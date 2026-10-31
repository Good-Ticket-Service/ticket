package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다.
 * 상태를 바꾸는 메서드는 모두 조건부 UPDATE이고, 바뀐 행 수(0 또는 1)를 돌려준다.
 */
public interface ReservationRepository {

    Reservation save(Reservation reservation);

    /** 저장하고 바로 DB에 쓴다. 제약 위반을 호출 안에서 알아야 할 때 쓴다. */
    Reservation saveAndFlush(Reservation reservation);

    Optional<Reservation> findById(Long id);

    /** 이 회원의 예약 가운데 주어진 상태인 것. 같은 시각의 예약도 페이지마다 순서가 같도록 정렬에 ID를 덧붙인다. */
    Page<ReservationSummary> findMine(Long userId, Collection<ReservationStatus> statuses, Pageable pageable);

    /** 같은 트랜잭션에서 조건부 UPDATE를 한 뒤 DB의 지금 값을 다시 읽는다. */
    Optional<Reservation> reload(Long id);

    /**
     * 결제 준비 결과를 기록한다. 상태는 바꾸지 않는다. order_id와 user_coupon_id가 이 준비가 본 값 그대로일 때만 쓴다.
     * 쿠폰을 바꾸면 order_id가 비므로 order_id만 보면, 그사이 다른 준비가 쿠폰을 또 바꿔도 바뀌기 전 할인액으로 만든 금액이 기록된다.
     */
    int recordCheckout(
            Long id,
            Long userId,
            String expectedOrderId,
            Long expectedUserCouponId,
            String orderId,
            long paymentAmount,
            ChangeStamp stamp);

    /**
     * 결제 준비에서 예약에 잡은 쿠폰을 바꾼다. 상태는 바꾸지 않는다. HOLD, 본인, 만료 전이고 user_coupon_id가 처음 읽은 값
     * 그대로일 때만 쓴다. 할인액과 지난 준비의 orderId·결제 금액을 지운다. 지난 준비는 바뀌기 전 할인액으로 만든 주문이라
     * 그 orderId로 승인하면 예약에 잡힌 쿠폰과 다른 금액이 결제된다.
     */
    int changeCoupon(Long id, Long userId, Long expectedUserCouponId, Long userCouponId, ChangeStamp stamp);

    /** 바꾼 쿠폰의 할인액을 기록한다. 같은 트랜잭션에서 changeCoupon이 1행을 바꾼 뒤에만 부른다. */
    int recordDiscount(Long id, long discountAmount);

    /** HOLD → PAYMENT_IN_PROGRESS. 본인, 만료 전, 마지막 준비의 orderId일 때만 바꾼다. */
    int startPayment(Long id, Long userId, String orderId, String pgAuthToken, ChangeStamp stamp);

    /** PAYMENT_IN_PROGRESS → CONFIRMED. 결과의 orderId가 지금 시도일 때만 바꾼다. */
    int confirm(Long id, String orderId, ChangeStamp stamp);

    /**
     * PAYMENT_IN_PROGRESS → HOLD. 결제 실패이고 선점 시간이 남았을 때만 바꾼다.
     * 실패한 시도의 orderId와 금액을 지워, 같은 orderId로 다시 승인하지 못하고 새로 준비하게 한다.
     * 환불이 필요하다고 기록된 예약은 바꾸지 않는다.
     */
    int returnToHold(Long id, String orderId, ChangeStamp stamp);

    /** PAYMENT_IN_PROGRESS → EXPIRED. 결제 실패이고 선점 시간이 지났을 때만 바꾼다. 환불이 필요하다고 기록된 예약은 바꾸지 않는다. */
    int expireAfterFailure(Long id, String orderId, ChangeStamp stamp);

    /** HOLD → EXPIRED. 선점 시간이 지났을 때만 바꾼다. */
    int expireHold(Long id, ChangeStamp stamp);

    /** HOLD → RELEASED. 본인 예약이고 선점 시간이 남았을 때만 바꾼다. */
    int releaseHold(Long id, Long userId, ChangeStamp stamp);

    /** CONFIRMED → CANCELING. 본인 예약이고 회차 시작 전일 때만 바꾼다. 좌석은 판매된 그대로다. */
    int startCancel(Long id, Long userId, ChangeStamp stamp);

    /** CANCELING → CANCELED. payment가 환불 요청을 받은 뒤 부른다. 회차가 시작됐어도 바꾼다. */
    int cancel(Long id, ChangeStamp stamp);

    /**
     * PAYMENT_IN_PROGRESS → CANCELED. 확정하지 못한 성공으로 기록된 지금 시도의 환불을 payment가 받은 뒤 부른다.
     * 확정한 적이 없으므로 취소 중을 거치지 않는다.
     */
    int cancelUnconfirmed(Long id, String orderId, ChangeStamp stamp);

    /** 이 회원·회차의 진행 중인(HOLD·결제 중·확정·취소 중) 예약이 가진 티켓 수. */
    long countActiveTickets(Long userId, Long scheduleId);

    /** 이 회원·회차의 HOLD 예약. 진행 중인 예약은 부분 UNIQUE로 하나뿐이므로 많아야 하나다. 만료 여부는 보지 않는다. */
    Optional<Reservation> findHold(Long userId, Long scheduleId);

    /** 넘긴 id 중 결제 중인 예약의 id. 빈 목록을 넘기지 않는다. */
    List<Long> findPaymentInProgressIds(Collection<Long> ids);

    /** 결과 확인 스케줄러가 확인한 시각을 남긴다. checkedBefore 뒤로 이미 집은 인스턴스가 있으면 0행이다. 상태와 감사 컬럼은 바꾸지 않는다. */
    int markPaymentChecked(Long id, LocalDateTime checkedAt, LocalDateTime checkedBefore);

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

    /** 환불이 필요하다고 기록된 결제 중 예약을, 마지막 환불 요청(없으면 기록 시각)이 checkedBefore 이전인 것부터 고른다. 잠그지 않는다. */
    List<Long> findRefundRequiredIds(LocalDateTime checkedBefore, int limit);

    /**
     * 확정하지 못한 결제의 환불을 요청한 시각을 남긴다. 결과 확인이 쓰는 확인 시각 컬럼을 같이 쓴다. 환불이 필요하다고 기록된 예약은
     * 결과 확인이 고르지 않으므로 겹치지 않는다. 마지막 요청(없으면 기록 시각)이 checkedBefore 이전일 때만 1행이라, 여러 인스턴스가
     * 같은 예약을 같은 차례에 고르면 한쪽만 요청한다. 상태와 감사 컬럼은 바꾸지 않는다.
     */
    int markRefundChecked(Long id, LocalDateTime checkedAt, LocalDateTime checkedBefore);

    /**
     * 취소 확인 스케줄러가 환불을 다시 요청한 시각을 남긴다. 아직 취소 중이고 마지막 확인(없으면 취소 시작)이 checkedBefore 이전일 때만
     * 1행이다. 여러 인스턴스가 같은 예약을 같은 차례에 고르면 한쪽만 다시 요청한다. 상태와 감사 컬럼은 바꾸지 않는다.
     */
    int markCancelChecked(Long id, LocalDateTime checkedAt, LocalDateTime checkedBefore);

    /** updatedBefore보다 먼저 취소 중이 된 예약을 오래 확인하지 않은 순으로 고른다. 잠그지 않는다. */
    List<Long> findStaleCancelIds(LocalDateTime updatedBefore, int limit);

    long countStalePayments(LocalDateTime updatedBefore);
}
