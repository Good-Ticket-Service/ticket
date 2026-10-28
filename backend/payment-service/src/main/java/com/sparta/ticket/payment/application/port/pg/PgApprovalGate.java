package com.sparta.ticket.payment.application.port.pg;

import com.sparta.ticket.payment.domain.model.pg.PgProvider;

/** 승인 직전 서킷 상태를 확인하는 포트. 실제 장애 격리 구현은 후속 작업에서 교체한다. */
public interface PgApprovalGate {
    boolean allows(PgProvider provider);
}
