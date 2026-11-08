package com.sparta.ticket.payment.support;

import com.sparta.ticket.payment.application.service.payment.SettlementJobWorker;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시험에서 정산 반영 작업을 처리한다. 시험은 워커 스케줄러를 끄고, 결제·환불을 확정한 뒤 이걸 불러 SALE·원장을 만든다(#304).
 * 운영과 같은 워커 코드를 쓴다.
 */
public final class SettlementJobDrain {

    private final SettlementJobWorker worker;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SettlementJobDrain(SettlementJobWorker worker, JdbcTemplate jdbc, Clock clock) {
        this.worker = worker;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 실행 시각이 된 대기 작업이 없어질 때까지 워커를 돌린다. 실패해 뒤로 미뤄진 작업은 남는다. */
    public void drain() {
        for (int i = 0; i < 50 && due() > 0; i++) worker.runOnce();
    }

    public int due() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_settlement_jobs WHERE status = 'PENDING' AND next_attempt_at <= ?",
                Integer.class,
                LocalDateTime.now(clock));
    }
}
