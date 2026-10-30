package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 보충 때 결제가 넘기는 환불 현재 상태. 정산은 환불 Entity를 직접 보지 않고 이 값만 쓴다.
 *
 * @param completed 환불이 끝났는지(DONE). 접수·실패·검증 대기는 false다
 * @param completedAt 환불 완료 시각. completed일 때만 있다
 */
public record RefundSnapshot(Long refundId, boolean completed, LocalDateTime completedAt) {

    public RefundSnapshot {
        Objects.requireNonNull(refundId, "refundId");
        if (completed && completedAt == null) throw new IllegalArgumentException("완료된 환불은 완료 시각이 있어야 한다");
    }
}
