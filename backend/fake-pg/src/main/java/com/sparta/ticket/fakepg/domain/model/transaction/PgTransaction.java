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
 * PG 쪽 거래 한 건. 승인하면 DONE, 취소하면 CANCELED가 된다. 주문번호 하나에 거래 하나다.
 *
 * <ul>
 *   <li>토스: 결제 인증 시뮬레이션에서 paymentKey를 발급하며 READY로 생긴다.</li>
 *   <li>카카오: 준비 요청에서 tid를 발급하며 READY로 생기고, 사용자 승인 시뮬레이션이 pg_token을 발급해야 승인할 수 있다.</li>
 * </ul>
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

    /** PG 거래 키. 토스 paymentKey(인증 때 발급) 또는 카카오 tid(준비 때 발급). */
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

    /** 취소된 금액. 전액 취소면 amount와 같고, 외부 부분 취소면 그보다 작다. 이 컬럼 전의 취소 기록은 NULL이다. */
    private Integer canceledAmount;

    @Column(length = 80)
    private String failureCode;

    /** 카카오 가맹점 회원 ID(partner_user_id). 준비와 승인에 같은 값이 와야 한다. 토스는 NULL. */
    @Column(length = 100)
    private String partnerUserId;

    /** 카카오 사용자 승인 결과(pg_token). 사용자 승인 전과 토스는 NULL. */
    @Column(length = 100)
    private String pgToken;

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

    /** 카카오 결제 준비. 결제창을 띄우기 전에 tid를 발급한다. 사용자 승인 전이라 pg_token은 없다. */
    public static PgTransaction prepareKakao(
            String orderId, String itemName, int amount, String partnerUserId, LocalDateTime now, int validMinutes) {
        PgTransaction transaction = authorize(PgProvider.KAKAO, orderId, itemName, amount, now, validMinutes);
        transaction.paymentKey = newKey("T");
        transaction.partnerUserId = partnerUserId;
        return transaction;
    }

    /**
     * 같은 주문으로 카카오 준비를 다시 불렀다. 실제 카카오처럼 부를 때마다 새 tid를 발급하고, 이전 사용자 승인은 버린다.
     * 승인 전(READY·EXPIRED)인 거래만 다시 준비할 수 있다.
     */
    public void reprepareKakao(int amount, String partnerUserId, LocalDateTime now, int validMinutes) {
        if (status != PgTransactionStatus.READY && status != PgTransactionStatus.EXPIRED) {
            throw new PgException(PgErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
        if (this.amount != amount || !this.partnerUserId.equals(partnerUserId)) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "같은 주문번호로 다른 금액이나 회원의 결제를 준비할 수 없습니다.");
        }
        this.paymentKey = newKey("T");
        this.pgToken = null;
        this.status = PgTransactionStatus.READY;
        this.authExpiresAt = now.plusMinutes(validMinutes);
        this.updatedAt = now;
    }

    /** 카카오 사용자 승인 시뮬레이션. 결제창에서 비밀번호 인증을 마친 것처럼 pg_token을 발급한다. 이미 발급했으면 같은 값이다. */
    public void issuePgToken(LocalDateTime now) {
        if (status != PgTransactionStatus.READY) {
            throw new PgException(PgErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
        if (pgToken == null) {
            this.pgToken = newKey("");
            this.updatedAt = now;
        }
    }

    /** 카카오 승인 요청이 이 거래와 맞는지 확인한다. 상태를 바꾸지 않는다. 금액은 승인 요청에 없고 응답으로 돌려준다. */
    public void verifyKakaoApprovable(String orderId, String partnerUserId, String pgToken) {
        if (!this.orderId.equals(orderId) || !this.partnerUserId.equals(partnerUserId)) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "tid와 주문번호·회원 ID가 맞지 않습니다.");
        }
        if (status == PgTransactionStatus.EXPIRED) {
            throw new PgException(PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
        }
        if (status != PgTransactionStatus.READY) {
            throw new PgException(PgErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
        if (this.pgToken == null) {
            throw new PgException(PgErrorCode.NOT_AUTHENTICATED_PAYMENT);
        }
        if (!this.pgToken.equals(pgToken)) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "pg_token이 맞지 않습니다.");
        }
    }

    public boolean isProvidedBy(PgProvider provider) {
        return this.provider == provider;
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
        this.canceledAmount = amount;
        this.cancelReason = reason;
        this.cancelTransactionKey = newKey("tcancel_");
        this.canceledAt = now;
        this.updatedAt = now;
    }

    /**
     * 우리 API를 거치지 않은 취소. 가맹점 관리자 화면이나 PG 고객센터에서 취소된 상황을 만든다. 금액이 승인액보다 작으면
     * 부분 취소(PARTIAL_CANCELED)다. 승인 완료(DONE) 거래만 바꿀 수 있다.
     */
    public void cancelExternally(Integer cancelAmount, String reason, LocalDateTime now) {
        if (status != PgTransactionStatus.DONE) {
            throw new PgException(PgErrorCode.NOT_CANCELABLE_PAYMENT);
        }
        int canceled = cancelAmount == null ? amount : cancelAmount;
        if (canceled <= 0 || canceled > amount) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "취소 금액은 0보다 크고 승인 금액 이하여야 합니다.");
        }
        cancel(reason, now);
        if (canceled < amount) {
            this.status = PgTransactionStatus.PARTIAL_CANCELED;
            this.canceledAmount = canceled;
        }
    }

    /** 취소된 금액. 취소 전이면 0이다. 컬럼을 추가하기 전에 전액 취소된 기록은 승인액으로 본다. */
    public int canceledAmountOrZero() {
        if (canceledAmount != null) {
            return canceledAmount;
        }
        return status == PgTransactionStatus.CANCELED ? amount : 0;
    }

    private static String newKey(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
