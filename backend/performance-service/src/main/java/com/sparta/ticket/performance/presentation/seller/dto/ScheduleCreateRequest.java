package com.sparta.ticket.performance.presentation.seller.dto;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * 회차 등록 요청. 시각은 오프셋을 포함한 ISO 8601이다(예: 2026-12-01T19:00:00+09:00). 오프셋이 없으면 400 VALIDATION_ERROR다.
 * 시각 순서(예매 오픈 < 예매 마감 ≤ 공연 시작)는 서비스가 400 INVALID_TIME_RANGE로 확인한다.
 */
public record ScheduleCreateRequest(
        @NotNull OffsetDateTime startAt,
        @NotNull OffsetDateTime bookingOpenAt,
        @NotNull OffsetDateTime bookingCloseAt) {}
