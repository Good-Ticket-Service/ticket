package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다.
 * 상태를 바꾸는 메서드는 모두 조건부 UPDATE이고, 바뀐 행 수(0 또는 1)를 돌려준다.
 */
public interface ReservationRepository {

    Reservation save(Reservation reservation);

    /** 저장하고 바로 DB에 쓴다. 제약 위반을 호출 안에서 알아야 할 때 쓴다. */
    Reservation saveAndFlush(Reservation reservation);

    Optional<Reservation> findById(Long id);

    /** 같은 트랜잭션에서 조건부 UPDATE를 한 뒤 DB의 지금 값을 다시 읽는다. */
    Optional<Reservation> reload(Long id);

    /** 결제 준비 결과를 기록한다. 상태는 바꾸지 않는다. order_id가 처음 읽은 값 그대로일 때만 쓴다. */
    int recordCheckout(
            Long id, Long userId, String expectedOrderId, String orderId, long paymentAmount, ChangeStamp stamp);

    /** HOLD → PAYMENT_IN_PROGRESS. 본인, 만료 전, 마지막 준비의 orderId일 때만 바꾼다. */
    int startPayment(Long id, Long userId, String orderId, String pgAuthToken, ChangeStamp stamp);

    /** PAYMENT_IN_PROGRESS → CONFIRMED. 결과의 orderId가 지금 시도일 때만 바꾼다. */
    int confirm(Long id, String orderId, ChangeStamp stamp);

    /**
     * PAYMENT_IN_PROGRESS → HOLD. 결제 실패이고 선점 시간이 남았을 때만 바꾼다.
     * 실패한 시도의 orderId와 금액을 지워, 같은 orderId로 다시 승인하지 못하고 새로 준비하게 한다.
     */
    int returnToHold(Long id, String orderId, ChangeStamp stamp);

    /** PAYMENT_IN_PROGRESS → EXPIRED. 결제 실패이고 선점 시간이 지났을 때만 바꾼다. */
    int expireAfterFailure(Long id, String orderId, ChangeStamp stamp);

    /** HOLD → EXPIRED. 선점 시간이 지났을 때만 바꾼다. */
    int expireHold(Long id, ChangeStamp stamp);

    /** HOLD → RELEASED. 본인 예약이고 선점 시간이 남았을 때만 바꾼다. */
    int releaseHold(Long id, Long userId, ChangeStamp stamp);

    /** 이 회원·회차의 진행 중인(HOLD·결제 중·확정) 예약이 가진 티켓 수. */
    long countActiveTickets(Long userId, Long scheduleId);

    /** 이 회원·회차의 HOLD 예약. 진행 중인 예약은 부분 UNIQUE로 하나뿐이므로 많아야 하나다. 만료 여부는 보지 않는다. */
    Optional<Reservation> findHold(Long userId, Long scheduleId);

    /** 넘긴 id 중 결제 중인 예약의 id. 빈 목록을 넘기지 않는다. */
    List<Long> findPaymentInProgressIds(Collection<Long> ids);

    /** 결과 확인 스케줄러가 확인한 시각을 남긴다. 상태와 감사 컬럼은 바꾸지 않는다. */
    int markPaymentChecked(Long id, LocalDateTime checkedAt);

    /**
     * 결제 중인 지금 시도가 성공했는데 확정하지 못했다고 기록한다. 이미 기록됐으면 0행이다.
     * 상태와 감사 컬럼은 바꾸지 않는다.
     */
    int markRefundRequired(Long id, String orderId, LocalDateTime at);

    /** 시간이 지난 HOLD를 오래된 순으로 고른다. 잠그지 않는다. */
    List<Long> findExpiredHoldIds(LocalDateTime now, int limit);

    /** updatedBefore보다 먼저 결제 중이 된 예약을 오래 확인하지 않은 순으로 고른다. 환불이 필요하다고 기록된 예약은 빼고, 잠그지 않는다. */
    List<Long> findStalePaymentIds(LocalDateTime updatedBefore, int limit);

    long countPaymentsInProgress();

    long countStalePayments(LocalDateTime updatedBefore);
}
