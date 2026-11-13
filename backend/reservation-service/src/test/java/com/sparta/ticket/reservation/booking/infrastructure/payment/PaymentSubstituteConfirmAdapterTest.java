package com.sparta.ticket.reservation.booking.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.autoconfigure.CommonFeignAutoConfiguration;
import com.sparta.ticket.common.feign.FeignErrorDecoder;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Accepted;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Busy;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.NotReceived;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Rejected;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer.Settled;
import com.sparta.ticket.reservation.booking.support.SwitchableConfirmSettings;
import com.sparta.ticket.reservation.infrastructure.config.JacksonConfig;
import feign.codec.ErrorDecoder;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
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
 * 대체 승인 계약(#422) 테스트. 실제 Feign 클라이언트로 HTTP 스텁 서버를 불러 요청 형식과 응답을 답으로 바꾸는 규칙을 확인한다.
 * 공통 오류 변환(FeignErrorDecoder)을 함께 띄워, 그것이 있어도 503의 Retry-After와 4xx 코드를 어댑터가 읽는지 본다.
 */
@SpringBootTest(
        classes = PaymentSubstituteConfirmAdapterTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.config.enabled=false")
class PaymentSubstituteConfirmAdapterTest {

    private static final StubPayment STUB = StubPayment.start();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIRM = "POST /internal/payments/order-1/confirm";
    private static final String FIND = "GET /internal/payments/order-1";
    private static final PaymentConfirmRequest REQUEST = new PaymentConfirmRequest(
            "order-1", 1L, 20_000L, "pk-1", LocalDateTime.of(2026, 11, 1, 20, 5, 0, 123_456_000));

    @Configuration
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class,
        CommonFeignAutoConfiguration.class
    })
    @EnableFeignClients(clients = SubstituteConfirmClient.class)
    @Import({PaymentSubstituteConfirmAdapter.class, JacksonConfig.class, SwitchableConfirmSettings.class})
    static class Config {}

    @DynamicPropertySource
    static void paymentUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.openfeign.client.config.substituteConfirmClient.url",
                () -> "http://localhost:" + STUB.port());
        registry.add("spring.cloud.openfeign.client.config.substituteConfirmClient.read-timeout", () -> "500");
    }

    @Autowired
    private PaymentSubstituteConfirmAdapter adapter;

    @Autowired
    private ErrorDecoder errorDecoder;

    @BeforeEach
    void setUp() {
        STUB.reset();
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    private static String confirmed(String status) {
        return """
                {"paymentId": 9, "status": "%s", "failureType": null, "failureCode": null}""".formatted(status);
    }

    private static String detail(String status, String confirmRequestedAt) {
        return """
                {"paymentId": 9, "orderId": "order-1", "reservationId": 1, "status": "%s", "amount": 20000,
                 "failureType": null, "failureCode": null, "confirmRequestedAt": %s,
                 "approvedAt": null, "refund": null}""".formatted(status, confirmRequestedAt == null ? "null" : "\"" + confirmRequestedAt + "\"");
    }

    private static String error(int status, String code) {
        return """
                {"status": %d, "code": "%s", "message": "거절"}""".formatted(status, code);
    }

    @Test
    @DisplayName("대체 승인은 Outbox 메시지의 인증값·금액·시한을 보내고, 시한은 자르지 않고 +09:00을 붙인다")
    void sendsMessageValues() throws Exception {
        STUB.respond(CONFIRM, 200, confirmed("DONE"));

        adapter.confirm(REQUEST);

        JsonNode body = JSON.readTree(STUB.lastBody(CONFIRM));
        assertThat(body.get("pgAuthToken").asText()).isEqualTo("pk-1");
        assertThat(body.get("amount").asInt()).isEqualTo(20_000);
        assertThat(body.get("confirmBefore").asText()).isEqualTo("2026-11-01T20:05:00.123456+09:00");
    }

    @Test
    @DisplayName("200의 DONE·FAILED는 정해진 결과, IN_PROGRESS·READY는 받았지만 결과가 정해지지 않은 것으로 바꾼다")
    void readsConfirmStatus() {
        Map<String, SubstituteAnswer> cases = Map.of(
                "DONE", new Settled(ConfirmOutcome.succeeded()),
                "FAILED", new Settled(ConfirmOutcome.failed()),
                "IN_PROGRESS", new Accepted(),
                "READY", new Accepted());
        cases.forEach((status, expected) -> {
            STUB.respond(CONFIRM, 200, confirmed(status));
            assertThat(adapter.confirm(REQUEST)).as(status).isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("공통 오류 변환이 있어도 503은 Retry-After 초만큼 쉬라는 답이고, 없거나 읽지 못하면 다시 볼 간격만큼 쉰다")
    void readsRetryAfterDespiteSharedErrorDecoder() {
        assertThat(errorDecoder).isInstanceOf(FeignErrorDecoder.class);

        STUB.respond(CONFIRM, 503, error(503, "SERVICE_UNAVAILABLE"), Map.of("Retry-After", "7"));
        assertThat(adapter.confirm(REQUEST)).isEqualTo(new Busy(Duration.ofSeconds(7)));

        STUB.respond(CONFIRM, 503, error(503, "SERVICE_UNAVAILABLE"));
        assertThat(adapter.confirm(REQUEST)).isEqualTo(new Busy(SwitchableConfirmSettings.RESEND_AFTER));

        STUB.respond(CONFIRM, 503, "", Map.of("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"));
        assertThat(adapter.confirm(REQUEST)).isEqualTo(new Busy(SwitchableConfirmSettings.RESEND_AFTER));
    }

    @Test
    @DisplayName("409·400·404와 모르는 4xx는 다시 보내도 같은 답이라 받지 않는다는 답으로 바꾸고 payment의 코드를 남긴다")
    void rejectsClientErrors() {
        Map<Integer, String> cases = Map.of(
                409, "PAYMENT_ORDER_CONFLICT",
                400, "PAYMENT_AMOUNT_MISMATCH",
                404, "PAYMENT_NOT_FOUND");
        cases.forEach((status, code) -> {
            STUB.respond(CONFIRM, status, error(status, code));
            assertThat(adapter.confirm(REQUEST)).as(code).isEqualTo(new Rejected(code));
        });

        STUB.respond(CONFIRM, 422, "not json");
        assertThat(adapter.confirm(REQUEST)).isEqualTo(new Rejected("HTTP 422"));
    }

    @Test
    @DisplayName("다른 5xx, 읽지 못하는 본문, 모르는 상태는 payment가 받았는지 모르는 것으로 본다")
    void unknownWhenAnswerIsUnreadable() {
        STUB.respond(CONFIRM, 500, error(500, "INTERNAL_ERROR"));
        assertThat(adapter.confirm(REQUEST)).isInstanceOf(SubstituteAnswer.Unknown.class);

        STUB.respond(CONFIRM, 200, "not json");
        assertThat(adapter.confirm(REQUEST)).isInstanceOf(SubstituteAnswer.Unknown.class);

        STUB.respond(CONFIRM, 200, confirmed("SUPERSEDED"));
        assertThat(adapter.confirm(REQUEST)).isInstanceOf(SubstituteAnswer.Unknown.class);
    }

    @Test
    @DisplayName("대체 승인이 응답하지 않으면 다시 보내지 않고 결과 모름으로 답한다")
    void confirmTimeoutIsNotRetried() {
        STUB.respondLate(CONFIRM, 1_000, 200, confirmed("DONE"));

        assertThat(adapter.confirm(REQUEST)).isInstanceOf(SubstituteAnswer.Unknown.class);
        assertThat(STUB.count(CONFIRM)).isEqualTo(1);
    }

    @Test
    @DisplayName("조회의 READY는 승인 접수 시각이 있으면 결과를 기다리는 중, 없으면 승인이 닿지 않은 것으로 본다")
    void lookupReadsConfirmRequestedAt() {
        STUB.respond(FIND, 200, detail("READY", "2026-11-01T20:00:01+09:00"));
        assertThat(adapter.lookup(REQUEST)).isEqualTo(new Accepted());

        STUB.respond(FIND, 200, detail("READY", null));
        assertThat(adapter.lookup(REQUEST)).isEqualTo(new NotReceived());
    }

    @Test
    @DisplayName("조회의 DONE은 성공, FAILED·CANCELED는 실패, IN_PROGRESS는 결과를 기다리는 중이고, 404는 받지 않는다는 답이다")
    void lookupReadsStatus() {
        Map<String, SubstituteAnswer> cases = Map.of(
                "DONE", new Settled(ConfirmOutcome.succeeded()),
                "FAILED", new Settled(ConfirmOutcome.failed()),
                "CANCELED", new Settled(ConfirmOutcome.failed()),
                "IN_PROGRESS", new Accepted());
        cases.forEach((status, expected) -> {
            STUB.respond(FIND, 200, detail(status, "2026-11-01T20:00:01+09:00"));
            assertThat(adapter.lookup(REQUEST)).as(status).isEqualTo(expected);
        });

        STUB.respond(FIND, 404, error(404, "PAYMENT_NOT_FOUND"));
        assertThat(adapter.lookup(REQUEST)).isEqualTo(new Rejected("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("조회가 응답하지 않으면 다시 보내지 않고 결과 모름으로 답한다")
    void lookupTimeoutIsNotRetried() {
        STUB.respondLate(FIND, 1_000, 200, detail("DONE", "2026-11-01T20:00:01+09:00"));

        assertThat(adapter.lookup(REQUEST)).isInstanceOf(SubstituteAnswer.Unknown.class);
        assertThat(STUB.count(FIND)).isEqualTo(1);
    }
}
