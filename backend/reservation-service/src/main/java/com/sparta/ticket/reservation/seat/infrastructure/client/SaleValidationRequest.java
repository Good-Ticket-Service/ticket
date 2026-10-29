package com.sparta.ticket.reservation.seat.infrastructure.client;

import java.util.List;

/** POST /internal/schedules/{scheduleId}/sale-validations 요청. configVersion은 좌석이 가진 회차 좌석 구성 버전이다. */
public record SaleValidationRequest(long configVersion, List<Long> venueSeatIds) {}
