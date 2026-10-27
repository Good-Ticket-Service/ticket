package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgApproveRequest;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgCancelRequest;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.kakao.KakaoPgClient;
import com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

/**
 * 승인·취소가 실패해도 어댑터가 스스로 다시 보내지 않는지, PG 요청에 팀 내부 헤더(X-User-*)가 실리지 않는지 확인한다.
 * 요청을 세야 하므로 fake PG 대신 항상 500을 돌려주는 로컬 소켓 서버를 쓴다.
 */
class PgClientNoRetryTest {

    private ServerSocket server;
    private Thread acceptor;
    private final List<List<String>> requestHeaders = new CopyOnWriteArrayList<>();

    /** 요청마다 헤더 이름을 기록하고 항상 500을 돌려주는 최소 HTTP 서버. 연결은 요청 하나 뒤 닫는다. */
    @BeforeEach
    void startServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        List<String> headers = new ArrayList<>();
        int contentLength = 0;
        reader.readLine();
        for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
            String name = line.substring(0, line.indexOf(':'));
            headers.add(name);
            if (name.equalsIgnoreCase("Content-Length")) {
                contentLength =
                        Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        for (int i = 0; i < contentLength; i++) {
            reader.read();
        }
        requestHeaders.add(headers);
        byte[] body = "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"error\"}"
                .getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 500 Internal Server Error\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    static Stream<Arguments> clients() {
        Function<PgProperties, PgClient> toss =
                properties -> new TossPgClient(RestClient.builder(), properties, new ObjectMapper());
        Function<PgProperties, PgClient> kakao =
                properties -> new KakaoPgClient(RestClient.builder(), properties, new ObjectMapper());
        return Stream.of(Arguments.of("TOSS", toss), Arguments.of("KAKAO", kakao));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clients")
    void approveIsSentOnceWithoutInternalHeaders(String provider, Function<PgProperties, PgClient> factory) {
        PgClient client = factory.apply(PgClientContractTest.properties("http://localhost:" + port()));

        PgResult result = client.approve(new PgApproveRequest("order-1", "T1", "token", 10_000, 1L));

        assertThat(result).isInstanceOf(PgResult.Unknown.class);
        assertThat(requestHeaders).hasSize(1);
        assertThat(requestHeaders.get(0)).noneMatch(name -> name.toLowerCase().startsWith("x-user"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clients")
    void cancelIsNotRetried(String provider, Function<PgProperties, PgClient> factory) {
        PgClient client = factory.apply(PgClientContractTest.properties("http://localhost:" + port()));

        PgResult result = client.cancel(new PgCancelRequest("order-1", "T1", 10_000, "order-1:cancel:0", "취소"));

        assertThat(result).isInstanceOf(PgResult.Unknown.class);
        // 카카오는 취소 전에 조회를 한 번 부른다
        assertThat(requestHeaders).hasSize(provider.equals("KAKAO") ? 2 : 1);
    }

    private int port() {
        return server.getLocalPort();
    }
}
