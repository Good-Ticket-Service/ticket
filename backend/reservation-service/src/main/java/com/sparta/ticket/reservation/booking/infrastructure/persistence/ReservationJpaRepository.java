package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import com.sparta.ticket.reservation.booking.domain.ChangeStamp;
import com.sparta.ticket.reservation.booking.domain.Reservation;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import com.sparta.ticket.reservation.booking.domain.ReservationTransition;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 전이 메서드는 default 메서드가 {@link ReservationTransition}에서 from·to를 꺼내 아래 쿼리에 넘긴다.
 * 쿼리 문자열에 전이의 상태 이름을 쓰지 않아 전이 규칙이 표 한 곳에만 남는다. updateXxx를 직접 부르지 않는다.
 *
 * Note: 조건부 UPDATE는 영속성 컨텍스트를 거치지 않고 DB를 바로 바꾼다. 아직 쓰지 않은 변경이 UPDATE보다 늦게 나가지 않게
 * flushAutomatically를 켠다. 컨텍스트는 비우지 않는다(이유는 ReservationReloaderImpl). UPDATE 뒤에 예약을 다시 봐야 하면 reload를 쓴다.
 */
public interface ReservationJpaRepository
        extends JpaRepository<Reservation, Long>, ReservationRepository, ReservationReloader {

    @Override
    default int recordCheckout(
            Long id, Long userId, String expectedOrderId, String orderId, long paymentAmount, ChangeStamp stamp) {
        return updateCheckout(
                id, ReservationStatus.HOLD, userId, expectedOrderId, orderId, paymentAmount, stamp.at(), stamp.by());
    }

    @Override
    default int startPayment(Long id, Long userId, String orderId, String pgAuthToken, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.START_PAYMENT;
        return updateStartPayment(id, t.from(), t.to(), userId, orderId, pgAuthToken, stamp.at(), stamp.by());
    }

    @Override
    default int confirm(Long id, String orderId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.CONFIRM;
        return updatePaymentResult(id, t.from(), t.to(), orderId, stamp.at(), stamp.by());
    }

    @Override
    default int returnToHold(Long id, String orderId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.RETURN_TO_HOLD;
        return updateReturnToHold(id, t.from(), t.to(), orderId, stamp.at(), stamp.by());
    }

    @Override
    default int expireAfterFailure(Long id, String orderId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.EXPIRE_AFTER_FAILURE;
        return updateExpireAfterFailure(id, t.from(), t.to(), orderId, stamp.at(), stamp.by());
    }

    @Override
    default int expireHold(Long id, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.EXPIRE_HOLD;
        return updateExpireHold(id, t.from(), t.to(), stamp.at(), stamp.by());
    }

    @Override
    default int releaseHold(Long id, Long userId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.RELEASE_HOLD;
        return updateReleaseHold(id, t.from(), t.to(), userId, stamp.at(), stamp.by());
    }

    @Override
    default List<Long> findExpiredHoldIds(LocalDateTime now, int limit) {
        return selectExpiredHoldIds(now, PageRequest.of(0, limit));
    }

    @Override
    default List<Long> findStalePaymentIds(LocalDateTime updatedBefore, int limit) {
        return selectStalePaymentIds(updatedBefore, PageRequest.of(0, limit));
    }

    /** order_id를 처음 읽은 값과 비교한다. 처음 준비라 둘 다 null인 경우도 같다고 본다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.orderId = :orderId, r.paymentAmount = :paymentAmount, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :status AND r.userId = :userId AND r.holdExpiresAt > :at
               AND COALESCE(r.orderId, '') = COALESCE(:expectedOrderId, '')
            """)
    int updateCheckout(
            @Param("id") Long id,
            @Param("status") ReservationStatus status,
            @Param("userId") Long userId,
            @Param("expectedOrderId") String expectedOrderId,
            @Param("orderId") String orderId,
            @Param("paymentAmount") long paymentAmount,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = :pgAuthToken, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.userId = :userId
               AND r.orderId = :orderId AND r.holdExpiresAt > :at
            """)
    int updateStartPayment(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("userId") Long userId,
            @Param("orderId") String orderId,
            @Param("pgAuthToken") String pgAuthToken,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.orderId = :orderId
            """)
    int updatePaymentResult(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("orderId") String orderId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.orderId = NULL, r.paymentAmount = NULL, r.pgAuthToken = NULL,
                   r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.orderId = :orderId AND r.holdExpiresAt > :at
            """)
    int updateReturnToHold(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("orderId") String orderId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.orderId = :orderId AND r.holdExpiresAt <= :at
            """)
    int updateExpireAfterFailure(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("orderId") String orderId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.holdExpiresAt <= :at
            """)
    int updateExpireHold(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.userId = :userId AND r.holdExpiresAt > :at
            """)
    int updateReleaseHold(
            @Param("id") Long id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("userId") Long userId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Reservation r SET r.paymentCheckedAt = :checkedAt
             WHERE r.id = :id AND r.status = com.sparta.ticket.reservation.booking.domain.ReservationStatus.PAYMENT_IN_PROGRESS
            """)
    int markPaymentChecked(@Param("id") Long id, @Param("checkedAt") LocalDateTime checkedAt);

    /** 상태를 리터럴로 써야 booking.sql의 부분 인덱스(WHERE status = 'HOLD')를 확실히 탄다. */
    @Query("""
            SELECT r.id FROM Reservation r
             WHERE r.status = com.sparta.ticket.reservation.booking.domain.ReservationStatus.HOLD
               AND r.holdExpiresAt <= :now
             ORDER BY r.holdExpiresAt
            """)
    List<Long> selectExpiredHoldIds(@Param("now") LocalDateTime now, Pageable pageable);

    /** 확인한 적 없는 예약을 먼저, 그다음 오래전에 확인한 예약을 고른다. 결과가 안 바뀌는 예약이 앞자리를 막지 않는다. */
    @Query("""
            SELECT r.id FROM Reservation r
             WHERE r.status = com.sparta.ticket.reservation.booking.domain.ReservationStatus.PAYMENT_IN_PROGRESS
               AND r.updatedAt <= :updatedBefore
             ORDER BY COALESCE(r.paymentCheckedAt, r.updatedAt), r.id
            """)
    List<Long> selectStalePaymentIds(@Param("updatedBefore") LocalDateTime updatedBefore, Pageable pageable);

    @Override
    @Query("""
            SELECT COUNT(r) FROM Reservation r
             WHERE r.status = com.sparta.ticket.reservation.booking.domain.ReservationStatus.PAYMENT_IN_PROGRESS
            """)
    long countPaymentsInProgress();

    @Override
    @Query("""
            SELECT COUNT(r) FROM Reservation r
             WHERE r.status = com.sparta.ticket.reservation.booking.domain.ReservationStatus.PAYMENT_IN_PROGRESS
               AND r.updatedAt <= :updatedBefore
            """)
    long countStalePayments(@Param("updatedBefore") LocalDateTime updatedBefore);
}
