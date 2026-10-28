package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.web.client.RestClient;

/** 실제 HTTP 응답의 다른 원거래·금액·잔액·누락 필드로 전액 환불을 확정하지 않는다. */
class PgCancellationValidationTest {

    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private ServerSocket server;
    private Thread acceptor;
    private final List<List<String>> requestHeaders = new CopyOnWriteArrayList<>();

    /** 요청별 성공 응답의 검증 필드를 바꾸는 최소 HTTP 서버다. */
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
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 " + responseStatus + " Response\r\n"
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "TOSS,foreign",
        "TOSS,amount",
        "TOSS,balance",
        "TOSS,missing",
        "KAKAO,foreign",
        "KAKAO,amount",
        "KAKAO,balance",
        "KAKAO,missing"
    })
    void invalidCancellationNeverBecomesSuccess(String provider, String kind) {
        boolean toss = provider.equals("TOSS");
        String tx = kind.equals("foreign") ? "OTHER" : "T1";
        int amount = kind.equals("amount") ? 1 : 10_000;
        int balance = kind.equals("balance") ? 1 : 0;
        responseBody = toss
                ? "{\"paymentKey\":\"" + tx + "\",\"orderId\":\"order-1\",\"status\":\"CANCELED\",\"balanceAmount\":"
                        + balance + ",\"cancels\":[{\"cancelAmount\":" + amount
                        + ",\"transactionKey\":\"c1\",\"canceledAt\":\"2026-10-28T12:00:00+09:00\"}]}"
                : "{\"tid\":\"" + tx + "\",\"status\":\"CANCEL_PAYMENT\",\"canceled_amount\":{\"total\":" + amount
                        + "},\"cancel_available_amount\":{\"total\":" + balance
                        + "},\"canceled_at\":\"2026-10-28T12:00:00\"}";
        if (kind.equals("missing")) responseBody = "{\"status\":\"CANCELED\"}";
        var props = PgClientContractTest.properties("http://localhost:" + port());
        PgClient client = toss
                ? new TossPgClient(RestClient.builder(), props, new ObjectMapper())
                : new KakaoPgClient(RestClient.builder(), props, new ObjectMapper());
        assertThat(client.cancel(new PgCancelRequest("order-1", "T1", 10_000, "order-1:cancel:0", "USER_CANCEL")))
                .isInstanceOf(PgResult.Unknown.class);
    }

    @org.junit.jupiter.api.Test
    void unknownTossCancelCodeDoesNotBecomeDefiniteFailure() {
        responseStatus = 400;
        responseBody = "{\"code\":\"NEW_UNCLASSIFIED_ERROR\",\"message\":\"unknown\"}";
        var client = new TossPgClient(
                RestClient.builder(),
                PgClientContractTest.properties("http://localhost:" + port()),
                new ObjectMapper());
        assertThat(client.cancel(new PgCancelRequest("order-1", "T1", 10_000, "order-1:cancel:0", "USER_CANCEL")))
                .isInstanceOf(PgResult.Unknown.class);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"TOSS,identity", "TOSS,partial", "KAKAO,identity", "KAKAO,partial"})
    void inconsistentInquiriesCannotAuthorizeNewCancellation(String provider, String kind) {
        boolean toss = provider.equals("TOSS");
        responseStatus = 200;
        responseBody = toss
                ? "{\"paymentKey\":\"T1\",\"orderId\":\"" + (kind.equals("identity") ? "OTHER" : "order-1")
                        + "\",\"status\":\"PARTIAL_CANCELED\"}"
                : "{\"tid\":\"T1\",\"partner_order_id\":\"" + (kind.equals("identity") ? "OTHER" : "order-1")
                        + "\",\"status\":\"PART_CANCEL_PAYMENT\"}";
        var props = PgClientContractTest.properties("http://localhost:" + port());
        PgClient client = toss
                ? new TossPgClient(RestClient.builder(), props, new ObjectMapper())
                : new KakaoPgClient(RestClient.builder(), props, new ObjectMapper());
        assertThat(client.inquire(new PgClient.PgInquireRequest("order-1", "T1")))
                .isInstanceOf(com.sparta.ticket.payment.domain.model.pg.PgInquiry.Inconsistent.class);
    }

    private int port() {
        return server.getLocalPort();
    }
}
