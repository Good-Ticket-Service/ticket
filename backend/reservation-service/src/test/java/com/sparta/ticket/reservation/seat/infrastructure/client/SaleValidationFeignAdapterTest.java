package com.sparta.ticket.reservation.seat.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.autoconfigure.CommonFeignAutoConfiguration;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * performance 판매 검증 내부 API(P21) 계약 테스트. 실제 Feign 클라이언트로 HTTP 스텁 서버를 불러 요청 형식과 응답 변환을 확인한다.
 * DB가 필요 없어 ReservationIntegrationTest와 컨텍스트를 나눈다. 스텁은 PaymentFeignAdapterTest와 같은 방식이다.
 */
@SpringBootTest(
        classes = SaleValidationFeignAdapterTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.config.enabled=false")
class SaleValidationFeignAdapterTest {

    private static final StubServer STUB = StubServer.start();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "POST /internal/schedules/201/sale-validations";

    @Configuration
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class,
        CommonFeignAutoConfiguration.class
    })
    @EnableFeignClients(clients = SaleValidationClient.class)
    @Import(SaleValidationFeignAdapter.class)
    static class Config {}

    @DynamicPropertySource
    static void performanceUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.openfeign.client.config.saleValidationClient.url",
                () -> "http://localhost:" + STUB.port());
        registry.add("spring.cloud.openfeign.client.config.default.read-timeout", () -> "500");
    }

    @Autowired
    private SaleValidationFeignAdapter adapter;

    @BeforeEach
    void setUp() {
        STUB.reset();
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @Test
    @DisplayName("허용이면 공연·판매자 ID와 좌석 등급·가격을 돌려주고, 요청에는 구성 버전과 공연장 좌석 ID를 보낸다")
    void allowed() throws Exception {
        STUB.respond(PATH, 200, """
                {"allowed": true, "reasonCode": null, "scheduleId": 201, "performanceId": 11, "sellerId": 1001,
                 "configVersion": 2, "policyVersion": 3, "performanceTitle": "오페라의 유령",
                 "scheduleStartAt": "2026-11-30T19:00:00+09:00", "validatedAt": "2026-11-01T14:00:00+09:00",
                 "seats": [{"venueSeatId": 1001, "venueSeatGradeId": 101, "gradeName": "VIP", "basePrice": 150000}]}""");

        SaleValidation result = adapter.validate(201L, 2L, List.of(1001L));

        assertThat(result)
                .isEqualTo(new SaleValidation(
                        true,
                        null,
                        201L,
                        11L,
                        1001L,
                        2L,
                        3L,
                        List.of(new SaleValidationSeat(1001L, 101L, "VIP", 150_000))));
        JsonNode body = JSON.readTree(STUB.lastBody(PATH));
        assertThat(body.get("configVersion").asLong()).isEqualTo(2L);
        assertThat(body.get("venueSeatIds").get(0).asLong()).isEqualTo(1001L);
        assertThat(body.size()).as("계약의 요청 필드 두 개만 보낸다").isEqualTo(2);
    }

    @Test
    @DisplayName("판매 거절은 예외가 아니라 allowed=false와 reasonCode로 돌려준다")
    void rejected() {
        STUB.respond(PATH, 200, """
                {"allowed": false, "reasonCode": "SALES_CLOSED", "scheduleId": 201, "performanceId": 11,
                 "sellerId": 1001, "configVersion": 2, "policyVersion": 3, "seats": []}""");

        SaleValidation result = adapter.validate(201L, 2L, List.of(1001L));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("SALES_CLOSED");
        assertThat(result.seats()).isEmpty();
    }

    @Test
    @DisplayName("회차가 없으면 performance의 404 SCHEDULE_NOT_FOUND를 그대로 올린다")
    void scheduleNotFound() {
        STUB.respond(PATH, 404, """
                {"status": 404, "code": "SCHEDULE_NOT_FOUND", "message": "회차를 찾을 수 없습니다."}""");

        assertThatThrownBy(() -> adapter.validate(201L, 2L, List.of(1001L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode().status().value()).isEqualTo(404);
                    assertThat(e.getErrorCode().code()).isEqualTo("SCHEDULE_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("performance가 5xx로 응답하면 503이다")
    void serverError() {
        STUB.respond(PATH, 500, "{}");

        assertThatThrownBy(() -> adapter.validate(201L, 2L, List.of(1001L)))
                .extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("응답하지 않으면 다시 보내지 않고 503으로 끝낸다")
    void timeout() {
        STUB.delay(PATH, 1_000);

        assertThatThrownBy(() -> adapter.validate(201L, 2L, List.of(1001L)))
                .extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(STUB.count(PATH)).isEqualTo(1);
    }

    /**
     * "메서드 경로"마다 정해 둔 응답을 돌려주고, 받은 요청 본문과 횟수를 기록하는 HTTP 스텁.
     * 요청 한 건마다 연결을 닫는 HTTP/1.1만 다룬다. Feign 기본 클라이언트의 요청에는 이것으로 충분하다.
     */
    static final class StubServer {

        private record Reply(int status, String body, long delayMillis) {}

        private final ServerSocket server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final Map<String, Reply> replies = new ConcurrentHashMap<>();
        private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();

        private StubServer(ServerSocket server) {
            this.server = server;
        }

        static StubServer start() {
            try {
                StubServer stub = new StubServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
                Thread acceptor = new Thread(stub::acceptLoop, "stub-performance");
                acceptor.setDaemon(true);
                acceptor.start();
                return stub;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        int port() {
            return server.getLocalPort();
        }

        void stop() {
            workers.shutdownNow();
            try {
                server.close();
            } catch (IOException ignored) {
                // 테스트가 끝난 뒤라 상관없다.
            }
        }

        void reset() {
            replies.clear();
            bodies.clear();
        }

        void respond(String key, int status, String body) {
            replies.put(key, new Reply(status, body, 0));
        }

        void delay(String key, long millis) {
            replies.put(key, new Reply(200, "{}", millis));
        }

        String lastBody(String key) {
            List<String> received = bodies.get(key);
            return received.get(received.size() - 1);
        }

        int count(String key) {
            return bodies.getOrDefault(key, List.of()).size();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    workers.execute(() -> handle(socket));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String[] requestLine = in.readLine().split(" ");
                int contentLength = 0;
                for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
                    if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(
                                header.substring("content-length:".length()).trim());
                    }
                }
                char[] body = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                String key = requestLine[0] + " " + requestLine[1];
                bodies.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(new String(body, 0, read));
                Reply reply = replies.getOrDefault(key, new Reply(500, "{}", 0));
                if (reply.delayMillis() > 0) {
                    Thread.sleep(reply.delayMillis());
                }
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 " + reply.status() + " X\r\nContent-Type: application/json\r\nContent-Length: "
                                + bytes.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                // 클라이언트가 타임아웃으로 먼저 연결을 끊으면 쓰기가 실패한다. 테스트에는 상관없다.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
