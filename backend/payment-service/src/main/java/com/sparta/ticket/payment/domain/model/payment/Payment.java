package com.sparta.ticket.payment.domain.model.payment;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
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
import java.time.temporal.ChronoUnit;
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
    private Long userId;

    @Column(nullable = false)
    private Long sellerId;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 80)
    private String failureCode;

    private String failureMessage;

    private LocalDateTime confirmRequestedAt;

    /** PG 승인을 부른 시각. 결과 확정 시한, "승인 기록 없음" 유예, 최소 대사 대상 판단의 기준이다. */
    private LocalDateTime approveRequestedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime reconciledAt;

    /** 바깥에 그대로 내주지 않는다. 바꾸는 일은 상태를 함께 검사하는 이 클래스의 메서드로만 한다. */
    @Getter(AccessLevel.NONE)
    @Embedded
    private Recovery recovery = Recovery.none();

    private Payment(PaymentOrder order) {
        this.orderId = order.orderId();
        this.reservationId = order.reservationId();
        this.userId = order.userId();
        this.sellerId = order.sellerId();
        this.performanceId = order.performanceId();
        this.scheduleId = order.scheduleId();
        this.pgProvider = order.pgProvider();
        this.totalPrice = order.totalPrice();
        this.expiresAt = toDbPrecision(order.expiresAt());
        this.discountAmount = order.discountAmount();
        this.amount = order.totalPrice() - order.discountAmount();
        this.status = PaymentStatus.READY;
    }

    /** 준비. 예매가 확정한 할인액을 저장하고, 할인 후 금액이 최소 결제 금액(100원) 이상인지 검증한다. */
    public static Payment ready(PaymentOrder order) {
        if (order.discountAmount() < 0 || (long) order.totalPrice() - order.discountAmount() < 100) {
            throw new BusinessException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
        }
        return new Payment(order);
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
                && discountAmount == order.discountAmount()
                && expiresAt.equals(toDbPrecision(order.expiresAt()));
    }

    /**
     * Note: PostgreSQL TIMESTAMP는 마이크로초까지만 저장한다. 더 정밀한 시각(Windows는 100나노초 단위)을 그대로 두면
     * 저장 후 다시 읽은 값과 요청 값이 달라져, 같은 요청의 재전송을 다른 요청(409)으로 오판한다. 저장과 비교 모두 같은 정밀도로 맞춘다.
     */
    private static LocalDateTime toDbPrecision(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

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

    /** READY·IN_PROGRESS에서만 실패로 확정한다. 쿠폰 상태는 예매가 결제 결과를 확인해 별도 로컬 트랜잭션에서 처리한다. */
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

    /**
     * 결과 확정 시한이 지났는지. 승인 호출 후 이 시간이 지나도 결과를 모르면 FAILED(PG_UNRESOLVED)로 확정해 좌석을 풀게 한다.
     * 그 뒤에 승인이 발견되면 결제는 FAILED 그대로 두고 자동 취소 환불(AUTO_VOID)로 돌려준다.
     */
    public boolean isResultDeadlineOver(LocalDateTime now, Duration deadline) {
        return status == PaymentStatus.IN_PROGRESS
                && approveRequestedAt != null
                && !now.isBefore(approveRequestedAt.plus(deadline));
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
