package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.infrastructure.config.ClockConfig;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 토픽 payment.confirm.requested의 메시지 형식(#318 제안). 형식이 바뀌면 이 클래스만 고친다.
 * 키는 reservationId 10진수 문자열이다. 결과 메시지와 같은 키라 같은 예약의 요청과 결과가 같은 파티션 번호를 쓴다.
 */
record PaymentConfirmRequestedMessage(
        String eventId,
        int schemaVersion,
        OffsetDateTime occurredAt,
        String orderId,
        Long reservationId,
        long amount,
        String pgAuthToken,
        OffsetDateTime confirmBefore) {

    static final String TOPIC = "payment.confirm.requested";
    private static final int SCHEMA_VERSION = 1;
    /** HTTP 대체 승인도 이 시간대로 confirmBefore를 보낸다. payment가 두 값으로 같은 지문을 만들어야 같은 요청으로 본다. */
    private static final ZoneId SEOUL = ClockConfig.ZONE;

    static PaymentConfirmRequestedMessage of(PaymentConfirmRequest request, LocalDateTime occurredAt) {
        return new PaymentConfirmRequestedMessage(
                UUID.randomUUID().toString(),
                SCHEMA_VERSION,
                atSeoul(occurredAt),
                request.orderId(),
                request.reservationId(),
                request.amount(),
                request.pgAuthToken(),
                atSeoul(request.confirmBefore()));
    }

    /** Outbox에 남긴 본문을 요청으로 되돌린다. 시한은 남길 때처럼 서울 시각으로 읽는다. */
    static PaymentConfirmRequest toRequest(ObjectMapper objectMapper, String payload) {
        try {
            PaymentConfirmRequestedMessage message =
                    objectMapper.readValue(payload, PaymentConfirmRequestedMessage.class);
            return new PaymentConfirmRequest(
                    message.orderId(),
                    message.reservationId(),
                    message.amount(),
                    message.pgAuthToken(),
                    message.confirmBefore().atZoneSameInstant(SEOUL).toLocalDateTime());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox의 승인 요청 메시지를 읽지 못했다", e);
        }
    }

    String key() {
        return reservationId.toString();
    }

    String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("승인 요청 메시지를 만들지 못했다. orderId=" + orderId, e);
        }
    }

    private static OffsetDateTime atSeoul(LocalDateTime time) {
        return time.atZone(SEOUL).toOffsetDateTime();
    }
}
