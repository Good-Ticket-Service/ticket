package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.service.observation.JobObservation;
import com.sparta.ticket.payment.domain.repository.settlement.StatementCandidate;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 정산서 발행 잡(Task 20-5, settlement-design 5절). cutoff를 인자로 받아 대상 (판매자, 회차)를 차례로 발행한다.
 * 상태를 갖지 않고, 업무 판단은 모두 발행 트랜잭션에 있다. 실패·잠김 건은 완료로 보지 않고 다음 실행이 다시 찾는다.
 * Note: Spring Batch로 바꾸면 cutoff는 JobParameter, 읽기는 StatementCandidateReader, 쓰기는 issue()가 된다.
 */
@Slf4j
@Component
public class SettlementStatementJob {

    private final StatementCandidateReader reader;
    private final SettlementStatementTransactions statements;
    private final JobObservation observation;
    private final int batchSize;

    public SettlementStatementJob(
            StatementCandidateReader reader,
            SettlementStatementTransactions statements,
            MeterRegistry meters,
            @Value("${payment.settlement.statement.batch-size:100}") int batchSize) {
        this.reader = reader;
        this.statements = statements;
        this.observation = new JobObservation(meters, "settlement-statement");
        this.batchSize = batchSize;
    }

    public void run(LocalDateTime cutoff) {
        observation.run(() -> issueAll(cutoff));
    }

    private void issueAll(LocalDateTime cutoff) {
        StatementCandidate after = null;
        while (true) {
            List<StatementCandidate> candidates = reader.next(cutoff, after, batchSize);
            if (candidates.isEmpty()) return;
            for (StatementCandidate candidate : candidates) {
                after = candidate;
                observation.claimed();
                try {
                    statements.issue(candidate.getSellerId(), candidate.getScheduleId(), cutoff);
                } catch (RuntimeException e) {
                    observation.failed();
                    log.error(
                            "정산서 발행 실패. 다음 실행에서 다시 찾는다. sellerId={}, scheduleId={}",
                            candidate.getSellerId(),
                            candidate.getScheduleId(),
                            e);
                }
            }
        }
    }
}
