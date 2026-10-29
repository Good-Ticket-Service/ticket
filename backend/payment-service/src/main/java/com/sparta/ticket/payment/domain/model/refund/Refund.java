package com.sparta.ticket.payment.domain.model.refund;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.recovery.Recovery;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 결제 하나에 환불 행 하나(1차는 전액 환불). 사용자 환불과 자동 취소(AUTO_VOID)가 겹쳐도 같은 행을 쓴다.
 * 전이표는 docs/architecture.md의 결제 상태 전이이다.
 */
@Getter
@Entity
@Table(name = "p_refunds")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseEntity {

    private static final String CANCEL_KEY_SEPARATOR = ":cancel:";

    /** 실패 결제의 자동 취소가 거절돼 금액을 다시 확인하는 중이다. */
    public static final String VOID_CANCEL_REJECTED = "VOID_CANCEL_REJECTED";

    private static final String VOID_AMOUNT_CORRECTED = "VOID_AMOUNT_CORRECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long reservationId;

    /** 사용자 환불은 결제 금액, AUTO_VOID는 PG가 승인한 금액. */
    @Column(nullable = false)
    private int refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    /** PG 취소 멱등 키의 세대. 관리자가 업무 거절을 다시 시도할 때만 올린다. */
    @Column(nullable = false)
    private int cancelAttempt;

    @Column(nullable = false)
    private long executionVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundRecoveryMode recoveryMode = RefundRecoveryMode.ACTIVE;

    private LocalDateTime slowStartedAt;
    private LocalDateTime cancelKeyCreatedAt;

    @Column(nullable = false)
    private int slowAttempts;

    @Column(length = 80)
    private String verificationReason;

    private LocalDateTime restrictionStartedAt;

    @Column(length = 200)
    private String pgCancelId;

    @Column(length = 80)
    private String failureCode;

    private String failureMessage;

    private LocalDateTime requestedAt;

    private LocalDateTime refundedAt;

    /** 운영 중단 여부. 시간 초과는 SLOW로 전환하며 조회를 멈추지 않는다. */
    @Column(nullable = false)
    private boolean recoveryBlocked;

    /** 바깥에 그대로 내주지 않는다. 바꾸는 일은 상태를 함께 검사하는 이 클래스의 메서드로만 한다. */
    @Getter(AccessLevel.NONE)
    @Embedded
    private Recovery recovery = Recovery.none();

    public LocalDateTime getNextAttemptAt() {
        return recovery.getNextAttemptAt();
    }

    public LocalDateTime getRecoveryStartedAt() {
        return recovery.getStartedAt();
    }

    public int getRecoveryAttempts() {
        return recovery.getAttempts();
    }

    public String getLastError() {
        return recovery.getLastError();
    }

    private Refund(Payment payment, RefundReason reason, int refundAmount, RefundStatus status) {
        if (refundAmount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
        }
        this.paymentId = payment.getId();
        this.reservationId = payment.getReservationId();
        this.reason = reason;
        this.refundAmount = refundAmount;
        this.status = status;
    }

    /** 결제가 아직 IN_PROGRESS일 때 받은 환불 요청. */
    public static Refund waiting(Payment payment, RefundReason reason) {
        return new Refund(payment, reason, payment.getAmount(), RefundStatus.WAITING);
    }

    /**
     * PG에 돈이 잡혀 있다고 확인된 환불.
     *
     * @param nextAttemptAt 요청 경로가 바로 취소를 부르면 점유 기간 뒤, 복구 잡에 맡기면 지금
     */
    public static Refund requested(
            Payment payment, RefundReason reason, int refundAmount, LocalDateTime now, LocalDateTime nextAttemptAt) {
        Refund refund = new Refund(payment, reason, refundAmount, RefundStatus.REQUESTED);
        refund.markRequested(now, nextAttemptAt);
        return refund;
    }

    /**
     * ensureRefund 규칙: WAITING·SKIPPED면 REQUESTED로 올리고, 이미 REQUESTED·DONE·FAILED면 그대로 둔다.
     * 사유는 처음 것을 유지한다.
     *
     * @return 이번 호출로 REQUESTED가 됐으면 true
     */
    public boolean ensureRequested(int refundAmount, LocalDateTime now, LocalDateTime nextAttemptAt) {
        if (status != RefundStatus.WAITING && status != RefundStatus.SKIPPED) {
            return false;
        }
        if (refundAmount <= 0) throw new BusinessException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
        this.refundAmount = refundAmount;
        this.status = RefundStatus.REQUESTED;
        markRequested(now, nextAttemptAt);
        return true;
    }

    /** 기다리던 결제가 FAILED로 확정돼 환불할 돈이 없다. */
    public void skip() {
        require(RefundStatus.WAITING);
        this.status = RefundStatus.SKIPPED;
    }

    public void complete(String pgCancelId, LocalDateTime refundedAt) {
        require(RefundStatus.REQUESTED);
        this.status = RefundStatus.DONE;
        this.pgCancelId = pgCancelId;
        this.refundedAt = refundedAt;
        this.recoveryBlocked = false;
        this.recoveryMode = RefundRecoveryMode.PAUSED;
        this.recovery.finish();
    }

    /** 오래된 성공의 재조회가 실패해도 운영 인수에 필요한 단서를 남긴다. */
    public void requireCancellationVerification() {
        if (status != RefundStatus.DONE) recovery.recordError("STALE_CANCEL_SUCCESS_REQUIRES_VERIFICATION");
        // Note: 거절 뒤 성공 근거가 오면 단순 확정 거절로 새 키를 허용할 수 없다. 최초 사유는 별도로 보존한다.
        if (status == RefundStatus.FAILED) failureCode = "PG_CANCEL_RESULT_CONFLICT";
    }

    public void confirmCancellation(String pgCancelId, LocalDateTime refundedAt) {
        if (status != RefundStatus.REQUESTED && status != RefundStatus.FAILED) throw invalidTransition();
        this.status = RefundStatus.REQUESTED;
        complete(pgCancelId, refundedAt);
        this.failureCode = null;
        this.failureMessage = null;
    }

    /** PG의 업무 거절은 자동 처리를 차단하고 운영 확인 대상으로 남긴다. */
    public void reject(String code, String message) {
        require(RefundStatus.REQUESTED);
        this.status = RefundStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        block(code);
    }

    public void reject(String code, String message, LocalDateTime now) {
        reject(code, message);
        if (restrictionStartedAt == null) restrictionStartedAt = now;
    }

    /**
     * 실패 결제의 자동 취소(AUTO_VOID)가 PG 확인 금액으로 보낸 취소를 거절당했다. 금액을 만든 응답이 틀렸을 수 있으므로
     * 운영 중단(PAUSED) 대신 조회 전용으로 두고, 다음 조회에서 금액이 달라지면 correctVoidAmount로 다시 시작한다.
     */
    public void rejectForVoidRecheck(String code, LocalDateTime now) {
        require(RefundStatus.REQUESTED);
        this.status = RefundStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = "PG_CANCEL_REJECTED";
        if (restrictionStartedAt == null) restrictionStartedAt = now;
        this.recoveryBlocked = false;
        this.recoveryMode = RefundRecoveryMode.VERIFY_ONLY;
        this.verificationReason = VOID_CANCEL_REJECTED;
        if (slowStartedAt == null) slowStartedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        recovery.recordError(code);
    }

    /** 실패 결제의 자동 취소이고, 금액이 PG 확인 전이거나 그 금액의 취소가 거절된 뒤라 다른 금액으로 다시 취소할 수 있는지. */
    public boolean isVoidAmountCorrectable() {
        boolean beforeCancel = status == RefundStatus.REQUESTED
                && !recoveryBlocked
                && (recoveryMode == RefundRecoveryMode.ACTIVE || recoveryMode == RefundRecoveryMode.SLOW);
        boolean afterRejection = status == RefundStatus.FAILED
                && !recoveryBlocked
                && recoveryMode == RefundRecoveryMode.VERIFY_ONLY
                && VOID_CANCEL_REJECTED.equals(verificationReason);
        return beforeCancel || afterRejection;
    }

    /**
     * 실패 결제의 자동 취소 금액을 PG 조회로 확인한 승인액으로 바로잡고 새 취소 키 세대로 다시 시작한다.
     * Note: 이전 금액은 PG 승인액과 달라 PG가 받아들일 수 없는 취소다. 새 키로 보내도 이중 취소가 되지 않는다.
     * 키 생성 시각을 새로 잡으므로 호출자가 보정 횟수를 제한한다.
     */
    public void correctVoidAmount(int amount, LocalDateTime now) {
        if (amount <= 0) throw new BusinessException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
        if (!isVoidAmountCorrectable()) throw invalidTransition();
        LocalDateTime at = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.refundAmount = amount;
        this.status = RefundStatus.REQUESTED;
        this.cancelAttempt = Math.incrementExact(cancelAttempt);
        this.executionVersion = Math.incrementExact(executionVersion);
        this.failureCode = null;
        this.failureMessage = null;
        this.recoveryBlocked = false;
        this.recoveryMode = RefundRecoveryMode.ACTIVE;
        this.slowStartedAt = null;
        this.slowAttempts = 0;
        this.verificationReason = null;
        this.restrictionStartedAt = null;
        this.cancelKeyCreatedAt = at;
        recovery.defer(VOID_AMOUNT_CORRECTED, at);
    }

    /** 관리자 재시도. 토스는 같은 멱등 키의 거절을 그대로 재현하므로 키 세대를 올려 새 요청으로 보낸다. */
    public void retryByAdmin(LocalDateTime now) {
        require(RefundStatus.FAILED);
        this.cancelAttempt = Math.incrementExact(cancelAttempt);
        this.executionVersion = Math.incrementExact(executionVersion);
        this.status = RefundStatus.REQUESTED;
        this.failureCode = null;
        this.failureMessage = null;
        this.recoveryBlocked = false;
        this.recoveryMode = RefundRecoveryMode.ACTIVE;
        this.slowStartedAt = null;
        this.slowAttempts = 0;
        this.verificationReason = null;
        this.restrictionStartedAt = null;
        this.cancelKeyCreatedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.recovery.start(
                now.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    public String pgCancelKey(String orderId) {
        return orderId + CANCEL_KEY_SEPARATOR + cancelAttempt;
    }

    public void claimRecovery(LocalDateTime until) {
        if (!isAutomaticallyQueryable()) throw invalidTransition();
        this.executionVersion = Math.incrementExact(executionVersion);
        recovery.claimUntil(until);
    }

    public void deferRecovery(String error, LocalDateTime nextAttemptAt) {
        if (!isAutomaticallyQueryable()) throw invalidTransition();
        recovery.defer(error, nextAttemptAt);
        if (recoveryMode != RefundRecoveryMode.ACTIVE && slowAttempts < Integer.MAX_VALUE) slowAttempts++;
    }

    public boolean isAutomaticallyQueryable() {
        return !recoveryBlocked
                && recoveryMode != RefundRecoveryMode.PAUSED
                && (status == RefundStatus.REQUESTED
                        || (status == RefundStatus.FAILED && recoveryMode == RefundRecoveryMode.VERIFY_ONLY));
    }

    public boolean enterSlowIfDue(LocalDateTime now, Duration after) {
        if (recoveryMode == RefundRecoveryMode.ACTIVE && recovery.isGivenUp(now, after)) {
            recoveryMode = RefundRecoveryMode.SLOW;
            slowStartedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            slowAttempts = 0;
            return true;
        }
        return false;
    }

    public void verifyOnly(String reason, LocalDateTime now) {
        if (status != RefundStatus.REQUESTED && status != RefundStatus.FAILED) return;
        if (restrictionStartedAt == null) restrictionStartedAt = now;
        recoveryMode = RefundRecoveryMode.VERIFY_ONLY;
        recoveryBlocked = false;
        if (verificationReason == null) verificationReason = reason.length() > 80 ? reason.substring(0, 80) : reason;
        if (slowStartedAt == null) slowStartedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        recovery.recordError(reason);
        if (recovery.getNextAttemptAt() == null)
            recovery.claimUntil(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    public boolean canCancelAt(LocalDateTime now, Duration maximumAge) {
        return status == RefundStatus.REQUESTED
                && !recoveryBlocked
                && (recoveryMode == RefundRecoveryMode.ACTIVE || recoveryMode == RefundRecoveryMode.SLOW)
                && cancelKeyCreatedAt != null
                && !now.isBefore(cancelKeyCreatedAt)
                && now.isBefore(cancelKeyCreatedAt.plus(maximumAge));
    }

    /** 명시적인 운영 중단. 시간 경과만으로 이 메서드를 호출하지 않는다. */
    public void blockRecovery(String reason) {
        require(RefundStatus.REQUESTED);
        block(reason);
    }

    public void blockRecovery(String reason, LocalDateTime now) {
        blockRecovery(reason);
        if (restrictionStartedAt == null) restrictionStartedAt = now;
    }

    /** 운영 재개는 실패 상태와 기존 취소 키를 보존하고 조회 권한만 되돌린다. */
    public void resumeVerification(LocalDateTime now) {
        if ((status != RefundStatus.REQUESTED && status != RefundStatus.FAILED)
                || recoveryMode != RefundRecoveryMode.PAUSED
                || !recoveryBlocked) throw invalidTransition();
        verifyOnly("OPERATOR_RESUMED_VERIFICATION", now);
        executionVersion = Math.incrementExact(executionVersion);
        recovery.claimUntil(now);
    }

    /** 안전성이 재확인된 기존 키만 재개한다. 키 생성 시각과 세대를 초기화하지 않는다. */
    public void retrySameKey(LocalDateTime now) {
        require(RefundStatus.REQUESTED);
        if (recoveryMode != RefundRecoveryMode.VERIFY_ONLY) throw invalidTransition();
        executionVersion = Math.incrementExact(executionVersion);
        recoveryMode = RefundRecoveryMode.ACTIVE;
        recoveryBlocked = false;
        verificationReason = null;
        restrictionStartedAt = null;
        recovery.claimUntil(now);
    }

    private void markRequested(LocalDateTime now, LocalDateTime nextAttemptAt) {
        if (requestedAt == null) {
            this.requestedAt = now;
        }
        this.recoveryBlocked = false;
        this.recoveryMode = RefundRecoveryMode.ACTIVE;
        this.cancelKeyCreatedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.recovery.start(
                now.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                nextAttemptAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    private void block(String reason) {
        this.recoveryBlocked = true;
        this.recoveryMode = RefundRecoveryMode.PAUSED;
        recovery.recordError(reason);
        if (verificationReason == null) verificationReason = reason;
    }

    private void require(RefundStatus expected) {
        if (status != expected) {
            throw invalidTransition();
        }
    }

    private BusinessException invalidTransition() {
        return new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }
}
