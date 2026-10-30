package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자 지급(settlement-design 7.2절). 정산서 하나에 하나다. 발행 때 READY로 만들고, 실행권을 얻으면 같은 attempt 동안
 * 멱등 키·금액·계좌 스냅샷을 고정한 채 REQUESTED로 지급대행을 호출한다. 결과는 키·금액·계좌·거래 ID를 확인한 뒤에만 확정한다.
 */
@Getter
@Entity
@Table(name = "p_payouts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long statementId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(nullable = false)
    private int attempt;

    /**
     * READY는 다음 실행 시각, REQUESTED는 다음 조회 시각이다. 달력 범위를 넘어 비어 있으면 지급 실행이 보류한다.
     * Note: 실행권 점유 만료도 이 값으로 표현한다(visibility timeout). 실행권을 얻으면 점유 시간만큼 뒤로 미뤄, 그 사이 다른 실행자가
     * 조회를 시작하지 않는다. 실행자가 끊기면 이 시각이 지나 recovery가 같은 키로 조회한다. 그래서 따로 lease 열을 두지 않는다.
     */
    private LocalDateTime nextAttemptAt;

    /** 실행권을 얻을 때 찍는 지급대행 멱등 키("payout:{id}:{attempt}"). */
    private String idempotencyKey;

    private String bankCode;

    private String accountRef;

    private String holderMasked;

    /** 스냅샷을 찍은 판매자 계좌의 version. */
    private Long accountVersion;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PayoutProviderStatus providerStatus;

    private String externalPayoutId;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PayoutFailure failureCode;

    /** 응답이 스냅샷과 달라 다시 요청하지 않고 조회로만 확정한다. */
    @Column(nullable = false)
    private boolean inquiryOnly;

    private String verificationReason;

    private LocalDateTime requestedAt;

    private LocalDateTime doneAt;

    private String lastError;

    @Version
    @Column(nullable = false)
    private long version;

    public static Payout ready(SettlementStatement statement, LocalDateTime nextAttemptAt) {
        if (statement.getStatus() != StatementStatus.ISSUED)
            throw new IllegalArgumentException("지급할 금액이 있는 정산서만 지급을 만든다");
        Payout payout = new Payout();
        payout.statementId = statement.getId();
        payout.sellerId = statement.getSellerId();
        payout.amount = statement.getPayableAmount();
        payout.status = PayoutStatus.READY;
        payout.nextAttemptAt = nextAttemptAt;
        return payout;
    }

    /** 실행권 획득. attempt를 올리고 이번 attempt의 키·계좌 스냅샷을 고정한다. 결과를 모르는 상태(UNKNOWN)로 시작한다. */
    public void claim(SellerPayoutAccount account, LocalDateTime now, LocalDateTime claimedUntil) {
        requireStatus(PayoutStatus.READY);
        if (!account.isRegistered() || !account.getSellerId().equals(sellerId))
            throw new IllegalArgumentException("이 판매자의 등록된 계좌만 스냅샷한다");
        attempt++;
        status = PayoutStatus.REQUESTED;
        idempotencyKey = "payout:" + id + ":" + attempt;
        bankCode = account.getBankCode();
        accountRef = account.getAccountRef();
        holderMasked = account.getHolderMasked();
        accountVersion = account.getVersion();
        providerStatus = PayoutProviderStatus.UNKNOWN;
        requestedAt = now;
        nextAttemptAt = claimedUntil;
        lastError = null;
    }

    /** 게이트 실패·계좌 미등록. READY에 머물고 다음 실행 시각을 미룬다. */
    public void defer(String reason, LocalDateTime next) {
        requireStatus(PayoutStatus.READY);
        lastError = reason;
        nextAttemptAt = next;
    }

    /** 이번 attempt의 실행권을 가진 호출인지. 늦게 도착한 이전 attempt 결과를 버린다. */
    public boolean ownedBy(int attempt, String idempotencyKey) {
        return status == PayoutStatus.REQUESTED
                && this.attempt == attempt
                && this.idempotencyKey.equals(idempotencyKey);
    }

    /** 지급대행이 접수·처리 중을 알렸다. 완료가 아니므로 호출한 쪽이 scheduleInquiry로 다음 조회 시각을 잡는다. */
    public void observe(PayoutProviderStatus observed, String externalPayoutId) {
        requireStatus(PayoutStatus.REQUESTED);
        if (observed == PayoutProviderStatus.UNKNOWN) throw new IllegalArgumentException("결과 불명은 markUnknown으로 남긴다");
        providerStatus = observed;
        if (externalPayoutId != null) this.externalPayoutId = externalPayoutId;
        lastError = null;
    }

    /**
     * 응답 유실·장애로 이번 결과를 모른다. 같은 키 조회로만 확정한다.
     * Note: 지급대행이 이미 접수를 확인한 지급(ACCEPTED·PROCESSING)은 조회가 실패해도 UNKNOWN으로 낮추지 않는다. 접수 사실은
     * 바뀌지 않으며, 낮추면 짧은 백오프와 같은 키 재전송 판단이 잘못 걸린다.
     */
    public void markUnknown(String reason) {
        requireStatus(PayoutStatus.REQUESTED);
        if (!isProviderConfirmed()) providerStatus = PayoutProviderStatus.UNKNOWN;
        lastError = reason;
    }

    /** 응답이 스냅샷과 다르다. 다시 요청하지 않고 조회로만 확정한다. */
    public void blockInquiryOnly(String reason) {
        requireStatus(PayoutStatus.REQUESTED);
        inquiryOnly = true;
        verificationReason = reason;
        providerStatus = PayoutProviderStatus.UNKNOWN;
    }

    /** 다음 조회 시각. 실행권 점유(다른 실행자가 이 시각 전에는 건드리지 않음)도 이 값으로 표현한다. */
    public void scheduleInquiry(LocalDateTime next) {
        requireStatus(PayoutStatus.REQUESTED);
        nextAttemptAt = next;
    }

    /** 지급대행이 이 키의 지급을 접수했음을 확인한 적이 있는지. 확인했다면 미등록 응답은 재전송 근거가 아니라 불일치다. */
    public boolean isProviderConfirmed() {
        return providerStatus == PayoutProviderStatus.ACCEPTED
                || providerStatus == PayoutProviderStatus.PROCESSING
                || externalPayoutId != null;
    }

    /** 지급 완료. 정산서 PAID·지급 분개와 같은 트랜잭션에서만 부른다. */
    public void complete(String externalPayoutId, LocalDateTime now) {
        requireStatus(PayoutStatus.REQUESTED);
        if (externalPayoutId == null || externalPayoutId.isBlank())
            throw new IllegalArgumentException("완료는 지급대행 거래 ID가 있어야 한다");
        status = PayoutStatus.DONE;
        this.externalPayoutId = externalPayoutId;
        providerStatus = null;
        doneAt = now;
        release(null);
        lastError = null;
    }

    /** 지급대행이 실패를 확정했다. 돈은 나가지 않았다. */
    public void fail(PayoutFailure code, String externalPayoutId) {
        requireStatus(PayoutStatus.REQUESTED);
        status = PayoutStatus.FAILED;
        failureCode = code;
        if (externalPayoutId != null) this.externalPayoutId = externalPayoutId;
        providerStatus = null;
        release(null);
    }

    private void release(LocalDateTime next) {
        nextAttemptAt = next;
    }

    private void requireStatus(PayoutStatus expected) {
        if (status != expected) throw new IllegalStateException("지급 상태가 " + expected + "가 아니다: " + status);
    }
}
