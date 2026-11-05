package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 PostgreSQL에서 커밋 경계·PG 호출 시 저장 상태·중단 후 재개를 검증한다. */
class PaymentTransactionBoundaryIntegrationTest extends PaymentIntegrationTest {
    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentTransactions transactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private CheckoutCommand command(PgProvider provider) {
        return new CheckoutCommand(
                "boundary-" + UUID.randomUUID(),
                UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE,
                1L,
                2L,
                10L,
                20L,
                provider,
                50_000,
                5_000,
                LocalDateTime.now(paymentClock).plusMinutes(10));
    }

    @ParameterizedTest
    @EnumSource(PgProvider.class)
    void normalFlowCommitsOnlyExpectedTransactions(PgProvider provider) {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            CheckoutCommand command = command(provider);
            long before = statistics.getSuccessfulTransactionCount();
            service.checkout(command);
            // 최초 읽기 1회 + 쓰기: 토스1회, 카카오2회. 원장 MANDATORY는 별도 커밋하지 않는다.
            assertThat(statistics.getSuccessfulTransactionCount() - before)
                    .isEqualTo(provider == PgProvider.TOSS ? 2 : 3);
            before = statistics.getSuccessfulTransactionCount();
            assertThat(service.confirm(
                                    command.orderId(), new ConfirmCommand("boundary-key-" + command.orderId(), 45_000))
                            .status())
                    .isEqualTo(PaymentStatus.DONE);
            // 접수+실행권 1회, 결과 확정 1회, 최종 읽기 1회.
            assertThat(statistics.getSuccessfulTransactionCount() - before).isEqualTo(3);
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    @Test
    void approvalStartRollbackDoesNotRecordReceiptOrCallPg() {
        CheckoutCommand command = command(PgProvider.TOSS);
        service.checkout(command);
        pg.approvalAllowed(false);
        publisher.fail = true;
        assertThatThrownBy(() -> service.confirm(command.orderId(), new ConfirmCommand("rollback-key", 45_000)))
                .isInstanceOf(IllegalStateException.class);
        var stored = payments.findByOrderId(command.orderId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(stored.getConfirmRequestedAt()).isNull();
        assertThat(stored.getApproveRequestedAt()).isNull();
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void approvalCommitFailureNeverDispatchesPgAndRollsBackReceipt() {
        CheckoutCommand command = command(PgProvider.TOSS);
        service.checkout(command);
        // Note: SQL flush 성공 후 COMMIT에서만 실패하는 PostgreSQL 지연 제약 트리거다. 시험 DB에만 설치한다.
        jdbc.execute("""
                CREATE FUNCTION boundary_fail_approval_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected approval commit failure' USING ERRCODE = '23514';
                END $$
                """);
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER boundary_approval_commit AFTER UPDATE ON p_payments "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.order_id = '" + command.orderId()
                    + "' AND NEW.status = 'IN_PROGRESS') EXECUTE FUNCTION boundary_fail_approval_commit()");
            assertThatThrownBy(
                            () -> service.confirm(command.orderId(), new ConfirmCommand("commit-failure-key", 45_000)))
                    .isInstanceOf(RuntimeException.class);
            var stored = payments.findByOrderId(command.orderId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(stored.getConfirmRequestedAt()).isNull();
            assertThat(stored.getPgTransactionId()).isNull();
            assertThat(pg.approveCalls()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS boundary_approval_commit ON p_payments");
            jdbc.execute("DROP FUNCTION boundary_fail_approval_commit()");
        }
    }

    @Test
    void completeCommitFailureAnswersWithStoredStateNotUncommittedResult() {
        CheckoutCommand command = command(PgProvider.TOSS);
        service.checkout(command);
        // Note: 결과 확정 SQL은 성공하고 COMMIT에서만 실패시킨다. 커밋되지 않은 DONE으로 응답하면 안 된다.
        jdbc.execute("""
                CREATE FUNCTION boundary_fail_complete_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected complete commit failure' USING ERRCODE = '23514';
                END $$
                """);
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER boundary_complete_commit AFTER UPDATE ON p_payments "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.order_id = '" + command.orderId()
                    + "' AND NEW.status = 'DONE') EXECUTE FUNCTION boundary_fail_complete_commit()");
            var result = service.confirm(command.orderId(), new ConfirmCommand("complete-commit-key", 45_000));
            assertThat(result.status()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(payments.findByOrderId(command.orderId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(pg.approveCalls()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS boundary_complete_commit ON p_payments");
            jdbc.execute("DROP FUNCTION boundary_fail_complete_commit()");
        }
    }

    @Test
    void kakaoReadyRunsOutsideTransactionAfterInitialClaimCommit() {
        CheckoutCommand command = command(PgProvider.KAKAO);
        pg.readyReturns(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            var stored = payments.findByOrderId(command.orderId()).orElseThrow();
            assertThat(stored.getNextAttemptAt()).isNotNull();
            assertThat(stored.getPgTransactionId()).isNull();
            return new PgReadyResult.Ready("boundary-tid-" + command.orderId(), "https://checkout/boundary");
        });
        assertThat(service.checkout(command).checkoutUrl()).isEqualTo("https://checkout/boundary");
        assertThat(pg.readyCalls()).isEqualTo(1);
    }

    @Test
    void kakaoInitialClaimSurvivesStopAndCanBeResumedAfterLeaseExpiry() {
        CheckoutCommand command = command(PgProvider.KAKAO);
        var prepared = transactions.prepare(command.toOrder(), PaymentService.READY_LEASE);
        assertThat(prepared.pgReadyClaimed()).isTrue();
        var stored = payments.findByOrderId(command.orderId()).orElseThrow();
        assertThat(stored.getPgTransactionId()).isNull();
        assertThat(stored.getNextAttemptAt()).isNotNull();
        assertThat(transactions.claimPgReady(stored.getId(), PaymentService.READY_LEASE))
                .isFalse();
        assertThat(pg.readyCalls()).isZero();
        paymentClock.advance(PaymentService.READY_LEASE.plus(Duration.ofSeconds(1)));
        assertThat(service.checkout(command).checkoutUrl()).isNotNull();
        assertThat(pg.readyCalls()).isEqualTo(1);
        assertThat(payments.findByOrderId(command.orderId()).orElseThrow().getNextAttemptAt())
                .isNull();
    }
}
