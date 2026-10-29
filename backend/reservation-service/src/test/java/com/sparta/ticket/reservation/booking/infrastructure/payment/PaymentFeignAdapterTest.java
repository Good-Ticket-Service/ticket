package com.sparta.ticket.reservation.booking.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.autoconfigure.CommonFeignAutoConfiguration;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentPrepareRequest;
import com.sparta.ticket.reservation.booking.application.port.PreparedPayment;
import com.sparta.ticket.reservation.booking.application.port.RefundOutcome;
import feign.RetryableException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
 * payment 내부 API 계약 테스트. 실제 Feign 클라이언트로 HTTP 스텁 서버를 불러 요청 형식과 응답 변환을 확인한다.
 * DB가 필요 없어 ReservationIntegrationTest와 컨텍스트를 나눈다.
 */
@SpringBootTest(
        classes = PaymentFeignAdapterTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.config.enabled=false")
class PaymentFeignAdapterTest {

    private static final StubPayment STUB = StubPayment.start();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Configuration
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class,
        CommonFeignAutoConfiguration.class
    })
    @EnableFeignClients(clients = {PaymentCommandClient.class, PaymentQueryClient.class})
    @Import(PaymentFeignAdapter.class)
    static class Config {}

    @DynamicPropertySource
    static void paymentUrl(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + STUB.port();
        registry.add("spring.cloud.openfeign.client.config.paymentCommandClient.url", () -> url);
        registry.add("spring.cloud.openfeign.client.config.paymentQueryClient.url", () -> url);
        registry.add("spring.cloud.openfeign.client.config.default.read-timeout", () -> "500");
    }

    @Autowired
    private PaymentFeignAdapter adapter;

    @BeforeEach
    void setUp() {
        STUB.reset();
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    private static PaymentPrepareRequest prepareRequest() {
        return new PaymentPrepareRequest(
                "order-1", 1L, 2L, 3L, 4L, 5L, 20_000L, 0L, "KAKAO", LocalDateTime.of(2026, 11, 1, 20, 10));
    }

    @Test
    @DisplayName("결제 준비는 계약의 필드를 보내고, READY면 결제 금액과 결제창 주소를 돌려준다")
    void prepare() throws Exception {
        STUB.respond("POST /internal/payments", 200, """
                {"paymentId": 9, "orderId": "order-1", "amount": 20000, "status": "READY",
                 "failureType": null, "failureCode": null, "checkoutUrl": "https://pg/checkout"}""");

        PreparedPayment prepared = adapter.prepare(prepareRequest());

        assertThat(prepared).isEqualTo(new PreparedPayment(20_000L, "https://pg/checkout"));
        JsonNode body = JSON.readTree(STUB.lastBody("POST /internal/payments"));
        assertThat(body.get("orderId").asText()).isEqualTo("order-1");
        assertThat(body.get("reservationId").asLong()).isEqualTo(1L);
        assertThat(body.get("userId").asLong()).isEqualTo(2L);
        assertThat(body.get("sellerId").asLong()).isEqualTo(3L);
        assertThat(body.get("performanceId").asLong()).isEqualTo(4L);
        assertThat(body.get("scheduleId").asLong()).isEqualTo(5L);
        assertThat(body.get("totalPrice").asInt()).isEqualTo(20_000);
        assertThat(body.get("discountAmount").asInt()).isZero();
        assertThat(body.get("pgProvider").asText()).isEqualTo("KAKAO");
        assertThat(body.get("expiresAt").asText()).as("시각은 KST 오프셋을 붙여 보낸다").isEqualTo("2026-11-01T20:10:00+09:00");
        assertThat(body.has("userCouponId")).as("쿠폰은 할인액으로만 보낸다").isFalse();
    }

    @Test
    @DisplayName("결제 준비가 200 FAILED면 503, 4xx면 payment의 오류 코드를 그대로 올린다")
    void prepareFailures() {
        STUB.respond("POST /internal/payments", 200, """
                {"paymentId": 9, "orderId": "order-1", "amount": 20000, "status": "FAILED",
                 "failureType": "CLOSED", "failureCode": "READY_FAILED", "checkoutUrl": null}""");
        assertThatThrownBy(() -> adapter.prepare(prepareRequest()))
                .extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SERVICE_UNAVAILABLE");

        STUB.respond("POST /internal/payments", 409, """
                {"status": 409, "code": "PAYMENT_ALREADY_IN_PROGRESS", "message": "진행 중인 결제가 있습니다."}""");
        assertThatThrownBy(() -> adapter.prepare(prepareRequest()))
                .extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("PAYMENT_ALREADY_IN_PROGRESS");
    }

    @Test
    @DisplayName("결제 준비가 응답하지 않으면 다시 보내지 않고 503으로 끝낸다")
    void prepareTimeout() {
        STUB.delay("POST /internal/payments", 1_000);

        assertThatThrownBy(() -> adapter.prepare(prepareRequest()))
                .extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(STUB.count("POST /internal/payments")).isEqualTo(1);
    }

    @Test
    @DisplayName("승인은 인증값과 금액을 보내고, DONE·FAILED·IN_PROGRESS를 성공·실패·결과 모름으로 바꾼다")
    void confirm() throws Exception {
        String path = "POST /internal/payments/order-1/confirm";
        STUB.respond(path, 200, """
                {"paymentId": 9, "status": "DONE", "failureType": null, "failureCode": null}""");
        assertThat(adapter.confirm("order-1", "pk-1", 20_000L)).isEqualTo(ConfirmOutcome.succeeded());
        JsonNode body = JSON.readTree(STUB.lastBody(path));
        assertThat(body.get("pgAuthToken").asText()).isEqualTo("pk-1");
        assertThat(body.get("amount").asInt()).isEqualTo(20_000);

        STUB.respond(path, 200, """
                {"paymentId": 9, "status": "FAILED", "failureType": "REJECTED", "failureCode": "PG_REJECTED"}""");
        assertThat(adapter.confirm("order-1", "pk-1", 20_000L)).isEqualTo(ConfirmOutcome.failed());

        STUB.respond(path, 200, """
                {"paymentId": 9, "status": "IN_PROGRESS", "failureType": null, "failureCode": null}""");
        assertThat(adapter.confirm("order-1", "pk-1", 20_000L)).isEqualTo(ConfirmOutcome.unknown());
    }

    @Test
    @DisplayName("승인 금액이 다르다는 거절은 돈이 오가지 않았으므로 실패로 끝낸다")
    void confirmAmountMismatch() {
        STUB.respond("POST /internal/payments/order-1/confirm", 400, """
                {"status": 400, "code": "PAYMENT_AMOUNT_MISMATCH", "message": "결제 금액이 다릅니다."}""");

        assertThat(adapter.confirm("order-1", "pk-1", 20_000L)).isEqualTo(ConfirmOutcome.failed());
    }

    @Test
    @DisplayName("승인이 응답하지 않으면 다시 보내지 않는다. 다시 보내는 일은 결과 확인 스케줄러가 한다")
    void confirmTimeoutIsNotRetried() {
        String path = "POST /internal/payments/order-1/confirm";
        STUB.delay(path, 1_000);

        assertThatThrownBy(() -> adapter.confirm("order-1", "pk-1", 20_000L)).isInstanceOf(RetryableException.class);
        assertThat(STUB.count(path)).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 상태를 결과 확인 스케줄러가 쓰는 상태로 바꾸고, 404는 모르는 주문으로 본다")
    void find() {
        String path = "GET /internal/payments/order-1";
        Map<String, PaymentLookup> cases = Map.of(
                "READY", PaymentLookup.of(PaymentLookup.Status.NOT_RECEIVED),
                "IN_PROGRESS", PaymentLookup.of(PaymentLookup.Status.IN_PROGRESS),
                "DONE", PaymentLookup.of(PaymentLookup.Status.SUCCEEDED),
                "CANCELED", PaymentLookup.of(PaymentLookup.Status.FAILED));
        cases.forEach((status, expected) -> {
            STUB.respond(path, 200, detail(status, null));
            assertThat(adapter.find("order-1")).as(status).isEqualTo(expected);
        });

        STUB.respond(path, 200, detail("FAILED", "PG_UNRESOLVED"));
        assertThat(adapter.find("order-1")).isEqualTo(PaymentLookup.of(PaymentLookup.Status.FAILED));

        STUB.respond(path, 404, """
                {"status": 404, "code": "PAYMENT_NOT_FOUND", "message": "결제를 찾을 수 없습니다."}""");
        assertThat(adapter.find("order-1")).isEqualTo(PaymentLookup.of(PaymentLookup.Status.UNKNOWN_ORDER));
    }

    @Test
    @DisplayName("조회는 응답하지 않으면 최대 3번까지 보낸다")
    void findTimeoutIsRetried() {
        String path = "GET /internal/payments/order-1";
        STUB.delay(path, 1_000);

        assertThatThrownBy(() -> adapter.find("order-1")).isInstanceOf(RetryableException.class);
        assertThat(STUB.count(path)).isEqualTo(3);
    }

    @Test
    @DisplayName("환불은 사용자 취소 사유를 보내고, 200에 아는 refundStatus면 값과 관계없이 받은 것으로 본다")
    void refundAccepted() throws Exception {
        String path = "POST /internal/payments/order-1/refund";
        for (String refundStatus : List.of("WAITING", "REQUESTED", "DONE", "FAILED", "SKIPPED", "NOT_REQUIRED")) {
            STUB.respond(path, 200, """
                    {"refundStatus": "%s", "refundAmount": 20000}""".formatted(refundStatus));
            assertThat(adapter.refund("order-1")).as(refundStatus).isEqualTo(RefundOutcome.accepted());
        }
        assertThat(JSON.readTree(STUB.lastBody(path)).get("reason").asText()).isEqualTo("USER_CANCEL");
    }

    @Test
    @DisplayName("환불 응답의 상태를 모르거나 본문을 읽지 못하면 받았다고 단정하지 않는다. 404는 모르는 주문이다")
    void refundNotAccepted() {
        String path = "POST /internal/payments/order-1/refund";
        STUB.respond(path, 200, """
                {"refundStatus": "PENDING", "refundAmount": 20000}""");
        assertThat(adapter.refund("order-1")).isEqualTo(RefundOutcome.unknown());

        STUB.respond(path, 200, "{}");
        assertThat(adapter.refund("order-1")).isEqualTo(RefundOutcome.unknown());

        STUB.respond(path, 200, "not json");
        assertThatThrownBy(() -> adapter.refund("order-1")).isInstanceOf(RuntimeException.class);

        STUB.respond(path, 404, """
                {"status": 404, "code": "PAYMENT_NOT_FOUND", "message": "결제를 찾을 수 없습니다."}""");
        assertThat(adapter.refund("order-1")).isEqualTo(RefundOutcome.unknownOrder());
    }

    @Test
    @DisplayName("환불이 응답하지 않으면 다시 보내지 않는다. 다시 보내는 일은 취소 확인 스케줄러가 한다")
    void refundTimeoutIsNotRetried() {
        String path = "POST /internal/payments/order-1/refund";
        STUB.delay(path, 1_000);

        assertThatThrownBy(() -> adapter.refund("order-1")).isInstanceOf(RetryableException.class);
        assertThat(STUB.count(path)).isEqualTo(1);
    }

    private static String detail(String status, String failureCode) {
        return """
                {"paymentId": 9, "orderId": "order-1", "reservationId": 1, "status": "%s", "amount": 20000,
                 "failureType": null, "failureCode": %s, "confirmRequestedAt": "2026-11-01T20:05:00+09:00",
                 "approvedAt": null, "refund": null}""".formatted(status, failureCode == null ? "null" : "\"" + failureCode + "\"");
    }

    /**
     * "메서드 경로"마다 정해 둔 응답을 돌려주고, 받은 요청 본문과 횟수를 기록하는 HTTP 스텁.
     * 요청 한 건마다 연결을 닫는 HTTP/1.1만 다룬다. Feign 기본 클라이언트의 요청에는 이것으로 충분하다.
     */
    static final class StubPayment {

        private record Reply(int status, String body, long delayMillis) {}

        private final ServerSocket server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final Map<String, Reply> replies = new ConcurrentHashMap<>();
        private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();

        private StubPayment(ServerSocket server) {
            this.server = server;
        }

        static StubPayment start() {
            try {
                StubPayment stub = new StubPayment(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
                Thread acceptor = new Thread(stub::acceptLoop, "stub-payment");
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
