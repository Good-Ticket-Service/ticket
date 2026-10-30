package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutAgencyResult;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutOrder;
import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.application.service.settlement.PayoutResultVerifier.Verdict;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.PayoutFailure;
import com.sparta.ticket.payment.domain.model.settlement.PayoutStatus;
import com.sparta.ticket.payment.domain.model.settlement.SellerPayoutAccount;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatement;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SellerPayoutAccountRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지급 실행의 DB 단계(Task 20-8, settlement-design 7.2절). 실행권 획득과 결과 반영은 각각 짧은 트랜잭션이고, 지급대행 호출은
 * 그 사이 트랜잭션 밖에서 한다. 어느 단계에서 끊겨도 지급은 READY나 REQUESTED로 남아 같은 키 조회로 확정할 수 있다.
 * 잠금 순서는 payout → statement다. 승인·환불·발행 경로는 지급 행을 잡지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutExecutionTransactions {

    /** 실행권 획득 결과. 외부 호출과 결과 반영이 같은 attempt·키인지 확인하는 데 쓴다. */
    public record Claim(Long payoutId, int attempt, String idempotencyKey, PayoutOrder order) {}

    public enum Applied {
        /** 완료 확정. 정산서 PAID와 지급 분개를 함께 남겼다. */
        DONE,
        /** 지급대행이 실패를 확정했다. */
        FAILED,
        /** 접수·처리 중. 조회로 확정한다. */
        IN_PROGRESS,
        /** 결과를 모른다. 같은 키 조회로 확정한다. */
        UNKNOWN,
        /** 응답이 스냅샷과 다르다. 다시 요청하지 않고 조회만 한다. */
        INQUIRY_ONLY,
        /** 이미 다른 attempt·결과로 바뀐 지급이다. 아무것도 바꾸지 않았다. */
        STALE
    }

    private static final int REASON_LENGTH = 80;

    private final PayoutRepository payouts;
    private final SellerPayoutAccountRepository accounts;
    private final LedgerTransactions ledger;
    private final CommittedObservation observation;
    private final Clock clock;

    @Value("${payment.settlement.payout.lock-timeout-ms:5000}")
    private long lockTimeoutMillis;

    /** 실행권 점유 시간. 실행자가 끊기면 이 시간이 지난 뒤 다른 실행자가 같은 키로 조회를 시작한다(next_attempt_at으로 표현). */
    @Value("${payment.settlement.payout.lease-seconds:60}")
    private long leaseSeconds;

    @Value("${payment.settlement.payout.inquiry-delay-seconds:30}")
    private long inquiryDelaySeconds;

    /** 게이트 실패·계좌 미등록 뒤 다시 시도할 때까지의 길이. 실패한 후보를 즉시 다시 잡는 루프를 막는다. */
    @Value("${payment.settlement.payout.retry-after-minutes:60}")
    private long retryAfterMinutes;

    /**
     * READY → REQUESTED. 다른 실행자가 잡은 지급은 기다리지 않고 건너뛴다. 게이트와 계좌를 통과해야 키·계좌 스냅샷을 찍는다.
     * 커밋 뒤에만 지급대행을 부른다.
     */
    @Transactional
    public Optional<Claim> claim(Long payoutId) {
        LocalDateTime now = LocalDateTime.now(clock);
        payouts.limitLockWait(lockTimeoutMillis);
        Optional<Payout> locked = payouts.lockDueReady(payoutId, now);
        if (locked.isEmpty()) return Optional.empty();
        Payout payout = locked.get();
        payouts.lockStatement(payout.getStatementId());

        Optional<String> violation = payouts.gateViolation(payout.getId(), payout.getStatementId());
        if (violation.isPresent()) {
            block(payout, "GATE_" + violation.get(), now);
            return Optional.empty();
        }
        Optional<SellerPayoutAccount> account =
                accounts.findBySellerId(payout.getSellerId()).filter(SellerPayoutAccount::isRegistered);
        if (account.isEmpty()) {
            block(payout, "ACCOUNT_NOT_REGISTERED", now);
            return Optional.empty();
        }
        payout.claim(account.get(), now, now.plusSeconds(leaseSeconds));
        observation.counter("settlement.payout.claimed");
        return Optional.of(claimOf(payout));
    }

    private static Claim claimOf(Payout payout) {
        return new Claim(
                payout.getId(),
                payout.getAttempt(),
                payout.getIdempotencyKey(),
                new PayoutOrder(
                        payout.getIdempotencyKey(),
                        "seller:" + payout.getSellerId(),
                        payout.getAccountRef(),
                        payout.getHolderMasked(),
                        payout.getAmount()));
    }

    /** REQUESTED 지급의 현재 실행권. 같은 키 조회에 쓴다. */
    @Transactional(readOnly = true)
    public Optional<Claim> requested(Long payoutId) {
        return payouts.find(payoutId)
                .filter(payout -> payout.getStatus() == PayoutStatus.REQUESTED)
                .map(PayoutExecutionTransactions::claimOf);
    }

    /**
     * 지급대행 응답·조회 결과 반영. 이번 attempt의 실행권이 아니면 버린다. 완료는 키·금액·계좌·거래 ID가 맞을 때만이고,
     * 지급 DONE·정산서 PAID·지급 분개를 한 트랜잭션에서 남긴다. 불명은 FAILED로 바꾸지 않는다.
     */
    @Transactional
    public Applied apply(Claim claim, PayoutAgencyResult result) {
        LocalDateTime now = LocalDateTime.now(clock);
        payouts.limitLockWait(lockTimeoutMillis);
        Payout payout = payouts.lock(claim.payoutId()).orElseThrow();
        if (!payout.ownedBy(claim.attempt(), claim.idempotencyKey())) return Applied.STALE;
        LocalDateTime nextInquiryAt = now.plusSeconds(inquiryDelaySeconds);

        Applied applied = applyResult(payout, result, now, nextInquiryAt);
        observation.counter("settlement.payout.applied", "result", applied.name());
        return applied;
    }

    private Applied applyResult(
            Payout payout, PayoutAgencyResult result, LocalDateTime now, LocalDateTime nextInquiryAt) {
        if (result instanceof PayoutAgencyResult.Observed observed)
            return applyVerdict(payout, PayoutResultVerifier.verify(payout, observed), now, nextInquiryAt);
        if (result instanceof PayoutAgencyResult.RequestInvalid invalid) {
            payout.fail(PayoutFailure.REQUEST_INVALID, null);
            alert(payout, PayoutFailure.REQUEST_INVALID.name(), invalid.reason());
            return Applied.FAILED;
        }
        if (result instanceof PayoutAgencyResult.KeyConflict) return inquiryOnly(payout, "KEY_CONFLICT", nextInquiryAt);
        if (result instanceof PayoutAgencyResult.Malformed)
            return inquiryOnly(payout, "MALFORMED_RESPONSE", nextInquiryAt);
        String reason = result instanceof PayoutAgencyResult.Unknown unknown
                ? truncate(unknown.reason())
                : "PROVIDER_NOT_FOUND";
        payout.markUnknown(reason, nextInquiryAt);
        return Applied.UNKNOWN;
    }

    private Applied applyVerdict(Payout payout, Verdict verdict, LocalDateTime now, LocalDateTime nextInquiryAt) {
        if (verdict instanceof Verdict.Completed completed) {
            SettlementStatement statement =
                    payouts.lockStatement(payout.getStatementId()).orElseThrow();
            payout.complete(completed.externalPayoutId(), now);
            statement.markPaid(now);
            ledger.post(LedgerJournals.payoutDone(
                    payout.getId(), statement.getId(), payout.getSellerId(), payout.getAmount(), now));
            return Applied.DONE;
        }
        if (verdict instanceof Verdict.Failed failed) {
            payout.fail(failed.code(), failed.externalPayoutId());
            alert(payout, failed.code().name(), "지급대행 실패 확정");
            return Applied.FAILED;
        }
        if (verdict instanceof Verdict.InProgress progress) {
            payout.observe(progress.status(), progress.externalPayoutId(), nextInquiryAt);
            return Applied.IN_PROGRESS;
        }
        return inquiryOnly(payout, ((Verdict.Mismatch) verdict).reason(), nextInquiryAt);
    }

    private Applied inquiryOnly(Payout payout, String reason, LocalDateTime nextInquiryAt) {
        payout.blockInquiryOnly(reason, nextInquiryAt);
        alert(payout, "INQUIRY_ONLY", reason);
        return Applied.INQUIRY_ONLY;
    }

    /** 게이트 실패·계좌 미등록. READY에 머물고 유한한 다음 시도 시각을 남긴다. */
    private void block(Payout payout, String reason, LocalDateTime now) {
        payout.defer(reason, now.plusMinutes(retryAfterMinutes));
        observation.counter("settlement.payout.blocked", "reason", reason.startsWith("GATE_") ? "GATE" : reason);
        log.error(
                "[ALERT] 지급 보류. READY 유지 후 다시 시도한다. payoutId={}, statementId={}, reason={}",
                payout.getId(),
                payout.getStatementId(),
                reason);
    }

    private void alert(Payout payout, String kind, String reason) {
        observation.counter("settlement.payout.alert", "kind", kind);
        log.error(
                "[ALERT] 지급 확인 필요. payoutId={}, attempt={}, kind={}, reason={}",
                payout.getId(),
                payout.getAttempt(),
                kind,
                reason);
    }

    private static String truncate(String reason) {
        if (reason == null) return "UNKNOWN";
        return reason.length() <= REASON_LENGTH ? reason : reason.substring(0, REASON_LENGTH);
    }
}
