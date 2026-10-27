package com.sparta.ticket.payment.domain.repository.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface PaymentRepository {

    /** 저장하고 바로 INSERT·UPDATE를 실행한다. 유니크 제약 위반을 호출한 메서드 안에서 잡기 위해 쓴다. */
    Payment saveAndFlush(Payment payment);

    Optional<Payment> findById(Long id);

    Optional<Payment> findByOrderId(String orderId);

    /** 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 상태를 바꾸기 전에 최신 상태를 다시 판단할 때 쓴다. */
    Optional<Payment> lockById(Long id);

    /**
     * 예매의 살아 있는 결제(READY·IN_PROGRESS·DONE)를 잠가서 읽는다. 부분 UNIQUE 인덱스로 예매당 하나뿐이다.
     * 새 준비가 이전 READY를 대체할지, 진행 중이라 거절할지 판단할 때 쓴다.
     */
    Optional<Payment> lockLiveByReservationId(Long reservationId);
}
