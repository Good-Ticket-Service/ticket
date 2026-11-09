package com.sparta.ticket.payment.infrastructure.pgsettlement;

import com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.pg.enabled", havingValue = "true", matchIfMissing = true)
public class PgSettlementScheduler {
    private final PgSettlementRunner runner;
    private final PgSettlementProperties properties;
    private final JobRepository repository;
    private final JobExplorer explorer;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 05시 이후 미처리 날짜를 오래된 순서로 보충한다. 실패는 30분 간격으로 최대 4회 자동 실행한다. */
    @Scheduled(fixedDelayString = "${payment.pg.schedule-delay-ms:60000}")
    public void schedule() {
        var now = LocalDateTime.now(clock);
        if (now.getHour() < 5) return;
        for (String provider : List.of("TOSS", "KAKAO")) {
            var dates = jdbc.query(
                    """
                    SELECT x.day::date FROM generate_series(?::date,?::date,interval '1 day') x(day)
                    LEFT JOIN p_pg_settlement_days d ON d.provider=? AND d.settlement_date=x.day::date
                    WHERE d.id IS NULL OR d.status<>'POSTED' ORDER BY x.day LIMIT 1
                    """,
                    (rs, n) -> rs.getObject(1, LocalDate.class),
                    properties.startDate(),
                    now.toLocalDate().minusDays(1),
                    provider);
            if (dates.isEmpty()) continue;
            var date = dates.get(0);
            var params = new JobParametersBuilder()
                    .addString("provider", provider)
                    .addString("settlementDate", date.toString())
                    .toJobParameters();
            var last = repository.getLastJobExecution("pgSettlementJob", params);
            if (last != null) {
                if (last.isRunning() || last.getStatus() == BatchStatus.COMPLETED) continue;
                var attempts = explorer.getJobExecutions(last.getJobInstance());
                if (attempts.size() >= 4
                        || last.getEndTime() == null
                        || last.getEndTime().plusMinutes(30).isAfter(now)) continue;
            }
            try {
                runner.run(provider, date);
            } catch (RuntimeException e) {
                log.warn("PG 정산 실행 요청 실패. provider={}, date={}", provider, date, e);
            }
        }
    }
}
