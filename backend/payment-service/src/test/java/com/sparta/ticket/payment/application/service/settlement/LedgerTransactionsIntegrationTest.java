package com.sparta.ticket.payment.application.service.settlement;

import static com.sparta.ticket.payment.support.SettlementDbFixture.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.domain.model.settlement.LedgerAction;
import com.sparta.ticket.payment.domain.model.settlement.LedgerConflictException;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.LedgerSourceType;
import com.sparta.ticket.payment.domain.model.settlement.LedgerTransaction;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.LedgerTransactionRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementDbFixture;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** 업무 키 멱등 기록·충돌·경합·롤백과 설계식 잔액(settlement-design 4.5·8.1절, 시험 S1·S2). */
class LedgerTransactionsIntegrationTest extends PaymentIntegrationTest {

    /** 시험마다 판매자를 나눠 잔액 합계가 다른 시험 데이터와 섞이지 않게 한다. */
    private static final AtomicLong SELLER_IDS = new AtomicLong(30_000);

    @Autowired
    private LedgerTransactions ledgerTransactions;

    @Autowired
    private LedgerTransactionRepository ledger;

    @Autowired
    private SettlementRules rules;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    /** 호출 경로처럼 결제 행을 잠근 트랜잭션에서 기록한다. */
    private LedgerPosting postLocked(long paymentId, LedgerTransaction draft) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM p_payments WHERE id = ? FOR UPDATE", Long.class, paymentId);
            return ledgerTransactions.post(draft);
        });
    }

    private LedgerTransaction saleDraft(long paymentId, long sellerId, int gross) {
        return LedgerJournals.sale(SettlementDbFixture.sale(rules, paymentId, sellerId, gross), APPROVED);
    }

    private int headers(long sellerId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE seller_id = ?", Integer.class, sellerId);
    }

    /** 판매자 계정별 차변 − 대변. */
    private Map<String, Long> balances(long sellerId) {
        return jdbc
                .queryForList(
                        "SELECT e.account, SUM(e.debit - e.credit) AS net FROM p_ledger_entries e"
                                + " JOIN p_ledger_transactions t ON t.id = e.transaction_id WHERE t.seller_id = ?"
                                + " GROUP BY e.account",
                        sellerId)
                .stream()
                .collect(Collectors.toMap(r -> (String) r.get("account"), r -> ((Number) r.get("net")).longValue()));
    }

    @Test
    void secondPostWithSameMeaningIsAlreadyPosted() {
        long sellerId = SELLER_IDS.incrementAndGet();
        long paymentId = SettlementDbFixture.payment(jdbc, sellerId, 10_000);

        assertThat(postLocked(paymentId, saleDraft(paymentId, sellerId, 10_000)))
                .isEqualTo(LedgerPosting.POSTED);
        assertThat(postLocked(paymentId, saleDraft(paymentId, sellerId, 10_000)))
                .isEqualTo(LedgerPosting.ALREADY_POSTED);
        assertThat(headers(sellerId)).isEqualTo(1);
    }

    @Test
    void samePaymentWithDifferentAmountsConflicts() {
        long sellerId = SELLER_IDS.incrementAndGet();
        long paymentId = SettlementDbFixture.payment(jdbc, sellerId, 10_000);
        postLocked(paymentId, saleDraft(paymentId, sellerId, 10_000));

        assertThatThrownBy(() -> postLocked(paymentId, saleDraft(paymentId, sellerId, 20_000)))
                .isInstanceOf(LedgerConflictException.class);
        assertThat(ledger.findByKey(LedgerSourceType.SALE, paymentId, LedgerAction.POST)
                        .orElseThrow()
                        .lines()
                        .get(0)
                        .debit())
                .isEqualTo(10_000);
    }

    @Test
    void postWithoutCallerTransactionFails() {
        long sellerId = SELLER_IDS.incrementAndGet();
        long paymentId = SettlementDbFixture.payment(jdbc, sellerId, 10_000);

        assertThatThrownBy(() -> ledgerTransactions.post(saleDraft(paymentId, sellerId, 10_000)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(headers(sellerId)).isZero();
    }

    @Test
    void concurrentPostsUnderPaymentLockWriteOnce() throws Exception {
        long sellerId = SELLER_IDS.incrementAndGet();
        long paymentId = SettlementDbFixture.payment(jdbc, sellerId, 10_000);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<LedgerPosting>> results = List.of(
                    pool.submit(() -> {
                        start.await();
                        return postLocked(paymentId, saleDraft(paymentId, sellerId, 10_000));
                    }),
                    pool.submit(() -> {
                        start.await();
                        return postLocked(paymentId, saleDraft(paymentId, sellerId, 10_000));
                    }));
            start.countDown();

            assertThat(List.of(
                            results.get(0).get(10, TimeUnit.SECONDS),
                            results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(LedgerPosting.POSTED, LedgerPosting.ALREADY_POSTED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(headers(sellerId)).isEqualTo(1);
    }

    @Test
    void callerRollbackLeavesNoHeaderOrLines() {
        long sellerId = SELLER_IDS.incrementAndGet();
        long paymentId = SettlementDbFixture.payment(jdbc, sellerId, 10_000);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    ledgerTransactions.post(saleDraft(paymentId, sellerId, 10_000));
                    throw new IllegalStateException("호출자 후속 처리 실패");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(headers(sellerId)).isZero();
        assertThat(balances(sellerId)).isEmpty();
    }

    @Test
    void refundBeforeStatementNetsEveryAccountToZero() {
        long sellerId = SELLER_IDS.incrementAndGet();
        long paymentId = SettlementDbFixture.payment(jdbc, sellerId, 10_000);
        long refundId = SettlementDbFixture.refund(jdbc, paymentId, 10_000);
        postLocked(paymentId, saleDraft(paymentId, sellerId, 10_000));
        LedgerTransaction original = ledger.findByKey(LedgerSourceType.SALE, paymentId, LedgerAction.POST)
                .orElseThrow();

        postLocked(paymentId, LedgerJournals.reverse(original, refundId, APPROVED.plusDays(1)));

        assertThat(balances(sellerId))
                .isNotEmpty()
                .allSatisfy((account, net) -> assertThat(net).as(account).isZero());
    }

    /**
     * 발행 뒤 환불: 판매1(N=8,680) 지급 → 판매1 환불로 채권 8,680 → 판매2(N=17,360) 정산서에서 8,680 상계 → 나머지 8,680 지급.
     * 판매자에게 줄 돈·받을 돈이 모두 0이 되고 실제 지급 합은 17,360이다.
     */
    @Test
    void refundAfterStatementBalancesThroughOffsetAndPayout() {
        long sellerId = SELLER_IDS.incrementAndGet();
        long payment1 = SettlementDbFixture.payment(jdbc, sellerId, 10_000);
        long payment2 = SettlementDbFixture.payment(jdbc, sellerId, 20_000);
        long refundId = SettlementDbFixture.refund(jdbc, payment1, 10_000);
        postLocked(payment1, saleDraft(payment1, sellerId, 10_000));
        postLocked(payment2, saleDraft(payment2, sellerId, 20_000));
        tx.executeWithoutResult(status -> {
            ledgerTransactions.post(LedgerJournals.payoutDone(1L, 11L, sellerId, 8_680, APPROVED.plusDays(5)));
            ledgerTransactions.post(LedgerJournals.receivable(
                    SettlementDbFixture.sale(rules, payment1, sellerId, 10_000), refundId, APPROVED.plusDays(6)));
            ledgerTransactions.post(LedgerJournals.offset(12L, sellerId, 8_680, APPROVED.plusDays(8)));
            ledgerTransactions.post(LedgerJournals.payoutDone(2L, 12L, sellerId, 8_680, APPROVED.plusDays(8)));
        });

        Map<String, Long> net = balances(sellerId);
        assertThat(net.get("SELLER_PAYABLE")).isZero();
        assertThat(net.get("SELLER_RECEIVABLE")).isZero();
        assertThat(net.get("PAYOUT_CLEARING")).isEqualTo(-17_360);
        assertThat(net.values().stream().mapToLong(Long::longValue).sum()).isZero();
    }
}
