package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.application.port.settlement.LedgerJournalWriter;
import com.sparta.ticket.payment.domain.model.settlement.LedgerLine;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** IDENTITY 키를 유지하면서 분개 줄을 다중 행 INSERT 한 번으로 저장한다. */
@Component
@RequiredArgsConstructor
public class JdbcLedgerJournalWriter implements LedgerJournalWriter {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(LedgerTransaction journal) {
        // Note: JPA 변경과 JDBC 쓰기는 같은 트랜잭션이다. 원천 FK와 후속 조회가 볼 변경을 먼저 반영한다.
        entityManager.flush();
        LocalDateTime now = LocalDateTime.now();
        String actor = auditorAware.getCurrentAuditor().orElse("SYSTEM");
        Long id = jdbc.queryForObject(
                """
                INSERT INTO p_ledger_transactions
                  (source_type,source_id,action,seller_id,payment_id,refund_id,statement_id,payout_id,
                   policy_version,reverses_transaction_id,occurred_at,created_at,created_by,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """,
                Long.class,
                journal.getSourceType().name(),
                journal.getSourceId(),
                journal.getAction().name(),
                journal.getSellerId(),
                journal.getPaymentId(),
                journal.getRefundId(),
                journal.getStatementId(),
                journal.getPayoutId(),
                journal.getPolicyVersion(),
                journal.getReversesTransactionId(),
                journal.getOccurredAt(),
                now,
                actor,
                now,
                actor);
        List<LedgerLine> lines = journal.lines();
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LedgerLine line = lines.get(i);
            Collections.addAll(
                    parameters, id, i + 1, line.account().name(), line.debit(), line.credit(), now, actor, now, actor);
        }
        String values = String.join(",", Collections.nCopies(lines.size(), "(?,?,?,?,?,?,?,?,?)"));
        int inserted = jdbc.update("""
                INSERT INTO p_ledger_entries
                  (transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                VALUES
                """ + values, parameters.toArray());
        if (inserted != lines.size()) throw new IllegalStateException("원장 분개 저장 행 수가 일치하지 않는다");
    }
}
