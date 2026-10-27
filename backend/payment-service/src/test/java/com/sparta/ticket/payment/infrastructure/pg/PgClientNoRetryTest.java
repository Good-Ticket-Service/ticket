package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgApproveRequest;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgCancelRequest;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.kakao.KakaoPgClient;
import com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * 요청을 세야 하므로 fake PG 대신 항상 500을 돌려주는 로컬 HTTP 서버를 쓴다.
 */
class PgClientNoRetryTest {

    private HttpServer server;
    private final List<List<String>> requestHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestHeaders.add(List.copyOf(exchange.getRequestHeaders().keySet()));
            byte[] body = "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"error\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
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
        return server.getAddress().getPort();
    }
}
