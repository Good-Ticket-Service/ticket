package com.sparta.ticket.payment.domain.repository.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface PaymentRepository {

    /** 저장하고 바로 INSERT·UPDATE를 실행한다. 유니크 제약 위반을 호출한 메서드 안에서 잡기 위해 쓴다. */
    Payment saveAndFlush(Payment payment);

    Optional<Payment> findById(Long id);

    Optional<Payment> findByOrderId(String orderId);

    /** 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 상태를 바꾸기 전에 최신 상태를 다시 판단할 때 쓴다. */
    Optional<Payment> lockById(Long id);

    /** 승인 접수는 선조회 없이 잠금 조회해 대기 중 바뀐 최신 상태를 읽는다. */
    Optional<Payment> lockByOrderId(String orderId);

    /** 같은 PG 키의 동시 사용을 트랜잭션 종료까지 직렬화한다. */
    void lockTransactionKey(String transactionKey);

    /** 논리 삭제된 행의 거래 키도 다시 쓰지 않는다. */
    boolean isPgTransactionInUse(String provider, String transactionId);

    /**
     * 예매의 살아 있는 결제(READY·IN_PROGRESS·DONE)를 잠가서 읽는다. 부분 UNIQUE 인덱스로 예매당 하나뿐이다.
     * 새 준비가 이전 READY를 대체할지, 진행 중이라 거절할지 판단할 때 쓴다.
     */
    Optional<Payment> lockLiveByReservationId(Long reservationId);

    List<Payment> lockNextExpiredReady(LocalDateTime now, Collection<Long> excludedIds);

    List<Payment> lockNextRecovery(LocalDateTime now, Collection<Long> excludedIds);

    LocalDateTime oldestDueRecovery(LocalDateTime now);

    List<Payment> lockNextReconcile(LocalDateTime now, Collection<Long> excludedIds);

    LocalDateTime oldestDueReconcile(LocalDateTime now);

    LocalDateTime oldestUnknownReconcile();

    List<Payment> lockNextRefund(LocalDateTime now, Collection<Long> excludedIds);

    List<Payment> lockNextRefundInModes(LocalDateTime now, Collection<Long> excludedIds, Collection<String> modes);

    LocalDateTime oldestDueRefund(LocalDateTime now);
}
