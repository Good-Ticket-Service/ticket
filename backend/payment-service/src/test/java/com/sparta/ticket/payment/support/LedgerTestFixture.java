package com.sparta.ticket.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 시험 소유자만 사용하는 초기화·변조 도구. 운영 애플리케이션에는 우회 설정이 없다. */
public final class LedgerTestFixture {
    private LedgerTestFixture() {}

    public static void clear(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE p_ledger_transactions CASCADE");
        jdbc.update("UPDATE p_reconcile_checkpoint SET valid=false");
        jdbc.update("DELETE FROM p_reconcile_account_balances");
    }

    /** DDL 잠금·변조·트리거 복원이 같은 시험 트랜잭션이다. 실패해도 롤백이 DDL을 복원한다. */
    public static int corrupt(JdbcTemplate jdbc, String sql, Object... arguments) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            jdbc.execute("ALTER TABLE p_ledger_transactions DISABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE p_ledger_entries DISABLE TRIGGER USER");
            String journalDelete = "DELETE FROM p_ledger_transactions WHERE ";
            if (sql.startsWith(journalDelete)) {
                // Note: 원장 누락을 만드는 시험은 같은 원천 사건도 함께 제거한다. 운영 경로에는 이 우회가 없다.
                // 집계 세대가 존재하는 시험은 별도 복구 시나리오로 검증해야 한다.
                Long builds = jdbc.queryForObject("SELECT count(*) FROM p_settlement_projection_builds", Long.class);
                if (builds != null && builds > 0) throw new IllegalStateException("집계 세대가 있는 원장은 이 fixture로 삭제할 수 없다");
                jdbc.execute("ALTER TABLE p_settlement_projection_facts DISABLE TRIGGER USER");
                jdbc.update(
                        "DELETE FROM p_settlement_projection_facts WHERE journal_id IN (SELECT id FROM p_ledger_transactions WHERE "
                                + sql.substring(journalDelete.length()) + ")",
                        arguments);
                jdbc.execute("ALTER TABLE p_settlement_projection_facts ENABLE TRIGGER USER");
            }
            int changed = jdbc.update(sql, arguments);
            jdbc.execute("ALTER TABLE p_ledger_transactions ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE p_ledger_entries ENABLE TRIGGER USER");
            return changed;
        });
    }
}
