package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.service.settlement.LedgerTransactions;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 일회용 시험 프로필에서만 쓰는 실제 원장 기록 경로의 비용 probe. 제품 API가 아니다. */
@RestController
@Profile("projection-measurement")
@RequireRole(Role.MASTER)
class ProjectionSourceCostEndpoint {
    private final EntityManager entityManager;
    private final LedgerTransactions ledger;
    private final PlatformTransactionManager manager;
    private final JdbcTemplate jdbc;

    ProjectionSourceCostEndpoint(
            EntityManager entityManager,
            LedgerTransactions ledger,
            PlatformTransactionManager manager,
            JdbcTemplate jdbc) {
        this.entityManager = entityManager;
        this.ledger = ledger;
        this.manager = manager;
        this.jdbc = jdbc;
    }

    record Result(int transactions, double elapsedMs, List<Double> transactionMs, long sourceFacts) {}

    @PostMapping("/__measure/source-cost")
    ApiResponse<Result> run(@RequestParam int from, @RequestParam int count) {
        if (from < 1 || count < 1 || count > 10000) throw new IllegalArgumentException("시험 입력 범위를 확인한다");
        var tx = new TransactionTemplate(manager);
        var samples = new ArrayList<Double>();
        long started = System.nanoTime();
        for (int id = from; id < from + count; id++) {
            int itemId = id;
            long before = System.nanoTime();
            tx.executeWithoutResult(status -> {
                var sale = entityManager.find(SettlementItem.class, (long) itemId, LockModeType.PESSIMISTIC_WRITE);
                if (sale == null) throw new IllegalArgumentException("시험 SALE이 없다");
                ledger.post(LedgerJournals.sale(sale, sale.getApprovedAt()));
            });
            samples.add((System.nanoTime() - before) / 1e6);
        }
        return ApiResponse.ok(new Result(
                count,
                (System.nanoTime() - started) / 1e6,
                samples,
                jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_facts", Long.class)));
    }
}
