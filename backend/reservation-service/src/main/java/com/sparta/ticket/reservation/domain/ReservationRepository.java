package com.sparta.ticket.reservation.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다.
 * 상태를 바꾸는 메서드는 모두 조건부 UPDATE이고, 바뀐 행 수(0 또는 1)를 돌려준다.
 */
public interface ReservationRepository {

    Reservation save(Reservation reservation);

    Optional<Reservation> findById(UUID id);

    /** 같은 트랜잭션에서 조건부 UPDATE를 한 뒤 DB의 지금 값을 다시 읽는다. */
    Optional<Reservation> reload(UUID id);

    /** 결제 준비 결과를 기록한다. 상태는 바꾸지 않는다. order_id가 처음 읽은 값 그대로일 때만 쓴다. */
    int recordCheckout(
            UUID id, UUID userId, String expectedOrderId, String orderId, long paymentAmount, ChangeStamp stamp);

    /** HOLD → PAYMENT_IN_PROGRESS. 본인, 만료 전, 마지막 준비의 orderId일 때만 바꾼다. */
    int startPayment(UUID id, UUID userId, String orderId, String pgAuthToken, ChangeStamp stamp);

    /** PAYMENT_IN_PROGRESS → CONFIRMED. 결과의 orderId가 지금 시도일 때만 바꾼다. */
    int confirm(UUID id, String orderId, ChangeStamp stamp);

    /**
     * PAYMENT_IN_PROGRESS → HOLD. 결제 실패이고 선점 시간이 남았을 때만 바꾼다.
     * 실패한 시도의 orderId와 금액을 지워, 같은 orderId로 다시 승인하지 못하고 새로 준비하게 한다.
     */
    int returnToHold(UUID id, String orderId, ChangeStamp stamp);

    /** PAYMENT_IN_PROGRESS → EXPIRED. 결제 실패이고 선점 시간이 지났을 때만 바꾼다. */
    int expireAfterFailure(UUID id, String orderId, ChangeStamp stamp);

    /** HOLD → EXPIRED. 선점 시간이 지났을 때만 바꾼다. */
    int expireHold(UUID id, ChangeStamp stamp);

    /** 결과 확인 스케줄러가 확인한 시각을 남긴다. 상태와 감사 컬럼은 바꾸지 않는다. */
    int markPaymentChecked(UUID id, LocalDateTime checkedAt);

    /** 시간이 지난 HOLD를 오래된 순으로 고른다. 잠그지 않는다. */
    List<UUID> findExpiredHoldIds(LocalDateTime now, int limit);

    /** updatedBefore보다 먼저 결제 중이 된 예약을 오래 확인하지 않은 순으로 고른다. 잠그지 않는다. */
    List<UUID> findStalePaymentIds(LocalDateTime updatedBefore, int limit);

    long countPaymentsInProgress();

    long countStalePayments(LocalDateTime updatedBefore);
}
