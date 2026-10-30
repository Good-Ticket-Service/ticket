package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutAgencyResult;
import com.sparta.ticket.payment.application.service.observation.JobObservation;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Applied;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Claim;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.InquiryClaim;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 지급 실행(Task 20-8)과 recovery(Task 20-9). 둘 다 실행권·점유(트랜잭션) → 지급대행 호출(트랜잭션 밖) → 결과 반영(트랜잭션)이다.
 * 결과 반영이 실패해도 지급은 REQUESTED로 남아 다음 조회 시각에 같은 키로 다시 확인한다.
 * Note: payout.enabled=false는 새 지급 요청과 같은 키 재전송만 막는다. REQUESTED 결과 조회는 스위치와 무관하게 계속한다.
 */
@Slf4j
@Component
public class PayoutExecutionJob {

    private final PayoutRepository payouts;
    private final PayoutExecutionTransactions transactions;
    private final PayoutAgency agency;
    private final MeterRegistry meters;
    private final JobObservation execution;
    private final JobObservation recovery;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final Duration keyValidity;

    /**
     * @param keyValidityHours 같은 키 재전송을 허용하는 최초 요청 뒤 기간. 지급대행이 멱등 키를 보존하는 기간보다 짧게 둔다. 지나서
     *     같은 키로 보내면 지급대행이 새 요청으로 받아 중복 송금할 수 있다
     */
    public PayoutExecutionJob(
            PayoutRepository payouts,
            PayoutExecutionTransactions transactions,
            PayoutAgency agency,
            MeterRegistry meters,
            Clock clock,
            @Value("${payment.settlement.payout.enabled:false}") boolean enabled,
            @Value("${payment.settlement.payout.batch-size:50}") int batchSize,
            @Value("${payment.settlement.payout.key-validity-hours:168}") long keyValidityHours) {
        this.payouts = payouts;
        this.transactions = transactions;
        this.agency = agency;
        this.meters = meters;
        this.execution = new JobObservation(meters, "settlement-payout");
        this.recovery = new JobObservation(meters, "settlement-payout-recovery");
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.keyValidity = Duration.ofHours(keyValidityHours);
    }

    /** 실행할 시각이 된 READY 지급을 한 묶음 실행한다. 게이트 실패·잠김 건은 건너뛰고 다음 실행이 다시 찾는다. */
    public void runOnce() {
        if (!enabled) return;
        execution.run(() -> {
            List<Long> due = payouts.findDueReadyIds(LocalDateTime.now(clock), batchSize);
            for (Long payoutId : due) execute(payoutId);
        });
    }

    /** 지급 하나를 실행한다. 실행권을 못 얻으면 아무것도 보내지 않는다. */
    public Optional<Applied> execute(Long payoutId) {
        if (!enabled) return Optional.empty();
        Optional<Claim> claim;
        try {
            claim = transactions.claim(payoutId);
        } catch (RuntimeException e) {
            execution.failed();
            log.error("지급 실행권 획득 실패. READY로 남아 다음 실행이 다시 찾는다. payoutId={}", payoutId, e);
            return Optional.empty();
        }
        if (claim.isEmpty()) return Optional.empty();
        execution.claimed();
        return applySafely(claim.get(), call(() -> agency.request(claim.get().order())));
    }

    /** 조회할 시각이 된 REQUESTED 지급을 한 묶음 조회한다. 스위치가 꺼져 있어도 돈다. */
    public void recoverOnce() {
        recovery.run(() -> {
            List<Long> due = payouts.findDueRequestedIds(LocalDateTime.now(clock), batchSize);
            for (Long payoutId : due) recover(payoutId);
        });
    }

    /**
     * REQUESTED 지급 하나를 같은 키로 조회해 반영한다. 지급대행이 그 키를 받은 적 없다고(404) 답하면, 재전송 조건을 모두 만족할 때만
     * 같은 키·같은 스냅샷으로 다시 보낸다. 같은 키라 이전 요청이 늦게 도착해도 지급은 1건이다.
     */
    public Optional<Applied> recover(Long payoutId) {
        Optional<InquiryClaim> claimed;
        try {
            claimed = transactions.claimInquiry(payoutId);
        } catch (RuntimeException e) {
            recovery.failed();
            log.error("지급 조회 점유 실패. 다음 실행이 다시 찾는다. payoutId={}", payoutId, e);
            return Optional.empty();
        }
        if (claimed.isEmpty()) return Optional.empty();
        recovery.claimed();
        Claim claim = claimed.get().claim();
        PayoutAgencyResult result = call(() -> agency.inquire(claim.idempotencyKey()));
        if (result instanceof PayoutAgencyResult.NotFound) {
            Optional<String> blocked = resendBlocked(claimed.get());
            meters.counter("settlement.payout.resend", "result", blocked.orElse("SENT"))
                    .increment();
            if (blocked.isEmpty()) result = call(() -> agency.request(claim.order()));
            else if (!"STATE".equals(blocked.get()))
                log.error(
                        "[ALERT] 지급대행 미등록인데 같은 키로 재전송하지 않는다. payoutId={}, attempt={}, reason={}",
                        claim.payoutId(),
                        claim.attempt(),
                        blocked.get());
        }
        return applySafely(claim, result);
    }

    /** 같은 키 재전송을 막는 이유. 비어 있으면 보낼 수 있다. */
    private Optional<String> resendBlocked(InquiryClaim claimed) {
        if (!claimed.resendable()) return Optional.of("STATE");
        if (!enabled) return Optional.of("DISABLED");
        if (!LocalDateTime.now(clock).isBefore(claimed.requestedAt().plus(keyValidity)))
            return Optional.of("KEY_EXPIRED");
        return Optional.empty();
    }

    /** 어댑터가 예상 못 한 예외를 던져도 보냈을 수 있으므로 결과 불명으로 남긴다. */
    private static PayoutAgencyResult call(Supplier<PayoutAgencyResult> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return new PayoutAgencyResult.Unknown(e.getClass().getSimpleName());
        }
    }

    private Optional<Applied> applySafely(Claim claim, PayoutAgencyResult result) {
        try {
            return Optional.of(transactions.apply(claim, result));
        } catch (RuntimeException e) {
            execution.failed();
            log.error(
                    "지급 결과 반영 실패. REQUESTED로 남아 같은 키 조회로 확정한다. payoutId={}, attempt={}",
                    claim.payoutId(),
                    claim.attempt(),
                    e);
            return Optional.empty();
        }
    }
}
