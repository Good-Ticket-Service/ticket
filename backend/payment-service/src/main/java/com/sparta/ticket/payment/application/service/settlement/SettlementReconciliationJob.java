package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.port.settlement.PayoutAgency;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.AgencyStatus;
import com.sparta.ticket.payment.application.port.settlement.PayoutAgency.PayoutAgencyResult;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository.CheckResult;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository.DonePayout;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository.Finished;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 정산 전체 대조(Task 20-11, settlement-design 8.1·8.2절). 매일 한 번 DB 대조를 한 스냅샷에서 실행하고, 이어서 최근 DONE 지급을
 * 지급대행 기록과 맞춘다(지급대행 대사). 위반은 실행 기록·지표·구조화 로그로만 남기고 고치지 않는다. 지급을 막는 것은 정산서
 * 단위 게이트이고, 여기서는 사람이 판단할 수 있게 드러낸다. 실행이 끝까지 성공해야 성공 시각을 남겨, 미실행·중단을 성공으로 치지 않는다.
 */
@Slf4j
@Component
public class SettlementReconciliationJob {

    /** 지급대행 대사 항목 이름. 응답이 없거나 다르면 위반, 조회 장애는 위반이 아니라 확인 못 함으로 따로 센다. */
    public static final String PROVIDER_CHECK = "PAYOUT_PROVIDER_MISMATCH";

    private static final int SAMPLE_SIZE = 10;

    private final SettlementReconcileRepository repository;
    private final PayoutAgency agency;
    private final Clock clock;
    private final LocalDateTime paymentsSince;
    private final long providerWindowHours;
    private final int providerLimit;

    public SettlementReconciliationJob(
            SettlementReconcileRepository repository,
            PayoutAgency agency,
            Clock clock,
            @Value("${payment.settlement.reconcile.payments-since:}") String paymentsSince,
            @Value("${payment.settlement.reconcile.provider-window-hours:48}") long providerWindowHours,
            @Value("${payment.settlement.reconcile.provider-limit:500}") int providerLimit) {
        this.repository = repository;
        this.agency = agency;
        this.clock = clock;
        this.paymentsSince = paymentsSince.isBlank() ? null : LocalDateTime.parse(paymentsSince);
        this.providerWindowHours = providerWindowHours;
        this.providerLimit = providerLimit;
    }

    public record Report(long runId, long violations, List<CheckResult> results, int providerUnreachable) {}

    public Report run() {
        long runId = repository.startRun(LocalDateTime.now(clock));
        try {
            List<CheckResult> results = new ArrayList<>(repository.runChecks(paymentsSince));
            List<DonePayout> done =
                    repository.findDonePayouts(LocalDateTime.now(clock).minusHours(providerWindowHours), providerLimit);
            ProviderResult provider = reconcileProvider(done);
            results.add(provider.result());
            long violations =
                    results.stream().mapToLong(CheckResult::violations).sum();
            repository.finishRun(
                    runId,
                    new Finished(
                            "SUCCEEDED",
                            LocalDateTime.now(clock),
                            violations,
                            done.size(),
                            provider.unreachable(),
                            null,
                            results));
            for (CheckResult result : results) {
                if (result.violations() > 0)
                    log.error(
                            "[RECONCILE] 정산 대조 위반. runId={}, check={}, violations={}, sample={}",
                            runId,
                            result.check(),
                            result.violations(),
                            result.sample());
            }
            if (provider.unreachable() > 0)
                log.warn("[RECONCILE] 지급대행 대사 중 확인 못 한 지급. runId={}, unreachable={}", runId, provider.unreachable());
            log.info(
                    "[RECONCILE] 정산 대조 완료. runId={}, violations={}, providerChecked={}",
                    runId,
                    violations,
                    done.size());
            return new Report(runId, violations, results, provider.unreachable());
        } catch (RuntimeException e) {
            log.error("[RECONCILE] 정산 대조 실패. 성공으로 치지 않는다. runId={}", runId, e);
            repository.finishRun(
                    runId,
                    new Finished(
                            "FAILED",
                            LocalDateTime.now(clock),
                            null,
                            null,
                            null,
                            e.getClass().getSimpleName(),
                            List.of()));
            throw e;
        }
    }

    private record ProviderResult(CheckResult result, int unreachable) {}

    /**
     * DONE 지급마다 같은 키로 지급대행을 조회해 완료·금액·계좌·거래 ID를 맞춘다. 지급대행이 모른다고(404) 하거나 다르게 답하면 돈이
     * 장부와 다르게 움직였을 수 있으므로 위반이다. DB 트랜잭션 밖에서 부른다.
     */
    private ProviderResult reconcileProvider(List<DonePayout> done) {
        long mismatched = 0;
        int unreachable = 0;
        List<String> sample = new ArrayList<>();
        for (DonePayout payout : done) {
            PayoutAgencyResult result;
            try {
                result = agency.inquire(payout.idempotencyKey());
            } catch (RuntimeException e) {
                result = new PayoutAgencyResult.Unknown(e.getClass().getSimpleName());
            }
            if (result instanceof PayoutAgencyResult.Unknown) {
                unreachable++;
                continue;
            }
            if (!matches(payout, result)) {
                mismatched++;
                if (sample.size() < SAMPLE_SIZE) sample.add(String.valueOf(payout.payoutId()));
            }
        }
        return new ProviderResult(
                new CheckResult(PROVIDER_CHECK, mismatched, sample.isEmpty() ? null : String.join(",", sample)),
                unreachable);
    }

    private static boolean matches(DonePayout payout, PayoutAgencyResult result) {
        return result instanceof PayoutAgencyResult.Observed observed
                && observed.status() == AgencyStatus.COMPLETED
                && payout.idempotencyKey().equals(observed.idempotencyKey())
                && observed.amount() != null
                && observed.amount() == payout.amount()
                && payout.accountRef().equals(observed.accountRef())
                && payout.externalPayoutId().equals(observed.payoutId());
    }
}
