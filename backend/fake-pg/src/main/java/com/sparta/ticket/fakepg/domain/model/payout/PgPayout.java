package com.sparta.ticket.fakepg.domain.model.payout;

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
 * PG 지급대행 한 건. 판매자 정산 지급을 PG가 대신 송금하는 것을 흉내 낸다(settlement-design 7.1절).
 * 지급은 접수 즉시 끝나지 않는다. 접수 때 결과와 처리 시각을 정해 두고, 처리 시각이 지난 뒤 조회하면 그 결과로 확정한다.
 * 결과를 접수 때 정하므로 같은 키를 몇 번 조회해도 다시 추첨하지 않는다.
 */
@Getter
@Entity
@Table(name = "pg_payout")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgPayout {

    /** 이 접두어로 시작하는 계좌 참조는 계좌·예금주 불일치로 실패한다. 시험에서 계좌 오류를 재현하는 규칙이다. */
    public static final String INVALID_ACCOUNT_PREFIX = "INVALID-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    /** 지급대행 거래 ID. 판매자 입금 문의를 추적하는 번호다. */
    @Column(nullable = false, length = 100)
    private String payoutId;

    @Column(nullable = false, length = 100)
    private String sellerRef;

    @Column(nullable = false, length = 100)
    private String accountRef;

    @Column(nullable = false, length = 100)
    private String holderMasked;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    /** 접수 때 정한 최종 결과. 처리 시각 전에는 밖으로 보이지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus outcome;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PayoutFailure failureCode;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Column(nullable = false)
    private LocalDateTime processAt;

    private LocalDateTime decidedAt;

    /**
     * 지급을 접수한다. 계좌 참조가 시험용 오류 접두어면 계좌 오류로, rejected면 지급대행 거절로 끝날 지급이다.
     *
     * @param rejected 장애 주입의 명시 거절. 접수는 되고 처리 결과가 거절이다
     */
    public static PgPayout accept(
            String idempotencyKey,
            String requestHash,
            String sellerRef,
            String accountRef,
            String holderMasked,
            long amount,
            boolean rejected,
            LocalDateTime now,
            long processingDelayMs) {
        if (amount <= 0) throw new IllegalArgumentException("지급 금액은 0보다 커야 한다");
        PgPayout payout = new PgPayout();
        payout.idempotencyKey = idempotencyKey;
        payout.requestHash = requestHash;
        payout.payoutId = "payout_" + UUID.randomUUID().toString().replace("-", "");
        payout.sellerRef = sellerRef;
        payout.accountRef = accountRef;
        payout.holderMasked = holderMasked;
        payout.amount = amount;
        payout.status = PayoutStatus.ACCEPTED;
        if (accountRef.startsWith(INVALID_ACCOUNT_PREFIX)) payout.fail(PayoutFailure.ACCOUNT_INVALID);
        else if (rejected) payout.fail(PayoutFailure.REJECTED);
        else payout.outcome = PayoutStatus.COMPLETED;
        payout.requestedAt = now;
        payout.processAt = now.plusNanos(processingDelayMs * 1_000_000);
        return payout;
    }

    private void fail(PayoutFailure failure) {
        this.outcome = PayoutStatus.FAILED;
        this.failureCode = failure;
    }

    /** 조회 시점의 상태. 처리 시각 전이면 처리 중, 지나면 접수 때 정한 결과로 확정한다. */
    public void observe(LocalDateTime now) {
        if (status == PayoutStatus.COMPLETED || status == PayoutStatus.FAILED) return;
        if (now.isBefore(processAt)) {
            status = PayoutStatus.PROCESSING;
            return;
        }
        status = outcome;
        decidedAt = processAt;
    }

    /** 확정 전에는 실패 코드를 밖으로 보이지 않는다. */
    public PayoutFailure visibleFailureCode() {
        return status == PayoutStatus.FAILED ? failureCode : null;
    }
}
