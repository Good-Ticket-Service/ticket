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
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 준비 시도 한 번 = 결제 한 건. 상태 전이는 이 클래스의 메서드로만 일어나고, 허용하지 않은 전이는
 * {@link CommonErrorCode#INVALID_STATUS_TRANSITION}으로 막는다. 전이표는 docs/payment/plan.md 3절이다.
 */
@Getter
@Entity
@Table(name = "p_payments")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false)
    private Long reservationId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID sellerId;

    @Column(nullable = false)
    private Long performanceId;

    @Column(nullable = false)
    private Long scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgProvider pgProvider;

    /** 카카오는 준비 때 받은 tid, 토스는 승인 직전에 받은 paymentKey. */
    @Column(length = 200)
    private String pgTransactionId;

    @Column(length = 500)
    private String checkoutUrl;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int totalPrice;

    @Column(nullable = false)
    private int discountAmount;

    @Column(nullable = false)
    private int amount;

    private Long userCouponId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 80)
    private String failureCode;

    private String failureMessage;

    private LocalDateTime confirmRequestedAt;

    /** PG 승인을 부른 시각. "승인 기록 없음" 유예와 최소 대사 대상 판단의 기준이다. */
    private LocalDateTime approveRequestedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime reconciledAt;

    @Embedded
    private Recovery recovery = Recovery.none();

    private Payment(PaymentOrder order, int discountAmount) {
        this.orderId = order.orderId();
        this.reservationId = order.reservationId();
        this.userId = order.userId();
        this.sellerId = order.sellerId();
        this.performanceId = order.performanceId();
        this.scheduleId = order.scheduleId();
        this.pgProvider = order.pgProvider();
        this.totalPrice = order.totalPrice();
        this.userCouponId = order.userCouponId();
        this.expiresAt = order.expiresAt();
        this.discountAmount = discountAmount;
        this.amount = order.totalPrice() - discountAmount;
        this.status = PaymentStatus.READY;
    }

    /** 준비. 할인액은 쿠폰을 잡은 결과이고, 할인 후 금액은 0보다 커야 한다. */
    public static Payment ready(PaymentOrder order, int discountAmount) {
        if (discountAmount < 0 || order.totalPrice() - discountAmount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
        }
        return new Payment(order, discountAmount);
    }

    /** 같은 orderId로 다시 온 준비 요청이 처음과 같은 내용인지 본다. 다르면 호출한 쪽이 409로 응답한다. */
    public boolean matches(PaymentOrder order) {
        return orderId.equals(order.orderId())
                && reservationId.equals(order.reservationId())
                && userId.equals(order.userId())
                && sellerId.equals(order.sellerId())
                && performanceId.equals(order.performanceId())
                && scheduleId.equals(order.scheduleId())
                && pgProvider == order.pgProvider()
                && totalPrice == order.totalPrice()
                && Objects.equals(userCouponId, order.userCouponId())
                && expiresAt.equals(order.expiresAt());
    }

    /** 카카오 준비 결과. 같은 orderId의 준비 재요청에 결제창 URL을 그대로 돌려주기 위해 저장한다. */
    public void recordCheckout(String transactionId, String checkoutUrl) {
        require(PaymentStatus.READY);
        this.pgTransactionId = transactionId;
        this.checkoutUrl = checkoutUrl;
    }

    /** 승인 요청 접수. 처음 받은 시각만 남긴다. */
    public void requestConfirm(LocalDateTime now) {
        require(PaymentStatus.READY);
        if (confirmRequestedAt == null) {
            this.confirmRequestedAt = now;
        }
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * PG 승인을 부르기 직전에 커밋하는 전이. 이 상태가 남아 있으면 서버가 죽어도 복구 잡이 찾는다.
     *
     * @param authTransactionId 토스 paymentKey. 카카오는 준비 때 저장한 tid를 쓰므로 null
     */
    public void startApproval(String authTransactionId, LocalDateTime now, Duration firstCheckAfter) {
        require(PaymentStatus.READY);
        if (authTransactionId != null) {
            this.pgTransactionId = authTransactionId;
        }
        this.status = PaymentStatus.IN_PROGRESS;
        this.approveRequestedAt = now;
        this.recovery.start(now, now.plus(firstCheckAfter));
    }

    public void approve(String transactionId, LocalDateTime approvedAt) {
        require(PaymentStatus.IN_PROGRESS);
        if (transactionId != null) {
            this.pgTransactionId = transactionId;
        }
        this.status = PaymentStatus.DONE;
        this.approvedAt = approvedAt;
        this.recovery.finish();
    }

    /** READY·IN_PROGRESS에서만 실패로 확정한다. 쿠폰 반납은 같은 트랜잭션에서 호출한 쪽이 한다. */
    public void fail(PaymentFailureCode code, String message) {
        fail(code.name(), message);
    }

    /** PG가 준 거절 코드로 실패를 확정한다. */
    public void fail(String code, String message) {
        if (status != PaymentStatus.READY && status != PaymentStatus.IN_PROGRESS) {
            throw invalidTransition();
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        this.recovery.finish();
    }

    /** 환불이 끝나 결제를 취소 상태로 닫는다. FAILED 결제의 자동 취소 환불은 결제를 바꾸지 않는다. */
    public void cancel() {
        require(PaymentStatus.DONE);
        this.status = PaymentStatus.CANCELED;
    }

    public void claimRecovery(LocalDateTime until) {
        require(PaymentStatus.IN_PROGRESS);
        recovery.claimUntil(until);
    }

    public void deferRecovery(String error, LocalDateTime nextAttemptAt) {
        require(PaymentStatus.IN_PROGRESS);
        recovery.defer(error, nextAttemptAt);
    }

    public void blockRecovery(String reason) {
        require(PaymentStatus.IN_PROGRESS);
        recovery.block(reason);
    }

    public void unblockRecovery(LocalDateTime now) {
        require(PaymentStatus.IN_PROGRESS);
        if (!recovery.isBlocked()) {
            throw invalidTransition();
        }
        recovery.unblock(now);
    }

    public boolean isRecoveryGivenUp(LocalDateTime now, Duration giveUpAfter) {
        return status == PaymentStatus.IN_PROGRESS && recovery.isGivenUp(now, giveUpAfter);
    }

    /** "승인 기록 없음" 응답을 거절로 확정해도 되는지. 승인 호출 후 유예 시간이 지나야 한다. */
    public boolean isNotFoundGraceOver(LocalDateTime now, Duration grace) {
        return approveRequestedAt != null && !now.isBefore(approveRequestedAt.plus(grace));
    }

    /** 최소 대사 대상: PG 승인을 실제로 불렀는데 FAILED로 끝났고 아직 확인하지 않은 결제. */
    public boolean needsReconciliation() {
        return status == PaymentStatus.FAILED && approveRequestedAt != null && reconciledAt == null;
    }

    public void markReconciled(LocalDateTime now) {
        if (!needsReconciliation()) {
            throw invalidTransition();
        }
        this.reconciledAt = now;
    }

    private void require(PaymentStatus expected) {
        if (status != expected) {
            throw invalidTransition();
        }
    }

    private BusinessException invalidTransition() {
        return new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
    }
}
