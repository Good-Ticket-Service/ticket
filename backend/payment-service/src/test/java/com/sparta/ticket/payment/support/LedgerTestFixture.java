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
            int changed = jdbc.update(sql, arguments);
            jdbc.execute("ALTER TABLE p_ledger_transactions ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE p_ledger_entries ENABLE TRIGGER USER");
            return changed;
        });
    }
}
