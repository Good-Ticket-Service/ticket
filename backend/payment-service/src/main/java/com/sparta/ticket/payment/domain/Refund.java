package com.sparta.ticket.payment.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
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
 * 전이표는 docs/payment/plan.md 3절이다.
 */
@Getter
@Entity
@Table(name = "p_refunds")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseEntity {

    private static final String CANCEL_KEY_SEPARATOR = ":cancel:";

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

    @Column(length = 200)
    private String pgCancelId;

    @Column(length = 80)
    private String failureCode;

    private String failureMessage;

    private LocalDateTime requestedAt;

    private LocalDateTime refundedAt;

    @Embedded
    private Recovery recovery = Recovery.none();

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
        this.recovery.finish();
    }

    /** PG의 업무 거절. 자동으로 다시 보내지 않도록 차단하고 경보 대상으로 남긴다. */
    public void reject(String code, String message) {
        require(RefundStatus.REQUESTED);
        this.status = RefundStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        this.recovery.block(code);
    }

    /** 관리자 재시도. 토스는 같은 멱등 키의 거절을 그대로 재현하므로 키 세대를 올려 새 요청으로 보낸다. */
    public void retryByAdmin(LocalDateTime now) {
        require(RefundStatus.FAILED);
        this.cancelAttempt++;
        this.status = RefundStatus.REQUESTED;
        this.failureCode = null;
        this.failureMessage = null;
        this.recovery.start(now, now);
    }

    public String pgCancelKey(String orderId) {
        return orderId + CANCEL_KEY_SEPARATOR + cancelAttempt;
    }

    public void claimRecovery(LocalDateTime until) {
        require(RefundStatus.REQUESTED);
        recovery.claimUntil(until);
    }

    public void deferRecovery(String error, LocalDateTime nextAttemptAt) {
        require(RefundStatus.REQUESTED);
        recovery.defer(error, nextAttemptAt);
    }

    public void blockRecovery(String reason) {
        require(RefundStatus.REQUESTED);
        recovery.block(reason);
    }

    public void unblockRecovery(LocalDateTime now) {
        require(RefundStatus.REQUESTED);
        if (!recovery.isBlocked()) {
            throw invalidTransition();
        }
        recovery.unblock(now);
    }

    public boolean isRecoveryGivenUp(LocalDateTime now, Duration giveUpAfter) {
        return status == RefundStatus.REQUESTED && recovery.isGivenUp(now, giveUpAfter);
    }

    private void markRequested(LocalDateTime now, LocalDateTime nextAttemptAt) {
        if (requestedAt == null) {
            this.requestedAt = now;
        }
        this.recovery.start(now, nextAttemptAt);
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
