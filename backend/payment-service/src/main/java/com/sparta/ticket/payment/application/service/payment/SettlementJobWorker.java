package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.service.observation.JobObservation;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

/**
 * 정산 반영 워커(#304). 작업을 한 건씩 잠그고 같은 트랜잭션에서 반영한다. 반영 실패는 작업에만 남고 결제 확정에는 영향을 주지 않는다.
 * 실행 스레드 하나로 천천히 처리한다. 정산은 일 단위 업무라 몰릴 때 쌓였다가 한가할 때 따라잡아도 된다(허용 지연 1시간).
 */
@Slf4j
@Component
public class SettlementJobWorker {
    private final SettlementJobTransactions transactions;
    private final SettlementJobProperties properties;
    private final MeterRegistry meters;
    private final JobObservation observation;

    public SettlementJobWorker(
            SettlementJobTransactions transactions, SettlementJobProperties properties, MeterRegistry meters) {
        this.transactions = transactions;
        this.properties = properties;
        this.meters = meters;
        this.observation = new JobObservation(meters, "settlement-job");
    }

    /** 실행 예산이 지나면 새 작업을 시작하지 않는다. 이미 시작한 반영은 끝까지 한다. */
    public void runOnce() {
        observation.run(this::executeOnce);
    }

    private void executeOnce() {
        long started = System.nanoTime();
        long budget = TimeUnit.SECONDS.toNanos(properties.maxRunSeconds());
        List<Long> attempted = new ArrayList<>(List.of(0L));
        for (int i = 0; i < properties.batchSize() && System.nanoTime() - started < budget; i++) {
            int before = attempted.size();
            try {
                if (transactions.processNext(attempted).isEmpty()) break;
                observation.claimed();
            } catch (RuntimeException e) {
                // Note: 작업을 고르기 전에 실패했으면(DB 연결 등) 이번 실행을 멈춘다. 고른 뒤 실패면 그 작업만 실패로 기록하고 다음으로 간다.
                if (attempted.size() == before) {
                    failure("claim");
                    break;
                }
                recordFailure(attempted.get(attempted.size() - 1), e);
            }
        }
        meters.timer("settlement.job.run.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private void recordFailure(Long jobId, RuntimeException e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String error = root.getClass().getSimpleName();
        try {
            if (transactions.fail(jobId, error))
                log.error("정산 반영 작업 격리. 원인 수정 후 재처리가 필요하다. jobId={}, error={}", jobId, error, e);
            else log.warn("정산 반영 실패. 다음 시도로 미룬다. jobId={}, error={}", jobId, error);
        } catch (RuntimeException f) {
            failure("fail");
            log.error("정산 반영 실패 기록도 실패했다. 다음 실행에서 다시 시도한다. jobId={}", jobId, f);
        }
    }

    private void failure(String phase) {
        observation.failed();
        meters.counter("settlement.job.errors", "phase", phase).increment();
        log.warn("정산 워커 단계 실패. phase={}", phase);
    }
}
