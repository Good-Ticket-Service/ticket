package com.sparta.ticket.payment.application.service.pgsettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementClient;
import com.sparta.ticket.payment.application.service.settlement.LedgerTransactions;
import com.sparta.ticket.payment.domain.model.settlement.LedgerJournals;
import com.sparta.ticket.payment.domain.model.settlement.SettlementRules;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementReconcileRepository;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementBatchConfig;
import com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementHttpClient;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import com.sparta.ticket.payment.support.SettlementDbFixture;
import com.sparta.ticket.payment.support.pg.FakePgContainer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** 실제 FakePG HTTP·PostgreSQL·Batch 메타데이터·원장을 함께 검증한다. */
class PgSettlementIntegrationTest extends PaymentIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private static final LocalDateTime APPROVED = LocalDateTime.of(2026, 10, 19, 10, 0);
    private static final long SELLER = 316001;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JobRepository repository;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private PgSettlementTransactions transactions;

    @Autowired
    private PgSettlementProperties properties;

    @Autowired
    private SettlementRules settlementRules;

    @Autowired
    private LedgerTransactions ledger;

    @Autowired
    private SettlementReconcileRepository checks;

    @Autowired
    private org.springframework.batch.core.explore.JobExplorer explorer;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private com.sparta.ticket.payment.infrastructure.pgsettlement.PgSettlementMetrics metrics;

    private JdbcTemplate fake;
    private PgSettlementClient client;
    private TaskExecutorJobLauncher launcher;
    private final String key = "pg316-" + UUID.randomUUID();

    @BeforeEach
    void prepare() throws Exception {
        cleanup();
        fake = FakePgContainer.jdbc();
        fake.update("DELETE FROM pg_transaction WHERE order_id LIKE 'pg316-%'");
        fault(false, 0, 0, 0);
        String base = FakePgContainer.baseUrl();
        client = new PgSettlementHttpClient(
                new PgProperties(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        new PgProperties.Toss(base, "test_sk_fake_toss"),
                        new PgProperties.Kakao(
                                base,
                                "DEV_fake_kakao",
                                "TC0ONETIME",
                                "http://localhost/ok",
                                "http://localhost/cancel",
                                "http://localhost/fail")),
                RestClient.builder());
        launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();
    }

    @AfterEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE p_payments,p_settlement_statements,p_payouts,p_pg_settlement_days,batch_job_instance CASCADE");
    }

    private Job job(PgSettlementClient source, int pageSize) {
        var config = new PgSettlementProperties(properties.fees(), false, properties.startDate(), pageSize, 1);
        return new PgSettlementBatchConfig()
                .pgSettlementJob(repository, manager, source, transactions, config, paymentClock, metrics);
    }

    private JobParameters parameters(LocalDate day) {
        return new JobParametersBuilder()
                .addString("provider", "TOSS")
                .addString("settlementDate", day.toString())
                .toJobParameters();
    }

    private void run(LocalDate day) throws Exception {
        var execution = launcher.run(job(client, 2000), parameters(day));
        assertThat(execution.getAllFailureExceptions()).isEmpty();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private long payment(String paymentKey, int amount, LocalDateTime approved, boolean posted) {
        long id = SettlementDbFixture.payment(jdbc, SELLER, amount);
        jdbc.update(
                "UPDATE p_payments SET pg_transaction_id=?,order_id=?,approved_at=?,pg_approved_at=?,pg_approved_amount=? WHERE id=?",
                paymentKey,
                paymentKey,
                approved,
                approved,
                amount,
                id);
        fake.update("""
                INSERT INTO pg_transaction(provider,payment_key,order_id,order_name,amount,status,auth_expires_at,
                  requested_at,approved_at,updated_at) VALUES('TOSS',?,?,'정산 시험',?,'DONE',?,?,?,?)
                """, paymentKey, paymentKey, amount, approved.plusMinutes(10), approved, approved, approved);
        if (posted) postSale(id, amount, approved);
        return id;
    }

    private void postSale(long id, int amount, LocalDateTime at) {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM p_payments WHERE id=? FOR UPDATE", Long.class, id);
            ledger.post(LedgerJournals.sale(SettlementDbFixture.sale(settlementRules, id, SELLER, amount), at));
        });
    }

    private void fault(boolean hidden, long amountDelta, long feeDelta, int dateShift) {
        RestClient.create(FakePgContainer.baseUrl())
                .put()
                .uri("/fake/admin/faults/settlements/TOSS")
                .body(Map.of(
                        "paymentKey",
                        key,
                        "hidden",
                        hidden,
                        "amountDelta",
                        amountDelta,
                        "feeDelta",
                        feeDelta,
                        "dateShiftDays",
                        dateShift))
                .retrieve()
                .toBodilessEntity();
    }

    private long balance(String account) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(debit-credit),0) FROM p_ledger_entries WHERE account=?", Long.class, account);
    }

    private void pgChecksClean() {
        assertThat(checks.runChecks(null).stream().filter(c -> c.check().startsWith("PG_")))
                .allSatisfy(c -> {
                    assertThat(c.completed()).isTrue();
                    assertThat(c.violations()).isZero();
                });
    }

    @Test
    void normalDayClearsReceivableAndRecordsActualFee() throws Exception {
        payment(key, 10000, APPROVED, true);
        run(DAY);
        assertThat(balance("CASH")).isEqualTo(9648);
        assertThat(balance("PG_CLEARING")).isZero();
        assertThat(balance("PG_FEE_EXPENSE")).isEqualTo(352);
        assertThat(jdbc.queryForObject(
                        "SELECT finding FROM p_pg_reconcile_results WHERE provider='TOSS' AND line_key=?",
                        String.class,
                        key + ":APPROVAL"))
                .isEqualTo("MATCHED");
        pgChecksClean();
        assertThatThrownBy(() -> launcher.run(job(client, 2000), parameters(DAY)))
                .isInstanceOf(org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void feeMismatchDoesNotLeaveConfirmedPrincipalInSuspense() throws Exception {
        payment(key, 10000, APPROVED, true);
        fault(false, 0, 50, 0);
        run(DAY);
        assertThat(balance("CASH")).isEqualTo(9598);
        assertThat(balance("PG_FEE_EXPENSE")).isEqualTo(402);
        assertThat(balance("PG_CLEARING")).isZero();
        assertThat(balance("PG_SUSPENSE")).isZero();
        assertThat(jdbc.queryForObject("SELECT finding FROM p_pg_reconcile_results", String.class))
                .isEqualTo("FEE_MISMATCH");
        pgChecksClean();
    }

    @Test
    void amountMismatchKeepsUnexplainedReceiptInSuspense() throws Exception {
        payment(key, 10000, APPROVED, true);
        fault(false, 100, 0, 0);
        run(DAY);
        assertThat(balance("PG_CLEARING")).isEqualTo(10000);
        assertThat(balance("PG_SUSPENSE")).isEqualTo(-10100);
        assertThat(jdbc.queryForObject("SELECT finding FROM p_pg_reconcile_results", String.class))
                .isEqualTo("AMOUNT_MISMATCH");
        pgChecksClean();
    }

    @Test
    void missingSaleIsAdjustedAfterPostingWithoutChangingOriginalJournal() throws Exception {
        long id = payment(key, 10000, APPROVED, false);
        run(DAY);
        assertThat(balance("PG_SUSPENSE")).isEqualTo(-10000);
        postSale(id, 10000, APPROVED);
        run(DAY.plusDays(1));
        assertThat(balance("PG_SUSPENSE")).isZero();
        assertThat(balance("PG_CLEARING")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='PG_ADJUST'", Long.class))
                .isEqualTo(1);
        pgChecksClean();
    }

    @Test
    void hiddenTransactionIsDetectedEvenWhenProviderTotalsAlsoHideIt() throws Exception {
        payment(key, 10000, APPROVED, true);
        fault(true, 0, 0, 0);
        run(DAY);
        run(DAY.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT finding FROM p_pg_reconcile_results", String.class))
                .isEqualTo("ONLY_IN_OURS");
        assertThat(balance("CASH")).isZero();
        assertThat(balance("PG_CLEARING")).isEqualTo(10000);
    }

    @Test
    void dateMismatchMatchesAcrossDaysWithoutPuttingPrincipalInSuspense() throws Exception {
        payment(key, 10000, APPROVED, true);
        fault(false, 0, 0, 1);
        run(DAY.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT finding FROM p_pg_reconcile_results", String.class))
                .isEqualTo("DATE_MISMATCH");
        assertThat(balance("PG_CLEARING")).isZero();
    }

    @Test
    void interruptedHttpReadRestartsFromAtomicallyCommittedCursor() throws Exception {
        payment(key + "a", 10000, APPROVED, true);
        payment(key + "b", 10000, APPROVED, true);
        payment(key + "c", 10000, APPROVED, true);
        var calls = new AtomicInteger();
        PgSettlementClient interrupted = (provider, date, cursor, size, snapshot) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("페이지 응답 중단");
            return client.read(provider, date, cursor, size, snapshot);
        };
        var first = launcher.run(job(interrupted, 1), parameters(DAY));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class))
                .isEqualTo(1);
        assertThat(balance("CASH")).isZero();
        var resumed = launcher.run(job(client, 1), parameters(DAY));
        assertThat(resumed.getAllFailureExceptions()).isEmpty();
        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class))
                .isEqualTo(3);
        assertThat(balance("CASH")).isEqualTo(28944);
        pgChecksClean();
    }

    @Test
    void concurrentSameDateCanHaveOnlyOneExecution() throws Exception {
        payment(key, 10000, APPROVED, true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        PgSettlementClient held = (provider, date, cursor, size, snapshot) -> {
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("시험 시간 초과");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return client.read(provider, date, cursor, size, snapshot);
        };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> launcher.run(job(held, 2000), parameters(DAY)));
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> launcher.run(job(client, 2000), parameters(DAY)))
                    .isInstanceOf(org.springframework.batch.core.repository.JobExecutionAlreadyRunningException.class);
            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(balance("CASH")).isEqualTo(9648);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void refund(long paymentId, String paymentKey, int amount, LocalDateTime at, boolean postReverse) {
        long refundId = SettlementDbFixture.refund(jdbc, paymentId, amount);
        jdbc.update(
                "UPDATE p_refunds SET refunded_at=?,pg_cancel_id=? WHERE id=?", at, paymentKey + ":CANCEL", refundId);
        fake.update(
                "UPDATE pg_transaction SET status='CANCELED',canceled_at=?,canceled_amount=?,cancel_transaction_key=? WHERE payment_key=?",
                at,
                amount,
                paymentKey + ":CANCEL",
                paymentKey);
        if (postReverse)
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var original = jdbc.queryForObject(
                        "SELECT id FROM p_ledger_transactions WHERE source_type='SALE' AND source_id=?",
                        Long.class,
                        paymentId);
                var sale = LedgerJournals.sale(
                        SettlementDbFixture.sale(settlementRules, paymentId, SELLER, amount), APPROVED);
                // 원거래의 반대와 동일한 금액을 발행 뒤 환불 규칙으로 기록해 PG 취소 미상계 잔액을 만든다.
                ledger.post(LedgerJournals.receivable(
                        SettlementDbFixture.sale(settlementRules, paymentId, SELLER, amount), refundId, at));
            });
    }

    @Test
    void negativeDayCarriesDebtAndNextReceiptSettlesIt() throws Exception {
        long id = payment(key, 10000, APPROVED, true);
        refund(id, key, 10000, LocalDateTime.of(2026, 10, 22, 10, 0), true);
        payment(key + "next", 20000, LocalDateTime.of(2026, 10, 23, 10, 0), true);
        run(DAY);
        run(LocalDate.of(2026, 10, 26));
        assertThat(balance("PG_PAYABLE")).isEqualTo(-9648);
        assertThat(balance("PG_FEE_EXPENSE")).isZero();
        run(LocalDate.of(2026, 10, 27));
        assertThat(balance("PG_PAYABLE")).isZero();
        assertThat(balance("PG_CLEARING")).isZero();
        assertThat(balance("CASH")).isEqualTo(19296);
        assertThat(balance("PG_FEE_EXPENSE")).isEqualTo(704);
        pgChecksClean();
    }

    @Test
    void autoVoidRetainsBothEventsWithoutInventingASale() throws Exception {
        long id = payment(key, 10000, APPROVED, false);
        jdbc.update("UPDATE p_payments SET status='FAILED',approved_at=NULL WHERE id=?", id);
        refund(id, key, 10000, APPROVED.plusDays(1), false);
        jdbc.update("UPDATE p_refunds SET reason='AUTO_VOID' WHERE payment_id=?", id);
        run(DAY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_pg_reconcile_results WHERE finding<>'MATCHED'", Long.class))
                .isZero();
        assertThat(balance("CASH")).isZero();
        assertThat(balance("PG_SUSPENSE")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='SALE'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines WHERE adjusted", Long.class))
                .isEqualTo(2);
        pgChecksClean();
    }

    @Test
    void changedSnapshotFailsBeforeMixedPagesCanBePosted() throws Exception {
        payment(key, 10000, APPROVED, true);
        payment(key + "z", 10000, APPROVED, true);
        var calls = new AtomicInteger();
        PgSettlementClient interrupted = (provider, date, cursor, size, snapshot) -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("중단");
            return client.read(provider, date, cursor, size, snapshot);
        };
        assertThat(launcher.run(job(interrupted, 1), parameters(DAY)).getStatus())
                .isEqualTo(BatchStatus.FAILED);
        fault(false, 0, 1, 0);
        assertThat(launcher.run(job(client, 1), parameters(DAY)).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(balance("CASH")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class))
                .isEqualTo(1);
    }

    @Test
    void administratorCanSeeBookCashAndOnlyAcceptFeeOrDateExceptionsWithAudit() throws Exception {
        payment(key, 10000, APPROVED, true);
        fault(false, 0, 50, 0);
        run(DAY);
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/admin/settlements/pg/balances");
        mockMvc.perform(get.header("X-User-Id", "1")
                        .header("X-Username", "master")
                        .header("X-User-Role", "MASTER"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.bookCash")
                        .value(9598))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.data.bankVerified")
                        .value(false));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/settlements/pg/balances")
                        .header("X-User-Id", "2")
                        .header("X-Username", "user")
                        .header("X-User-Role", "USER"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isForbidden());
        long id = jdbc.queryForObject("SELECT id FROM p_pg_reconcile_results", Long.class);
        transactions.resolve(id, "PG 계약 확인 후 실제 청구액 수용", "USER:1");
        assertThat(jdbc.queryForObject("SELECT status FROM p_pg_reconcile_results WHERE id=?", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                        "SELECT resolution_history->0->>'actor' FROM p_pg_reconcile_results WHERE id=?",
                        String.class,
                        id))
                .isEqualTo("USER:1");
        assertThat(balance("PG_FEE_EXPENSE")).isEqualTo(402);
    }

    @Test
    void payoutCashBridgeReducesBookBalanceOnce() throws Exception {
        payment(key, 10000, APPROVED, true);
        run(DAY);
        long statement = SettlementDbFixture.statement(jdbc, SELLER, 4, 8000, 0);
        long payout = SettlementDbFixture.payout(jdbc, statement, SELLER, 8000);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM p_payouts WHERE id=? FOR UPDATE", Long.class, payout);
            var journal = LedgerJournals.payoutCash(payout, statement, SELLER, 8000, APPROVED);
            ledger.post(journal);
            ledger.post(journal);
        });
        assertThat(transactions.balances().bookCash()).isEqualTo(1648);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='PAYOUT_CASH'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void checkpointFailureRollsBackPageAndDoesNotPersistAnAdvancedInMemoryCursor() throws Exception {
        payment(key + "a", 10000, APPROVED, true);
        payment(key + "b", 10000, APPROVED, true);
        jdbc.execute("""
                CREATE FUNCTION pg316_fail_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF EXISTS(SELECT 1 FROM p_pg_settlement_lines) THEN RAISE EXCEPTION 'checkpoint failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        jdbc.execute(
                "CREATE TRIGGER pg316_checkpoint BEFORE UPDATE ON batch_step_execution_context FOR EACH ROW EXECUTE FUNCTION pg316_fail_checkpoint()");
        org.springframework.batch.core.JobExecution failed;
        try {
            failed = launcher.run(job(client, 1), parameters(DAY));
        } finally {
            jdbc.execute("DROP TRIGGER pg316_checkpoint ON batch_step_execution_context");
            jdbc.execute("DROP FUNCTION pg316_fail_checkpoint()");
        }
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_pg_settlement_lines", Long.class))
                .isZero();
        var saved = explorer.getJobExecution(failed.getId())
                .getStepExecutions()
                .iterator()
                .next()
                .getExecutionContext();
        assertThat(saved.getString("cursor", "")).isEmpty();
        assertThat(launcher.run(job(client, 1), parameters(DAY)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(balance("CASH")).isEqualTo(19296);
    }

    @Test
    void postingFailureRollsBackJournalAndRestartsOnlyTheAccountingStep() throws Exception {
        payment(key, 10000, APPROVED, true);
        jdbc.execute(
                "CREATE FUNCTION pg316_fail_post() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'posting failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER pg316_post BEFORE UPDATE OF status ON p_pg_settlement_days FOR EACH ROW WHEN (NEW.status='POSTED') EXECUTE FUNCTION pg316_fail_post()");
        try {
            assertThat(launcher.run(job(client, 2000), parameters(DAY)).getStatus())
                    .isEqualTo(BatchStatus.FAILED);
        } finally {
            jdbc.execute("DROP TRIGGER pg316_post ON p_pg_settlement_days");
            jdbc.execute("DROP FUNCTION pg316_fail_post()");
        }
        assertThat(balance("CASH")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='PG_SETTLEMENT'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM p_pg_settlement_days", String.class))
                .isEqualTo("MATCHED");
        run(DAY);
        assertThat(balance("CASH")).isEqualTo(9648);
        pgChecksClean();
    }

    @Test
    void migrationConnectsExistingPayoutToCashExactlyOnce() {
        long statement = SettlementDbFixture.statement(jdbc, SELLER, 4, 8000, 0);
        long payout = SettlementDbFixture.payout(jdbc, statement, SELLER, 8000);
        jdbc.update("""
                UPDATE p_payouts SET status='DONE',idempotency_key='pg316-payout-'||id,bank_code='088',account_ref='test',
                  holder_masked='test',account_version=1,requested_at=LOCALTIMESTAMP,
                  external_payout_id='pg316-payout-'||id,done_at=LOCALTIMESTAMP WHERE id=?
                """, payout);
        jdbc.update("UPDATE p_settlement_statements SET status='PAID',paid_at=LOCALTIMESTAMP WHERE id=?", statement);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> ledger.post(LedgerJournals.payoutDone(payout, statement, SELLER, 8000, APPROVED)));
        var migration = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.FileSystemResource(
                        "../sql/payment-service/migrations/017-pg-settlement.sql"));
        migration.setSqlScriptEncoding("UTF-8");
        migration.execute(jdbc.getDataSource());
        migration.execute(jdbc.getDataSource());
        assertThat(balance("CASH")).isEqualTo(-8000);
        assertThat(balance("PAYOUT_CLEARING")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM p_ledger_transactions WHERE source_type='PAYOUT_CASH'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void acceptingFeeDifferenceSurvivesLaterSuspenseReclassification() throws Exception {
        long payment = payment(key, 10000, APPROVED, false);
        fault(false, 0, 50, 0);
        run(DAY);
        long result = jdbc.queryForObject("SELECT id FROM p_pg_reconcile_results", Long.class);
        transactions.resolve(result, "실제 PG 청구액 수용", "USER:1");
        postSale(payment, 10000, APPROVED);
        run(DAY.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT status FROM p_pg_reconcile_results WHERE id=?", String.class, result))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                        "SELECT resolved_by FROM p_pg_reconcile_results WHERE id=?", String.class, result))
                .isEqualTo("USER:1");
        assertThat(balance("PG_SUSPENSE")).isZero();
        assertThat(balance("PG_CLEARING")).isZero();
        pgChecksClean();
    }
}
