package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.service.observation.JobObservation;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Applied;
import com.sparta.ticket.payment.application.service.settlement.PayoutExecutionTransactions.Claim;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 최초 지급 실행(Task 20-8). 실행권 획득(트랜잭션) → 지급대행 호출(트랜잭션 밖) → 결과 반영(트랜잭션) 세 단계다.
 * 결과 반영이 실패해도 지급은 REQUESTED로 남아 같은 키 조회로 확정한다. 주기 조회·lease 회수는 20-9가 맡는다.
 * Note: payout.enabled=false는 새 지급 요청만 막는다. REQUESTED 결과 조회(inquire)는 스위치와 무관하게 계속한다.
 */
@Slf4j
@Component
public class PayoutExecutionJob {

    private final PayoutRepository payouts;
    private final PayoutExecutionTransactions transactions;
    private final PayoutAgency agency;
    private final JobObservation observation;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    public PayoutExecutionJob(
            PayoutRepository payouts,
            PayoutExecutionTransactions transactions,
            PayoutAgency agency,
            MeterRegistry meters,
            Clock clock,
            @Value("${payment.settlement.payout.enabled:false}") boolean enabled,
            @Value("${payment.settlement.payout.batch-size:50}") int batchSize) {
        this.payouts = payouts;
        this.transactions = transactions;
        this.agency = agency;
        this.observation = new JobObservation(meters, "settlement-payout");
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    /** 실행할 시각이 된 READY 지급을 한 묶음 실행한다. 게이트 실패·잠김 건은 건너뛰고 다음 실행이 다시 찾는다. */
    public void runOnce() {
        if (!enabled) return;
        observation.run(() -> {
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
            observation.failed();
            log.error("지급 실행권 획득 실패. READY로 남아 다음 실행이 다시 찾는다. payoutId={}", payoutId, e);
            return Optional.empty();
        }
        if (claim.isEmpty()) return Optional.empty();
        observation.claimed();
        return applySafely(claim.get(), call(() -> agency.request(claim.get().order())));
    }

    /** REQUESTED 지급을 같은 키로 한 번 조회해 반영한다. 20-9 recovery와 시험이 쓴다. */
    public Optional<Applied> inquire(Long payoutId) {
        Optional<Claim> claim = transactions.requested(payoutId);
        if (claim.isEmpty()) return Optional.empty();
        return applySafely(claim.get(), call(() -> agency.inquire(claim.get().idempotencyKey())));
    }

    /** 어댑터가 예상 못 한 예외를 던져도 보냈을 수 있으므로 결과 불명으로 남긴다. */
    private static PayoutAgency.PayoutAgencyResult call(Supplier<PayoutAgency.PayoutAgencyResult> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return new PayoutAgency.PayoutAgencyResult.Unknown(e.getClass().getSimpleName());
        }
    }

    private Optional<Applied> applySafely(Claim claim, PayoutAgency.PayoutAgencyResult result) {
        try {
            return Optional.of(transactions.apply(claim, result));
        } catch (RuntimeException e) {
            observation.failed();
            log.error(
                    "지급 결과 반영 실패. REQUESTED로 남아 같은 키 조회로 확정한다. payoutId={}, attempt={}",
                    claim.payoutId(),
                    claim.attempt(),
                    e);
            return Optional.empty();
        }
    }
}
