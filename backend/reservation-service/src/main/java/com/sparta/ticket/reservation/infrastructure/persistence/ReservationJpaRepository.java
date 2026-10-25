package com.sparta.ticket.reservation.infrastructure.persistence;

import com.sparta.ticket.reservation.domain.ChangeStamp;
import com.sparta.ticket.reservation.domain.Reservation;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import com.sparta.ticket.reservation.domain.ReservationStatus;
import com.sparta.ticket.reservation.domain.ReservationTransition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 전이 메서드는 default 메서드가 {@link ReservationTransition}에서 from·to를 꺼내 아래 쿼리에 넘긴다.
 * 쿼리 문자열에 상태 이름을 쓰지 않아 전이 규칙이 표 한 곳에만 남는다.
 *
 * Note: 조건부 UPDATE는 영속성 컨텍스트를 거치지 않고 DB를 바로 바꾼다. 앞서 읽어 둔 Entity가 옛 상태로 남지 않게
 * clearAutomatically로 컨텍스트를 비우고, 아직 쓰지 않은 변경이 UPDATE보다 늦게 나가지 않게 flushAutomatically를 켠다.
 */
public interface ReservationJpaRepository extends JpaRepository<Reservation, UUID>, ReservationRepository {

    @Override
    default int recordCheckout(
            UUID id, UUID userId, String expectedOrderId, String orderId, long paymentAmount, ChangeStamp stamp) {
        return updateCheckout(
                id, ReservationStatus.HOLD, userId, expectedOrderId, orderId, paymentAmount, stamp.at(), stamp.by());
    }

    @Override
    default int startPayment(UUID id, UUID userId, String orderId, String pgAuthToken, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.START_PAYMENT;
        return updateStartPayment(id, t.from(), t.to(), userId, orderId, pgAuthToken, stamp.at(), stamp.by());
    }

    @Override
    default int confirm(UUID id, String orderId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.CONFIRM;
        return updatePaymentResult(id, t.from(), t.to(), orderId, stamp.at(), stamp.by());
    }

    @Override
    default int returnToHold(UUID id, String orderId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.RETURN_TO_HOLD;
        return updatePaymentFailureBeforeExpiry(id, t.from(), t.to(), orderId, stamp.at(), stamp.by());
    }

    @Override
    default int expireAfterFailure(UUID id, String orderId, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.EXPIRE_AFTER_FAILURE;
        return updatePaymentFailureAfterExpiry(id, t.from(), t.to(), orderId, stamp.at(), stamp.by());
    }

    @Override
    default int expireHold(UUID id, ChangeStamp stamp) {
        ReservationTransition t = ReservationTransition.EXPIRE_HOLD;
        return updateExpireHold(id, t.from(), t.to(), stamp.at(), stamp.by());
    }

    @Override
    default List<UUID> findExpiredHoldIds(LocalDateTime now, int limit) {
        return selectExpiredHoldIds(ReservationStatus.HOLD, now, PageRequest.of(0, limit));
    }

    @Override
    default List<UUID> findStalePaymentIds(LocalDateTime updatedBefore, int limit) {
        return selectStaleIds(ReservationStatus.PAYMENT_IN_PROGRESS, updatedBefore, PageRequest.of(0, limit));
    }

    /** order_id를 처음 읽은 값과 비교한다. 처음 준비라 둘 다 null인 경우도 같다고 본다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.orderId = :orderId, r.paymentAmount = :paymentAmount, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :status AND r.userId = :userId AND r.holdExpiresAt > :at
               AND COALESCE(r.orderId, '') = COALESCE(:expectedOrderId, '')
            """)
    int updateCheckout(
            @Param("id") UUID id,
            @Param("status") ReservationStatus status,
            @Param("userId") UUID userId,
            @Param("expectedOrderId") String expectedOrderId,
            @Param("orderId") String orderId,
            @Param("paymentAmount") long paymentAmount,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = :pgAuthToken, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.userId = :userId
               AND r.orderId = :orderId AND r.holdExpiresAt > :at
            """)
    int updateStartPayment(
            @Param("id") UUID id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("userId") UUID userId,
            @Param("orderId") String orderId,
            @Param("pgAuthToken") String pgAuthToken,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.orderId = :orderId
            """)
    int updatePaymentResult(
            @Param("id") UUID id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("orderId") String orderId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.orderId = :orderId AND r.holdExpiresAt > :at
            """)
    int updatePaymentFailureBeforeExpiry(
            @Param("id") UUID id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("orderId") String orderId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.orderId = :orderId AND r.holdExpiresAt <= :at
            """)
    int updatePaymentFailureAfterExpiry(
            @Param("id") UUID id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("orderId") String orderId,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reservation r
               SET r.status = :to, r.pgAuthToken = NULL, r.updatedAt = :at, r.updatedBy = :by
             WHERE r.id = :id AND r.status = :from AND r.holdExpiresAt <= :at
            """)
    int updateExpireHold(
            @Param("id") UUID id,
            @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to,
            @Param("at") LocalDateTime at,
            @Param("by") String by);

    @Query("""
            SELECT r.id FROM Reservation r
             WHERE r.status = :status AND r.holdExpiresAt <= :now
             ORDER BY r.holdExpiresAt
            """)
    List<UUID> selectExpiredHoldIds(
            @Param("status") ReservationStatus status, @Param("now") LocalDateTime now, Pageable pageable);

    @Query("""
            SELECT r.id FROM Reservation r
             WHERE r.status = :status AND r.updatedAt <= :updatedBefore
             ORDER BY r.updatedAt
            """)
    List<UUID> selectStaleIds(
            @Param("status") ReservationStatus status,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            Pageable pageable);
}
