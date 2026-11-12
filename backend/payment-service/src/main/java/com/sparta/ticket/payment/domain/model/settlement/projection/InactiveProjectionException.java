package com.sparta.ticket.payment.domain.model.settlement.projection;

/** 종료된 세대의 offset을 적용 없이 넘기지 않는다. rollback 재개 때 같은 위치부터 소비한다. */
public class InactiveProjectionException extends RuntimeException {
    public InactiveProjectionException() {
        super("INACTIVE_PROJECTION_BUILD");
    }
}
