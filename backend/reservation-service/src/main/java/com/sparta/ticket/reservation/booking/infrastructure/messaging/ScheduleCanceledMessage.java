package com.sparta.ticket.reservation.booking.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.reservation.booking.application.schedulecancel.ScheduleCanceled;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 토픽 schedule.canceled의 메시지 형식. 형식이 바뀌면 이 클래스만 고친다.
 * 모르는 필드는 무시한다. 공연이 필드를 더해도 소비가 멈추지 않게 하기 위해서다. 뜻이 달라지는 변경은 schemaVersion으로 구분한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ScheduleCanceledMessage(String eventId, Integer schemaVersion, Long scheduleId, OffsetDateTime canceledAt) {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_EVENT_ID_LENGTH = 36;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static ScheduleCanceled parse(String payload, ObjectMapper objectMapper) {
        ScheduleCanceledMessage message;
        try {
            message = objectMapper.readValue(payload, ScheduleCanceledMessage.class);
        } catch (JsonProcessingException e) {
            throw new InvalidScheduleCanceledMessage("회차 취소 메시지를 읽지 못했다", e);
        }
        return message.toEvent();
    }

    private ScheduleCanceled toEvent() {
        if (schemaVersion == null || schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new InvalidScheduleCanceledMessage("모르는 스키마 버전이다: " + schemaVersion);
        }
        if (eventId == null || eventId.isBlank() || scheduleId == null || scheduleId <= 0 || canceledAt == null) {
            throw new InvalidScheduleCanceledMessage("필수 필드가 없다");
        }
        // Note: 저장 실패는 끝없이 재시도하므로, 저장할 수 없는 값은 여기서 걸러야 파티션이 멈추지 않는다
        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new InvalidScheduleCanceledMessage("eventId가 UUID보다 길다");
        }
        return new ScheduleCanceled(
                eventId, scheduleId, canceledAt.atZoneSameInstant(SEOUL).toLocalDateTime());
    }
}
