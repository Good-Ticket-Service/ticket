package com.sparta.ticket.fakepg.domain.model.transaction;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PG 쪽 거래 한 건. 결제 인증 시뮬레이션에서 READY로 생기고, 승인하면 DONE, 취소하면 CANCELED가 된다.
 * 주문번호 하나에 거래 하나다.
 */
@Getter
@Entity
@Table(name = "pg_transaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgProvider provider;

    /** 토스 paymentKey. 사용자 인증이 끝나면 발급된다. */
    @Column(nullable = false, length = 200)
    private String paymentKey;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false, length = 100)
    private String orderName;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PgTransactionStatus status;

    @Column(nullable = false)
    private LocalDateTime authExpiresAt;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime canceledAt;

    @Column(length = 200)
    private String cancelReason;

    @Column(length = 200)
    private String cancelTransactionKey;

    @Column(length = 80)
    private String failureCode;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 결제 인증 시뮬레이션. 사용자가 결제창에서 인증을 마친 상태를 만든다. */
    public static PgTransaction authorize(
            PgProvider provider, String orderId, String orderName, int amount, LocalDateTime now, int validMinutes) {
        if (amount <= 0) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
        PgTransaction transaction = new PgTransaction();
        transaction.provider = provider;
        transaction.paymentKey = newKey("tgen_");
        transaction.orderId = orderId;
        transaction.orderName = orderName;
        transaction.amount = amount;
        transaction.status = PgTransactionStatus.READY;
        transaction.requestedAt = now;
        transaction.authExpiresAt = now.plusMinutes(validMinutes);
        transaction.updatedAt = now;
        return transaction;
    }

    /** 같은 주문으로 결제창을 다시 열었다. 인증이 만료된 READY만 새 paymentKey로 다시 인증한다. */
    public void reauthorize(int amount, LocalDateTime now, int validMinutes) {
        if (status != PgTransactionStatus.READY && status != PgTransactionStatus.EXPIRED) {
            throw new PgException(PgErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
        if (this.amount != amount) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "같은 주문번호로 다른 금액을 인증할 수 없습니다.");
        }
        if (status == PgTransactionStatus.READY && !isAuthExpired(now)) {
            return;
        }
        this.paymentKey = newKey("tgen_");
        this.status = PgTransactionStatus.READY;
        this.authExpiresAt = now.plusMinutes(validMinutes);
        this.updatedAt = now;
    }

    /** 승인 요청이 이 거래와 맞는지 확인한다. 상태를 바꾸지 않는다. 인증 만료는 호출한 쪽이 따로 본다. */
    public void verifyConfirmable(String orderId, int amount) {
        if (!this.orderId.equals(orderId)) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "paymentKey와 주문번호가 맞지 않습니다.");
        }
        if (this.amount != amount) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "인증한 금액과 승인 요청 금액이 다릅니다.");
        }
        if (status == PgTransactionStatus.EXPIRED) {
            throw new PgException(PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
        }
        if (status != PgTransactionStatus.READY) {
            throw new PgException(PgErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
    }

    public boolean isAuthExpired(LocalDateTime now) {
        return !now.isBefore(authExpiresAt);
    }

    public void expire(LocalDateTime now) {
        if (status == PgTransactionStatus.READY) {
            this.status = PgTransactionStatus.EXPIRED;
            this.updatedAt = now;
        }
    }

    public void approve(LocalDateTime now) {
        if (status != PgTransactionStatus.READY) {
            throw new PgException(PgErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
        this.status = PgTransactionStatus.DONE;
        this.approvedAt = now;
        this.updatedAt = now;
    }

    /** 지연 승인용. 이미 끝난 거래는 건드리지 않는다. */
    public boolean approveIfReady(LocalDateTime now) {
        if (status != PgTransactionStatus.READY) {
            return false;
        }
        approve(now);
        return true;
    }

    public void abort(String code, LocalDateTime now) {
        this.status = PgTransactionStatus.ABORTED;
        this.failureCode = code;
        this.updatedAt = now;
    }

    /** 전액 취소만 지원한다(1차 범위). */
    public void verifyCancelable(Integer cancelAmount) {
        if (status == PgTransactionStatus.CANCELED) {
            throw new PgException(PgErrorCode.ALREADY_CANCELED_PAYMENT);
        }
        if (status != PgTransactionStatus.DONE) {
            throw new PgException(PgErrorCode.NOT_CANCELABLE_PAYMENT);
        }
        if (cancelAmount != null && cancelAmount != amount) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "부분 취소는 지원하지 않습니다.");
        }
    }

    public void cancel(String reason, LocalDateTime now) {
        this.status = PgTransactionStatus.CANCELED;
        this.cancelReason = reason;
        this.cancelTransactionKey = newKey("tcancel_");
        this.canceledAt = now;
        this.updatedAt = now;
    }

    private static String newKey(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
