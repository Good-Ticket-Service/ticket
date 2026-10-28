package com.sparta.ticket.payment.infrastructure.persistence.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<Payment, Long>, PaymentRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") String orderId);

    /** Note: 중복 키의 DB 예외에는 키 원문이 출력된다. 키 단위 잠금 후 선검사로 정상 경쟁을 예외 없이 처리한다. */
    @Override
    default void lockTransactionKey(String transactionKey) {
        acquireTransactionKey(transactionKey);
    }

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:transactionKey, 0))", nativeQuery = true)
    Integer acquireTransactionKey(@Param("transactionKey") String transactionKey);

    @Override
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM p_payments WHERE pg_provider = :provider"
                    + " AND pg_transaction_id = :transactionId)",
            nativeQuery = true)
    boolean isPgTransactionInUse(@Param("provider") String provider, @Param("transactionId") String transactionId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.reservationId = :reservationId"
            + " AND p.status IN (com.sparta.ticket.payment.domain.model.payment.PaymentStatus.READY,"
            + " com.sparta.ticket.payment.domain.model.payment.PaymentStatus.IN_PROGRESS,"
            + " com.sparta.ticket.payment.domain.model.payment.PaymentStatus.DONE)")
    Optional<Payment> lockLiveByReservationId(@Param("reservationId") Long reservationId);

    @Override
    @Query(
            value = "SELECT * FROM p_payments WHERE status = 'READY' AND expires_at <= :now"
                    + " AND deleted_at IS NULL AND id NOT IN (:excludedIds)"
                    + " ORDER BY expires_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Payment> lockNextExpiredReady(
            @Param("now") LocalDateTime now, @Param("excludedIds") Collection<Long> excludedIds);

    @Override
    @Query(
            value = "SELECT * FROM p_payments WHERE status = 'IN_PROGRESS' AND next_attempt_at <= :now"
                    + " AND deleted_at IS NULL AND id NOT IN (:excludedIds)"
                    + " ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Payment> lockNextRecovery(@Param("now") LocalDateTime now, @Param("excludedIds") Collection<Long> excludedIds);

    @Override
    @Query(
            value = "SELECT min(due_at) FROM ("
                    + " SELECT expires_at AS due_at FROM p_payments WHERE status = 'READY' AND expires_at <= :now AND deleted_at IS NULL"
                    + " UNION ALL SELECT next_attempt_at AS due_at FROM p_payments WHERE status = 'IN_PROGRESS'"
                    + " AND next_attempt_at <= :now AND deleted_at IS NULL) candidates",
            nativeQuery = true)
    LocalDateTime oldestDueRecovery(@Param("now") LocalDateTime now);

    @Override
    @Query(
            value = "SELECT * FROM p_payments WHERE status = 'FAILED' AND approve_requested_at IS NOT NULL"
                    + " AND next_reconcile_at <= :now AND deleted_at IS NULL AND id NOT IN (:excludedIds)"
                    + " ORDER BY next_reconcile_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Payment> lockNextReconcile(
            @Param("now") LocalDateTime now, @Param("excludedIds") Collection<Long> excludedIds);

    @Override
    @Query(
            value = "SELECT min(next_reconcile_at) FROM p_payments WHERE status = 'FAILED'"
                    + " AND approve_requested_at IS NOT NULL AND next_reconcile_at <= :now AND deleted_at IS NULL",
            nativeQuery = true)
    LocalDateTime oldestDueReconcile(@Param("now") LocalDateTime now);

    @Override
    @Query(
            value = "SELECT min(reconcile_unknown_since) FROM p_payments WHERE status = 'FAILED'"
                    + " AND next_reconcile_at IS NOT NULL AND deleted_at IS NULL",
            nativeQuery = true)
    LocalDateTime oldestUnknownReconcile();

    @Override
    @Query(
            value = "SELECT p.* FROM p_payments p JOIN p_refunds r ON r.payment_id = p.id"
                    + " WHERE r.status IN ('REQUESTED','FAILED') AND r.recovery_mode <> 'PAUSED' AND r.recovery_blocked = FALSE AND r.next_attempt_at <= :now"
                    + " AND p.deleted_at IS NULL AND r.deleted_at IS NULL AND p.id NOT IN (:excludedIds)"
                    + " ORDER BY r.next_attempt_at, p.id LIMIT 1 FOR UPDATE OF p SKIP LOCKED",
            nativeQuery = true)
    List<Payment> lockNextRefund(@Param("now") LocalDateTime now, @Param("excludedIds") Collection<Long> excludedIds);

    @Override
    @Query(
            value =
                    "SELECT min(next_attempt_at) FROM p_refunds WHERE status IN ('REQUESTED','FAILED') AND recovery_mode <> 'PAUSED'"
                            + " AND recovery_blocked = FALSE AND next_attempt_at <= :now AND deleted_at IS NULL",
            nativeQuery = true)
    LocalDateTime oldestDueRefund(@Param("now") LocalDateTime now);

    @Override
    @Query(
            value = "SELECT p.* FROM p_payments p JOIN p_refunds r ON r.payment_id = p.id"
                    + " WHERE r.status IN ('REQUESTED','FAILED') AND r.recovery_mode IN (:modes) AND r.recovery_blocked = FALSE"
                    + " AND r.next_attempt_at <= :now AND p.deleted_at IS NULL AND r.deleted_at IS NULL AND p.id NOT IN (:excludedIds)"
                    + " ORDER BY r.next_attempt_at, p.id LIMIT 1 FOR UPDATE OF p SKIP LOCKED",
            nativeQuery = true)
    List<Payment> lockNextRefundInModes(
            @Param("now") LocalDateTime now,
            @Param("excludedIds") Collection<Long> excludedIds,
            @Param("modes") Collection<String> modes);
}
