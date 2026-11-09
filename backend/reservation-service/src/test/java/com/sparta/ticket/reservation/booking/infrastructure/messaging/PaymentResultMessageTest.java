package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResult;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 메시지를 booking의 결과로 바꾸는 규칙만 본다. 소비자가 그 결과로 무엇을 하는지는 통합 테스트가 본다. */
class PaymentResultMessageTest {

    private static final String VALID = """
            {"eventId":"e-1","schemaVersion":1,"occurredAt":"2026-11-01T20:00:10+09:00","orderId":"order-1",\
            "reservationId":10,"paymentId":77,"status":"DONE","amount":10000,"failureType":null,"failureCode":null,\
            "approvedAt":"2026-11-01T20:00:09+09:00","somethingNew":true}""";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("DONE은 성공, FAILED는 실패로 바꾸고 시각은 서울 시각으로 읽는다. 모르는 필드는 무시한다")
    void parsesResult() {
        PaymentResult done = PaymentResultMessage.parse(VALID, objectMapper);
        PaymentResult failed = PaymentResultMessage.parse(VALID.replace("\"DONE\"", "\"FAILED\""), objectMapper);

        assertThat(done.eventId()).isEqualTo("e-1");
        assertThat(done.orderId()).isEqualTo("order-1");
        assertThat(done.reservationId()).isEqualTo(10L);
        assertThat(done.outcome()).isEqualTo(ConfirmOutcome.succeeded());
        assertThat(done.occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 20, 0, 10));
        assertThat(failed.outcome()).isEqualTo(ConfirmOutcome.failed());
    }

    @Test
    @DisplayName("UTC로 적은 시각도 같은 순간의 서울 시각으로 읽는다")
    void convertsZone() {
        PaymentResult result = PaymentResultMessage.parse(
                VALID.replace("2026-11-01T20:00:10+09:00", "2026-11-01T11:00:10Z"), objectMapper);

        assertThat(result.occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 20, 0, 10));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{not-json",
                "\"schemaVersion\":1 → \"schemaVersion\":2",
                "\"status\":\"DONE\" → \"status\":\"IN_PROGRESS\"",
                "\"status\":\"DONE\" → \"status\":null",
                "\"eventId\":\"e-1\", → ",
                "\"orderId\":\"order-1\", → ",
                "\"reservationId\":10, → \"reservationId\":0,",
                "\"occurredAt\":\"2026-11-01T20:00:10+09:00\", → "
            })
    @DisplayName("깨진 JSON, 모르는 버전·상태, 빠진 필수 필드는 해석 불가로 거른다")
    void rejectsUnreadable(String mutation) {
        String payload = mutation.contains(" → ")
                ? VALID.replace(mutation.split(" → ", -1)[0], mutation.split(" → ", -1)[1])
                : mutation;
        assertThat(payload).isNotEqualTo(VALID);

        assertThatThrownBy(() -> PaymentResultMessage.parse(payload, objectMapper))
                .isInstanceOf(InvalidPaymentResultMessage.class);
    }

    @Test
    @DisplayName("본문이 없는 메시지는 끝없이 재시도하지 않도록 해석 불가로 거른다")
    void rejectsNullPayload() {
        assertThatThrownBy(() -> PaymentResultMessage.parse(null, objectMapper))
                .isInstanceOf(InvalidPaymentResultMessage.class);
    }

    @Test
    @DisplayName("저장할 수 없는 길이의 eventId와 orderId는 해석 불가로 거른다")
    void rejectsTooLongIds() {
        String longEventId = VALID.replace("\"e-1\"", "\"" + "e".repeat(37) + "\"");
        String longOrderId = VALID.replace("\"order-1\"", "\"" + "o".repeat(65) + "\"");

        assertThatThrownBy(() -> PaymentResultMessage.parse(longEventId, objectMapper))
                .isInstanceOf(InvalidPaymentResultMessage.class);
        assertThatThrownBy(() -> PaymentResultMessage.parse(longOrderId, objectMapper))
                .isInstanceOf(InvalidPaymentResultMessage.class);
    }
}
