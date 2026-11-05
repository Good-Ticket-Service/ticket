package com.sparta.ticket.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.port.settlement.LedgerJournalWriter;
import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.LedgerTransactionRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementDbFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class JdbcLedgerJournalWriterIntegrationTest extends PaymentIntegrationTest {
    @Autowired
    private LedgerJournalWriter writer;

    @Autowired
    private LedgerTransactionRepository ledger;

    @Autowired
    private SettlementRules rules;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private LedgerTransaction draft(long paymentId, long sellerId) {
        return LedgerJournals.sale(
                SettlementDbFixture.sale(rules, paymentId, sellerId, 10_000), SettlementDbFixture.APPROVED);
    }

    @Test
    void writesAllLinesWithAuditAndCanBeReadByJpa() {
        long seller = 910_001L;
        long payment = SettlementDbFixture.payment(jdbc, seller, 10_000);
        LedgerTransaction expected = draft(payment, seller);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Username", "ledger-operator");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            tx.executeWithoutResult(status -> writer.append(expected));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        LedgerTransaction stored = ledger.findByKey(LedgerSourceType.SALE, payment, LedgerAction.POST)
                .orElseThrow();
        assertThat(stored.sameMeaningAs(expected)).isTrue();
        assertThat(stored.getCreatedBy()).isEqualTo("ledger-operator");
        assertThat(stored.getUpdatedBy()).isEqualTo("ledger-operator");
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_entries WHERE transaction_id = ? AND created_by = 'ledger-operator' AND updated_by = 'ledger-operator'",
                        Integer.class,
                        stored.getId()))
                .isEqualTo(expected.lines().size());
    }

    @Test
    void failingLineInsertRollsBackHeaderAsWell() {
        long seller = 910_002L;
        long payment = SettlementDbFixture.payment(jdbc, seller, 10_000);
        jdbc.execute(
                "ALTER TABLE p_ledger_entries ADD CONSTRAINT test_reject_commission CHECK (account <> 'COMMISSION_REVENUE') NOT VALID");
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> writer.append(draft(payment, seller))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE p_ledger_entries DROP CONSTRAINT test_reject_commission");
        }
        assertThat(ledger.findByKey(LedgerSourceType.SALE, payment, LedgerAction.POST))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_entries e JOIN p_ledger_transactions t ON t.id=e.transaction_id WHERE t.payment_id=?",
                        Integer.class,
                        payment))
                .isZero();
    }

    @Test
    void requiresCallerTransaction() {
        long seller = 910_003L;
        long payment = SettlementDbFixture.payment(jdbc, seller, 10_000);
        assertThatThrownBy(() -> writer.append(draft(payment, seller)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
