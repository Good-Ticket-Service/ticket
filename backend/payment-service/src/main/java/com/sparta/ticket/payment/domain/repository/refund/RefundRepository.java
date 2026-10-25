package com.sparta.ticket.payment.domain.repository.refund;

import com.sparta.ticket.payment.domain.model.refund.Refund;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface RefundRepository {

    Refund saveAndFlush(Refund refund);

    Optional<Refund> findByPaymentId(Long paymentId);

    /** 결제 행을 먼저 잠근 트랜잭션 안에서 부른다(잠금 순서: payment → refund). */
    Optional<Refund> lockByPaymentId(Long paymentId);
}
