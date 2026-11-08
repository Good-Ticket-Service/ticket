package com.sparta.ticket.payment.application.port.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementJobReason;
import java.time.LocalDateTime;

/**
 * 정산 반영 작업 저장. 호출자 트랜잭션(결제 승인 확정·환불 완료)에 함께 커밋되어, 확정과 후속 반영 책임이 함께 남거나 함께 사라진다.
 * 같은 결제·사유가 이미 있으면 아무것도 하지 않는다(재시도·중복 호출에 안전하다).
 */
public interface SettlementJobQueue {
    void enqueue(Long paymentId, SettlementJobReason reason, LocalDateTime now);
}
