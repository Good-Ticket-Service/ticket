package com.sparta.ticket.payment.domain.repository.settlement;

import java.time.LocalDateTime;

/** 정산서 발행 대상인 (판매자, 회차). 회차 시작 시각 → 회차 ID → 판매자 순으로 읽는다. */
public interface StatementCandidate {

    Long getSellerId();

    Long getScheduleId();

    LocalDateTime getScheduleStartAt();
}
