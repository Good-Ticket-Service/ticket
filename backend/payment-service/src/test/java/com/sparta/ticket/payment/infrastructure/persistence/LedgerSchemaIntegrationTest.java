package com.sparta.ticket.payment.infrastructure.persistence;

import static com.sparta.ticket.payment.support.SettlementDbFixture.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import com.sparta.ticket.payment.domain.model.settlement.SettlementItem;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.LedgerTransactionRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementDbFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** p_ledger_transactions·p_ledger_entries의 업무 키 UNIQUE·줄 CHECK가 실제 PostgreSQL에서 동작하는지 본다(Task 20-2). */
class LedgerSchemaIntegrationTest extends PaymentIntegrationTest {

    private static final long SELLER_ID = 20_002L;

    @Autowired
    private LedgerTransactionRepository ledger;

    @Autowired
    private SettlementRules rules;

    @Autowired
    private JdbcTemplate jdbc;

    private SettlementItem sale(long paymentId) {
        return SettlementDbFixture.sale(rules, paymentId, SELLER_ID, 10_000);
    }

    private long payment() {
        return SettlementDbFixture.payment(jdbc, SELLER_ID, 10_000);
    }

    @Test
    void savesHeaderWithLinesAndFindsByKey() {
        long paymentId = payment();
        LedgerTransaction saved = ledger.saveAndFlush(LedgerJournals.sale(sale(paymentId), APPROVED));

        LedgerTransaction found = ledger.findByKey(LedgerSourceType.SALE, paymentId, LedgerAction.POST)
                .orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.lines()).hasSize(4);
        assertThat(jdbc.queryForList(
                        "SELECT line_no FROM p_ledger_entries WHERE transaction_id = ? ORDER BY line_no",
                        Integer.class,
                        saved.getId()))
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void rejectsSecondTransactionWithSameKey() {
        long paymentId = payment();
        ledger.saveAndFlush(LedgerJournals.sale(sale(paymentId), APPROVED));

        assertThatThrownBy(() -> ledger.saveAndFlush(LedgerJournals.sale(sale(paymentId), APPROVED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReverseAndReceivableForSameRefund() {
        long paymentId = payment();
        long refundId = SettlementDbFixture.refund(jdbc, paymentId, 10_000);
        LedgerTransaction original = ledger.saveAndFlush(LedgerJournals.sale(sale(paymentId), APPROVED));
        ledger.saveAndFlush(LedgerJournals.reverse(original, refundId, APPROVED));

        assertThatThrownBy(() -> ledger.saveAndFlush(LedgerJournals.receivable(sale(paymentId), refundId, APPROVED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondReverseOfSameOriginal() {
        long paymentId = payment();
        long refundId = SettlementDbFixture.refund(jdbc, paymentId, 10_000);
        long otherRefundId = SettlementDbFixture.refund(jdbc, payment(), 10_000);
        LedgerTransaction original = ledger.saveAndFlush(LedgerJournals.sale(sale(paymentId), APPROVED));
        ledger.saveAndFlush(LedgerJournals.reverse(original, refundId, APPROVED));

        assertThatThrownBy(() -> ledger.saveAndFlush(LedgerJournals.reverse(original, otherRefundId, APPROVED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsLineWithBothOrNeitherSide() {
        long txId = ledger.saveAndFlush(LedgerJournals.sale(sale(payment()), APPROVED))
                .getId();
        String insert = "INSERT INTO p_ledger_entries (transaction_id, line_no, account, debit, credit, created_at,"
                + " created_by, updated_at, updated_by) VALUES (?, 9, 'PG_CLEARING', ?, ?, now(), 't', now(), 't')";

        assertThatThrownBy(() -> jdbc.update(insert, txId, 0, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, txId, 100, 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
