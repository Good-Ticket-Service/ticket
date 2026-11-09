package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResult;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 토픽 payment.result의 메시지 형식. 형식이 바뀌면 이 클래스만 고친다.
 * 모르는 필드는 무시한다. payment가 필드를 더해도 소비가 멈추지 않게 하기 위해서다. 뜻이 달라지는 변경은 schemaVersion으로 구분한다.
 * 모르는 status는 거른다. 뜻을 짐작해 반영하면 돈이 어긋날 수 있어, DLT에서 사람이 본다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PaymentResultMessage(
        String eventId,
        Integer schemaVersion,
        OffsetDateTime occurredAt,
        String orderId,
        Long reservationId,
        String status) {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_EVENT_ID_LENGTH = 36;
    private static final int MAX_ORDER_ID_LENGTH = 64;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static PaymentResult parse(String payload, ObjectMapper objectMapper) {
        PaymentResultMessage message;
        // Note: 본문이 없는 메시지(tombstone)는 readValue가 JSON 예외가 아닌 예외를 던져 끝없이 재시도된다. 해석 불가로 걸러 DLT로 보낸다
        if (payload == null) {
            throw new InvalidPaymentResultMessage("결제 결과 메시지에 본문이 없다");
        }
        try {
            message = objectMapper.readValue(payload, PaymentResultMessage.class);
        } catch (JsonProcessingException e) {
            throw new InvalidPaymentResultMessage("결제 결과 메시지를 읽지 못했다", e);
        }
        return message.toResult();
    }

    private PaymentResult toResult() {
        if (schemaVersion == null || schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new InvalidPaymentResultMessage("모르는 스키마 버전이다: " + schemaVersion);
        }
        if (isBlank(eventId) || isBlank(orderId) || reservationId == null || reservationId <= 0 || occurredAt == null) {
            throw new InvalidPaymentResultMessage("필수 필드가 없다");
        }
        // Note: 반영 실패는 끝없이 재시도하므로, 저장할 수 없는 값은 여기서 걸러야 파티션이 멈추지 않는다
        if (eventId.length() > MAX_EVENT_ID_LENGTH || orderId.length() > MAX_ORDER_ID_LENGTH) {
            throw new InvalidPaymentResultMessage("eventId나 orderId가 저장할 수 있는 길이를 넘는다");
        }
        return new PaymentResult(
                eventId,
                reservationId,
                orderId,
                outcome(),
                occurredAt.atZoneSameInstant(SEOUL).toLocalDateTime());
    }

    private ConfirmOutcome outcome() {
        if ("DONE".equals(status)) {
            return ConfirmOutcome.succeeded();
        }
        if ("FAILED".equals(status)) {
            return ConfirmOutcome.failed();
        }
        throw new InvalidPaymentResultMessage("모르는 결제 상태다: " + status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
