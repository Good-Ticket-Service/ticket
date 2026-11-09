package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ReconcileBatchIntegrationTest extends PaymentIntegrationTest {
    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private com.sparta.ticket.payment.infrastructure.observation.PaymentObservationTransactions observation;

    @Autowired
    @Qualifier("reconcileJob")
    private Job job;

    @Autowired
    private JobRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconcileBatchJdbcStore store;

    @Autowired
    private PlatformTransactionManager manager;

    @BeforeEach
    void seed() {
        jdbc.execute(
                "TRUNCATE p_payments,p_payouts,p_settlement_statements,p_pg_settlement_days,p_settlement_reconcile_runs,p_reconcile_checkpoint,p_reconcile_account_balances,batch_job_instance RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO p_reconcile_checkpoint(id) VALUES(1)");
        new TransactionTemplate(manager).executeWithoutResult(status -> ReconcilePerformanceTest.seed(jdbc, 3));
    }

    @Test
    void firstDailyBuildsFullThenDeltaMatchesFullAndAcceptedMarkerIsIdempotent() {
        var first = store.database(1, "DAILY");
        assertThat(first.valid()).isTrue();
        // Note: DB 세션 기본 시간대가 UTC여도 03시 슬롯과 기준 나이는 업무 시간대(KST)로 해석한다.
        assertThat(jdbc.queryForObject(
                        "SELECT abs(extract(epoch FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-started_at)))<30 FROM p_settlement_reconcile_runs WHERE id=?",
                        Boolean.class,
                        first.runId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT abs(extract(epoch FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')-accepted_at)))<30 FROM p_reconcile_checkpoint WHERE id=1",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT mode FROM p_settlement_reconcile_runs WHERE id=?", String.class, first.runId()))
                .isEqualTo("FULL");
        new TransactionTemplate(manager).executeWithoutResult(status -> ReconcilePerformanceTest.seedRange(jdbc, 3, 2));
        var daily = store.database(2, "DAILY");
        assertThat(daily.valid()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT delta_headers FROM p_settlement_reconcile_runs WHERE id=?", Long.class, daily.runId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(credit-debit) FROM p_reconcile_account_balances WHERE account='SELLER_PAYABLE'",
                        Long.class))
                .isEqualTo(5 * 8680);
        assertThat(store.database(2, "DAILY").runId()).isEqualTo(daily.runId());
        assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                .isEqualTo(2);
        var full = store.database(3, "FULL");
        assertThat(full.valid()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT cache_difference_count FROM p_settlement_reconcile_runs WHERE id=?",
                        Long.class,
                        full.runId()))
                .isZero();
    }

    @Test
    void missingSourceAndFinancialViolationDoNotAdvanceBaseline() {
        assertThat(store.database(1, "FULL").valid()).isTrue();
        jdbc.update("DELETE FROM p_settlement_items WHERE payment_id=1");
        var result = store.database(2, "DAILY");
        assertThat(result.valid()).isFalse();
        assertThat(result.technicalFailure()).isFalse();
        assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                .isEqualTo(1);
        assertThat(store.results(result.runId())).anySatisfy(check -> {
            assertThat(check.check()).isEqualTo("SALE_MISSING");
            assertThat(check.violations()).isEqualTo(1);
        });
    }

    @Test
    void sourceSaleWithoutAnyNewJournalIsStillDetectedByDaily() {
        assertThat(store.database(1, "FULL").valid()).isTrue();
        // Note: 시험 소유자의 유지보수 사고를 재현한다. 일반 DELETE 경로는 불변 트리거가 거부한다.
        jdbc.execute("TRUNCATE p_ledger_transactions CASCADE");
        var result = store.database(2, "DAILY");
        assertThat(result.valid()).isFalse();
        assertThat(store.results(result.runId())).anySatisfy(check -> {
            assertThat(check.check()).isEqualTo("SALE_JOURNAL");
            assertThat(check.violations()).isEqualTo(3);
        });
        assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                .isEqualTo(1);
    }

    @Test
    void operationsRequireMasterAndRejectConflictingCommandInput() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/settlements/reconciliation/executions/999")
                        .header("X-User-Id", "1")
                        .header("X-Username", "test")
                        .header("X-User-Role", "USER"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isForbidden());
        var command = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO p_reconcile_commands VALUES(?,'2026-11-09','FULL','original','USER:1',LOCALTIMESTAMP)",
                command);
        String body = "{\"businessDate\":\"2026-11-09\",\"mode\":\"FULL\",\"commandId\":\"" + command
                + "\",\"reason\":\"changed\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/admin/settlements/reconciliation/runs")
                        .header("X-User-Id", "1")
                        .header("X-Username", "test")
                        .header("X-User-Role", "MASTER")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isConflict());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM batch_job_instance WHERE job_name='reconcileJob'", Long.class))
                .isZero();
    }

    @Test
    void externalUnknownRestartsOnlyHttpWithAcceptedDatabaseBaseline() throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO p_settlement_statements(id,seller_id,schedule_id,sequence,cutoff,sale_total,offset_total,payable_amount,
                      status,issued_at,paid_at,created_at,created_by,updated_at,updated_by)
                    VALUES(1,2,4,0,LOCALTIMESTAMP,8680,0,8680,'PAID',LOCALTIMESTAMP,LOCALTIMESTAMP,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test')
                    """);
            jdbc.update("UPDATE p_settlement_items SET status='ON_STATEMENT',statement_id=1 WHERE payment_id=1");
            jdbc.update("""
                    INSERT INTO p_settlement_statement_lines(statement_id,item_id,kind,amount,created_at,created_by,updated_at,updated_by)
                    VALUES(1,1,'SALE',8680,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test')
                    """);
            jdbc.update("""
                    INSERT INTO p_payouts(id,statement_id,seller_id,amount,status,idempotency_key,bank_code,account_ref,holder_masked,
                      account_version,requested_at,external_payout_id,done_at,created_at,created_by,updated_at,updated_by)
                    VALUES(1,1,2,8680,'DONE','key-317','001','acct','test',0,LOCALTIMESTAMP,'po-317',LOCALTIMESTAMP,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test')
                    """);
            jdbc.update("""
                    INSERT INTO p_ledger_transactions(source_type,source_id,action,seller_id,statement_id,payout_id,occurred_at,created_at,created_by,updated_at,updated_by)
                    VALUES('PAYOUT',1,'DONE',2,1,1,LOCALTIMESTAMP,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test'),
                          ('PAYOUT_CASH',1,'POST',2,1,1,LOCALTIMESTAMP,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test')
                    """);
            jdbc.update("""
                    INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                    SELECT t.id,v.line_no,v.account,v.debit,v.credit,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test'
                    FROM p_ledger_transactions t CROSS JOIN LATERAL (
                      SELECT 1 AS line_no,'SELLER_PAYABLE' AS account,8680 AS debit,0 AS credit WHERE t.source_type='PAYOUT'
                      UNION ALL SELECT 2,'PAYOUT_CLEARING',0,8680 WHERE t.source_type='PAYOUT'
                      UNION ALL SELECT 1,'PAYOUT_CLEARING',8680,0 WHERE t.source_type='PAYOUT_CASH'
                      UNION ALL SELECT 2,'CASH',0,8680 WHERE t.source_type='PAYOUT_CASH'
                    ) v WHERE t.source_type IN ('PAYOUT','PAYOUT_CASH')
                    """);
        });
        var unknown = new java.util.concurrent.atomic.AtomicBoolean(true);
        var agency = new com.sparta.ticket.payment.application.port.settlement.PayoutAgency() {
            @Override
            public PayoutAgencyResult request(PayoutOrder order) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PayoutAgencyResult inquire(String key) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive())
                        .isFalse();
                return unknown.get()
                        ? new PayoutAgencyResult.Unknown("test")
                        : new PayoutAgencyResult.Observed(AgencyStatus.COMPLETED, "po-317", key, "acct", 8680L, null);
            }
        };
        var adapter = new SettlementReconcileJdbcAdapter(jdbc, java.time.Duration.ofSeconds(10));
        var reconciliation = new com.sparta.ticket.payment.application.service.settlement.SettlementReconciliationJob(
                adapter, agency, paymentClock, "", 48, 500);
        var testJob = new com.sparta.ticket.payment.infrastructure.settlement.ReconcileBatchConfig()
                .reconcileJob(repository, manager, store, reconciliation);
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();
        var parameters = new JobParametersBuilder()
                .addString("businessDate", "2026-11-09")
                .addString("mode", "FULL")
                .addString("runKey", "HTTP_TEST")
                .toJobParameters();
        var first = launcher.run(testJob, parameters);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT provider_unreachable FROM p_settlement_reconcile_runs", Integer.class))
                .isEqualTo(1);
        assertThat(observation.read()).anySatisfy(sample -> {
            assertThat(sample.name()).isEqualTo("settlement.reconcile.provider.unreachable");
            assertThat(sample.value()).isEqualTo(1);
        });
        unknown.set(false);
        var resumed = launcher.run(testJob, parameters);
        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumed.getStepExecutions())
                .noneMatch(step -> step.getStepName().equals("reconcileDatabase"));
        assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_settlement_reconcile_runs", Long.class))
                .isEqualTo(1);
    }

    @Test
    void fullAuditsAndRebuildsCorruptDerivedCacheOnly() {
        assertThat(store.database(1, "FULL").valid()).isTrue();
        jdbc.update(
                "UPDATE p_reconcile_account_balances SET credit=credit+100 WHERE account='SELLER_PAYABLE' AND owner_id=2");
        var result = store.database(2, "FULL");
        assertThat(result.valid()).isTrue();
        var audit = jdbc.queryForMap(
                "SELECT baseline_reason,cache_difference_count,cache_difference_sample FROM p_settlement_reconcile_runs WHERE id=?",
                result.runId());
        assertThat(audit.get("baseline_reason")).isEqualTo("FULL_REBASELINE");
        assertThat(audit.get("cache_difference_count")).isEqualTo(1L);
        assertThat(audit.get("cache_difference_sample").toString()).contains("credit_difference", "100");
        assertThat(jdbc.queryForObject("SELECT sum(credit-debit) FROM p_reconcile_account_balances", Long.class))
                .isEqualTo(3 * 8680);
    }

    @Test
    void incompleteSqlIsAuditedWithoutAdvancingBaseline() {
        assertThat(store.database(1, "FULL").valid()).isTrue();
        jdbc.execute("ALTER TABLE p_refunds RENAME TO p_refunds_unavailable");
        try {
            var result = store.database(2, "DAILY");
            assertThat(result.valid()).isFalse();
            assertThat(result.technicalFailure()).isTrue();
            assertThat(store.results(result.runId())).anyMatch(check -> !check.completed());
            assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                    .isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE p_refunds_unavailable RENAME TO p_refunds");
        }
    }

    @Test
    void immutableLedgerRejectsMutationAndOldHeaderEntryInsert() {
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE p_ledger_entries SET credit=credit+1 WHERE account='SELLER_PAYABLE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM p_ledger_transactions WHERE id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO p_ledger_entries(transaction_id,line_no,account,debit,credit,created_at,created_by,updated_at,updated_by)
                VALUES(1,9,'CASH',1,0,LOCALTIMESTAMP,'test',LOCALTIMESTAMP,'test')
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoLaunchersShareOneScheduledJobInstance() throws Exception {
        var parameters = new JobParametersBuilder()
                .addString("businessDate", "2026-11-09")
                .addString("mode", "DAILY")
                .addString("runKey", "SCHEDULED")
                .toJobParameters();
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> launch = () -> {
                var launcher = new TaskExecutorJobLauncher();
                launcher.setJobRepository(repository);
                launcher.afterPropertiesSet();
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    return launcher.run(job, parameters).getStatus() == BatchStatus.COMPLETED;
                } catch (org.springframework.batch.core.JobExecutionException
                        | org.springframework.dao.DataAccessException expected) {
                    return false;
                }
            };
            var first = pool.submit(launch);
            var second = pool.submit(launch);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM batch_job_instance WHERE job_name='reconcileJob'", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM p_settlement_reconcile_runs WHERE db_valid", Long.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void competingModesCannotOverwriteAcceptedGeneration() throws Exception {
        var baseline = store.database(1, "FULL");
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<String, Boolean> accept = mode -> {
                var tx = new TransactionTemplate(manager);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                try {
                    return tx.execute(status -> {
                        var incremental = new ReconcileIncrementalJdbc(jdbc);
                        var state = incremental.prepare(mode);
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        incremental.accept(state, baseline.runId());
                        return true;
                    });
                } catch (org.springframework.dao.ConcurrencyFailureException expected) {
                    return false;
                }
            };
            var first = pool.submit(() -> accept.apply("DAILY"));
            var second = pool.submit(() -> accept.apply("FULL"));
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT generation FROM p_reconcile_checkpoint", Long.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(credit-debit) FROM p_reconcile_account_balances", Long.class))
                    .isEqualTo(3 * 8680);
        } finally {
            pool.shutdownNow();
        }
    }
}
