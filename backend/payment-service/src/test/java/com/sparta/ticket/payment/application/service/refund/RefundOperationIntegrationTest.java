package com.sparta.ticket.payment.application.service.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.refund.RefundOperation;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefundOperationIntegrationTest extends PaymentIntegrationTest {
    @Autowired
    private MockMvc mvc;

    private String commandBody(RefundOperation.Request command) {
        return """
                {"commandId":"%s","reason":"%s","expectedStatus":"%s","expectedMode":"%s",
                 "expectedVersion":%d,"expectedAttempt":%d,"actor":"FORGED","action":"RETRY"}
                """.formatted(
                        command.commandId(),
                        command.reason(),
                        command.expectedStatus(),
                        command.expectedMode(),
                        command.expectedVersion(),
                        command.expectedAttempt());
    }

    @Test
    void adminApiRequiresIdentityAndRole() throws Exception {
        mvc.perform(get("/api/admin/payments/refunds"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        for (String role : new String[] {"USER", "SELLER"}) {
            mvc.perform(post("/api/admin/payments/order/refund/retry")
                            .header(UserHeaders.USER_ID, "42")
                            .header(UserHeaders.USERNAME, "user@ticket.com")
                            .header(UserHeaders.ROLE, role)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_refund_operation_commands", Long.class))
                .isZero();
    }

    @Test
    void adminApiRecordsVerifiedActorAndReplaysCommand() throws Exception {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RESUME, RefundOperation.Reason.RESUME_OBSERVATION);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/admin/payments/{orderId}/refund/resume", p.getOrderId())
                            .header(UserHeaders.USER_ID, "42")
                            .header(UserHeaders.USERNAME, "master@ticket.com")
                            .header(UserHeaders.ROLE, "MASTER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commandBody(command)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.result").value("APPLIED"))
                    .andExpect(jsonPath("$.data.mode").value("VERIFY_ONLY"));
        }
        assertThat(jdbc.queryForObject("SELECT actor FROM p_refund_operation_commands", String.class))
                .isEqualTo("USER:42");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_refund_operation_commands", Long.class))
                .isEqualTo(1);
        mvc.perform(post("/api/admin/payments/{orderId}/refund/resume", p.getOrderId())
                        .header(UserHeaders.USER_ID, "43")
                        .header(UserHeaders.USERNAME, "other-master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody(command)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_COMMAND_CONFLICT"));
        mvc.perform(get("/api/admin/payments/{orderId}/refund", p.getOrderId())
                        .header(UserHeaders.USER_ID, "42")
                        .header(UserHeaders.USERNAME, "master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commands[0].actor").value("USER:42"))
                .andExpect(jsonPath("$.data.pgObservation").value("NOT_QUERIED"));
    }

    @Test
    void adminApiConflictIsRecordedBeforeHttpError() throws Exception {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        tx.executeWithoutResult(s -> {
            payments.lockById(p.getId()).orElseThrow();
            refunds.lockByPaymentId(p.getId()).orElseThrow().resumeVerification(now());
        });
        mvc.perform(post("/api/admin/payments/{orderId}/refund/retry", p.getOrderId())
                        .header(UserHeaders.USER_ID, "42")
                        .header(UserHeaders.USERNAME, "master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody(command)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(jdbc.queryForObject("SELECT result FROM p_refund_operation_commands", String.class))
                .isEqualTo("STALE");
    }

    @Test
    void adminApiUnknownPgDoesNotRetryAndPersistsResult() throws Exception {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        int attempt = refund(p).getCancelAttempt();
        pg.inquireReturns(q -> new PgInquiry.Unknown("PG_DOWN"));
        mvc.perform(post("/api/admin/payments/{orderId}/refund/retry", p.getOrderId())
                        .header(UserHeaders.USER_ID, "42")
                        .header(UserHeaders.USERNAME, "master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody(command)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        assertThat(refund(p).getCancelAttempt()).isEqualTo(attempt);
        assertThat(jdbc.queryForObject("SELECT result FROM p_refund_operation_commands", String.class))
                .isEqualTo("PG_UNKNOWN");
    }

    @Test
    void adminApiValidatesInputAndUsesCommonErrors() throws Exception {
        mvc.perform(get("/api/admin/payments/refunds")
                        .param("limit", "101")
                        .header(UserHeaders.USER_ID, "42")
                        .header(UserHeaders.USERNAME, "master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/admin/payments/order/refund/resume")
                        .header(UserHeaders.USER_ID, "42")
                        .header(UserHeaders.USERNAME, "master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get("/api/admin/payments/nonexistent/refund")
                        .header(UserHeaders.USER_ID, "42")
                        .header(UserHeaders.USERNAME, "master@ticket.com")
                        .header(UserHeaders.ROLE, "MASTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p_refund_operation_commands", Long.class))
                .isZero();
    }

    @Autowired
    private RefundOperationService operations;

    @Autowired
    private RefundOperationTransactions reads;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private RefundTransactions refundTransactions;

    @Autowired
    private RefundRecoveryJob job;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void isolateOperations() {
        jdbc.update("DELETE FROM p_refund_operation_commands");
        jdbc.update("DELETE FROM p_ledger_entries");
        jdbc.update("DELETE FROM p_ledger_transactions");
        jdbc.update("DELETE FROM p_settlement_statement_lines");
        jdbc.update("DELETE FROM p_payout_operation_commands");
        jdbc.update("DELETE FROM p_payouts");
        jdbc.update("DELETE FROM p_settlement_items");
        jdbc.update("DELETE FROM p_settlement_statements");
        jdbc.update("DELETE FROM p_refunds");
        jdbc.update("DELETE FROM p_payments");
        pg.inquireReturns(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            Payment p = payments.findByOrderId(request.orderId()).orElseThrow();
            return new PgInquiry.Approved(p.getPgTransactionId(), 10_000, now());
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(paymentClock);
    }

    private Payment payment(boolean approve) {
        String order = "ops-" + UUID.randomUUID();
        paymentService.checkout(new CheckoutCommand(
                order,
                Math.abs(UUID.randomUUID().getMostSignificantBits()),
                1L,
                2L,
                3L,
                4L,
                PgProvider.TOSS,
                10_000,
                0,
                now().plusMinutes(10)));
        Payment p = payments.findByOrderId(order).orElseThrow();
        paymentTransactions.requestConfirm(order, new ConfirmCommand("key-" + p.getId(), 10_000));
        paymentTransactions.markInProgress(p.getId(), "key-" + p.getId());
        p = payments.findByOrderId(order).orElseThrow();
        if (approve)
            paymentTransactions.complete(p.getId(), new PgResult.Approved(p.getPgTransactionId(), 10_000, now()));
        return payments.findByOrderId(order).orElseThrow();
    }

    private Payment rejected(String code) {
        Payment p = payment(true);
        refundTransactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        var claim = refundTransactions.claim(p.getOrderId()).orElseThrow();
        refundTransactions.apply(claim, new PgResult.Rejected(code, "SECRET_MUST_NOT_APPEAR"));
        return p;
    }

    private Refund refund(Payment p) {
        return refunds.findByPaymentId(p.getId()).orElseThrow();
    }

    private RefundOperation.Request request(Payment p, RefundOperation.Action action, RefundOperation.Reason reason) {
        var r = refund(p);
        return new RefundOperation.Request(
                "cmd-" + UUID.randomUUID(),
                p.getOrderId(),
                action,
                reason,
                r.getStatus(),
                r.getRecoveryMode(),
                r.getExecutionVersion(),
                r.getCancelAttempt());
    }

    private void verifyOnly(Payment p, String reason) {
        tx.executeWithoutResult(status -> {
            payments.lockById(p.getId()).orElseThrow();
            refunds.lockByPaymentId(p.getId()).orElseThrow().verifyOnly(reason, now());
        });
    }

    @Test
    void retryOnlySchedulesAndReplaysWithoutPgCall() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var before = refund(p);
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        var first = operations.execute(command, "operator");
        assertThat(first.result()).isEqualTo(RefundOperation.Result.APPLIED);
        assertThat(refund(p).getCancelAttempt()).isEqualTo(1);
        assertThat(refund(p).getRequestedAt()).isEqualTo(before.getRequestedAt());
        assertThat(pg.cancelCalls()).isZero();
        int inquiries = pg.inquiryCalls();
        assertThat(operations.execute(command, "operator")).isEqualTo(first);
        assertThat(pg.inquiryCalls()).isEqualTo(inquiries);
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(reads.list(0, 20, null, null, null, null).items()).isEmpty();
        assertThat(reads.history(p.getOrderId()).get(0).actor()).isEqualTo("operator");
    }

    @Test
    void commandIdCannotBeReusedWithDifferentInputOrActor() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        operations.execute(command, "operator");
        assertThatThrownBy(() -> operations.execute(command, "other"))
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.REFUND_COMMAND_CONFLICT);
        var different = new RefundOperation.Request(
                command.commandId(),
                command.orderId(),
                command.action(),
                RefundOperation.Reason.ROOT_CAUSE_FIXED,
                command.expectedStatus(),
                command.expectedMode(),
                command.expectedVersion(),
                command.expectedAttempt());
        assertThatThrownBy(() -> operations.execute(different, "operator"))
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.REFUND_COMMAND_CONFLICT);
    }

    @Test
    void resumeFailedPreservesStateKeyAgeAndFailureReason() {
        Payment p = rejected("NOT_CANCELABLE_PAYMENT");
        var before = refund(p);
        pg.inquireReturns(r -> new PgInquiry.Unknown("SECRET"));
        var result = operations.execute(
                request(p, RefundOperation.Action.RESUME, RefundOperation.Reason.RESUME_OBSERVATION), "operator");
        assertThat(result.result()).isEqualTo(RefundOperation.Result.APPLIED);
        Refund after = refund(p);
        assertThat(after.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(after.getRecoveryMode()).isEqualTo(RefundRecoveryMode.VERIFY_ONLY);
        assertThat(after.getCancelAttempt()).isEqualTo(before.getCancelAttempt());
        assertThat(after.getCancelKeyCreatedAt()).isEqualTo(before.getCancelKeyCreatedAt());
        assertThat(after.getFailureCode()).isEqualTo("NOT_CANCELABLE_PAYMENT");
        job.runOnce();
        assertThat(pg.cancelCalls()).isZero();
    }

    @Test
    void mismatchFixedResumesSameKeyWithoutResettingAge() {
        Payment p = payment(true);
        refundTransactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        verifyOnly(p, "PG_INCONSISTENT_APPROVAL");
        var before = refund(p);
        var result = operations.execute(
                request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.ROOT_CAUSE_FIXED), "operator");
        assertThat(result.result()).isEqualTo(RefundOperation.Result.APPLIED);
        assertThat(refund(p).getCancelAttempt()).isEqualTo(before.getCancelAttempt());
        assertThat(refund(p).getCancelKeyCreatedAt()).isEqualTo(before.getCancelKeyCreatedAt());
        assertThat(refund(p).getRestrictionStartedAt()).isNull();
        assertThat(refund(p).getExecutionVersion()).isGreaterThan(before.getExecutionVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PG_PARTIAL_CANCELLATION", "PG_CANCEL_KEY_WINDOW_EXCEEDED", "PG_INCONSISTENT_CANCELLATION"})
    void unsupportedRestrictionCannotBeOverridden(String reason) {
        Payment p = payment(true);
        refundTransactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        verifyOnly(p, reason);
        var result = operations.execute(
                request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.ROOT_CAUSE_FIXED), "operator");
        assertThat(result.result()).isEqualTo(RefundOperation.Result.DENIED);
        assertThat(refund(p).getRecoveryMode()).isEqualTo(RefundRecoveryMode.VERIFY_ONLY);
        assertThat(pg.cancelCalls()).isZero();
    }

    @Test
    void unknownAndNotFoundDoNotCreateNewGeneration() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        pg.inquireReturns(r -> new PgInquiry.Unknown("SECRET"));
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        assertThat(operations.execute(command, "operator").result()).isEqualTo(RefundOperation.Result.PG_UNKNOWN);
        pg.inquireReturns(r -> new PgInquiry.NotFound());
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.PG_UNKNOWN);
        assertThat(refund(p).getCancelAttempt()).isZero();
    }

    /** #164: 업무 거절은 원인 해결 확인 + PG 승인 전액 그대로일 때만 새 키로 재시도하고, 부분 취소면 거절한다. */
    @Test
    void businessRejectionRetriesWithNewKeyOnlyWhenPgApprovalIsUntouched() {
        Payment p = rejected("CANCEL_DENIED");
        var retried = operations.execute(
                request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.ROOT_CAUSE_FIXED), "operator");
        assertThat(retried.result()).isEqualTo(RefundOperation.Result.APPLIED);
        pg.cancelReturns(r -> {
            assertThat(r.idempotencyKey()).endsWith(":cancel:1");
            return new PgResult.Approved("c", r.amount(), now());
        });
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);

        Payment partial = rejected("NOT_CANCELABLE_PAYMENT");
        pg.inquireReturns(r -> new PgInquiry.Inconsistent("PG_PARTIAL_CANCELLATION"));
        assertThat(operations
                        .execute(
                                request(partial, RefundOperation.Action.RETRY, RefundOperation.Reason.ROOT_CAUSE_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.DENIED);
        assertThat(refund(partial).getCancelAttempt()).isZero();
    }

    @Test
    void expiredKeyAndNonRetryableRejectionCannotCreateNewKey() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        paymentClock.advance(Duration.ofHours(24));
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.DENIED);
        assertThat(refund(p).getCancelAttempt()).isZero();
        p = rejected("NOT_CANCELABLE_PAYMENT");
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.DENIED);
    }

    @Test
    void validatedCancellationCompletesWithoutCancelCall() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        pg.inquireReturns(r -> new PgInquiry.Canceled(p.getPgTransactionId(), now(), 10_000, 0, "cancel"));
        assertThat(operations
                        .execute(
                                request(p, RefundOperation.Action.RESUME, RefundOperation.Reason.RESUME_OBSERVATION),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.CONFIRMED_CANCELED);
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(pg.cancelCalls()).isZero();
    }

    @Test
    void partialAndForeignCancellationCannotComplete() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        pg.inquireReturns(r -> new PgInquiry.Canceled("other", now(), 10_000, 0, "cancel"));
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.DENIED);
        pg.inquireReturns(r -> new PgInquiry.Canceled(p.getPgTransactionId(), now(), 5_000, 5_000, "cancel"));
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.DENIED);
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void concurrentSameCommandAppliesOnce() throws Exception {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        pg.inquireReturns(r -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new PgInquiry.Approved(p.getPgTransactionId(), 10_000, now());
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> operations.execute(command, "operator"));
            var b = pool.submit(() -> operations.execute(command, "operator"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
            assertThat(refund(p).getCancelAttempt()).isEqualTo(1);
            assertThat(reads.history(p.getOrderId())).hasSize(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void changedStateDuringPgInquiryIsNotOverwritten() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        pg.inquireReturns(r -> {
            verifyOnly(p, "STALE_CANCEL_SUCCESS_REQUIRES_VERIFICATION");
            return new PgInquiry.Approved(p.getPgTransactionId(), 10_000, now());
        });
        assertThat(operations.execute(command, "operator").result()).isEqualTo(RefundOperation.Result.STALE);
        assertThat(refund(p).getCancelAttempt()).isZero();
    }

    @Test
    void commandInsertFailureRollsBackRetry() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var command = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        jdbc.execute(
                "ALTER TABLE p_refund_operation_commands ADD CONSTRAINT test_reject_operation CHECK (result <> 'APPLIED')");
        try {
            assertThatThrownBy(() -> operations.execute(command, "operator"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.FAILED);
            assertThat(refund(p).getCancelAttempt()).isZero();
            assertThat(reads.history(p.getOrderId())).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE p_refund_operation_commands DROP CONSTRAINT test_reject_operation");
        }
    }

    @Test
    void listPaginatesAndWaitingTimestampIsNotInvented() {
        Payment a = payment(false);
        refundTransactions.accept(a.getOrderId(), RefundReason.USER_CANCEL);
        Payment b = rejected("UNAUTHORIZED_KEY");
        var page = reads.list(0, 1, null, null, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(reads.list(page.nextCursor(), 1, null, null, null, null)
                        .items()
                        .get(0)
                        .orderId())
                .isEqualTo(b.getOrderId());
        var view = reads.inspect(a.getOrderId());
        assertThat(view.requestedAt()).isNull();
        assertThat(view.guidance()).isEqualTo("WAITING_FOR_PAYMENT_RESULT");
        assertThat(reads.list(0, 10, null, RefundRecoveryMode.PAUSED, PgProvider.KAKAO, null)
                        .items())
                .isEmpty();
        assertThatThrownBy(() -> reads.list(0, 101, null, null, null, null))
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    void differentCommandIdsStillRequireOriginalVersion() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        var a = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        var b = request(p, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        assertThat(operations.execute(a, "operator").result()).isEqualTo(RefundOperation.Result.APPLIED);
        assertThat(operations.execute(b, "operator").result()).isEqualTo(RefundOperation.Result.STALE);
        assertThat(refund(p).getCancelAttempt()).isEqualTo(1);
        assertThat(reads.history(p.getOrderId())).hasSize(2);
    }

    @Test
    void sameCommandIdAcrossDifferentRefundsCannotOverwriteOriginalRecord() {
        Payment a = rejected("UNAUTHORIZED_KEY");
        Payment b = rejected("UNAUTHORIZED_KEY");
        var original = request(a, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        var other = request(b, RefundOperation.Action.RETRY, RefundOperation.Reason.AUTH_CONFIGURATION_FIXED);
        var collision = new RefundOperation.Request(
                original.commandId(),
                other.orderId(),
                other.action(),
                other.reason(),
                other.expectedStatus(),
                other.expectedMode(),
                other.expectedVersion(),
                other.expectedAttempt());
        operations.execute(original, "operator");
        assertThatThrownBy(() -> operations.execute(collision, "operator"))
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.REFUND_COMMAND_CONFLICT);
        assertThat(refund(b).getCancelAttempt()).isZero();
        assertThat(reads.history(a.getOrderId())).hasSize(1);
        assertThat(reads.history(b.getOrderId())).isEmpty();
    }

    @Test
    void firstRestrictionReasonAndTimeSurviveLaterInquiryErrors() {
        Payment p = payment(true);
        refundTransactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        verifyOnly(p, "PG_TRANSACTION_MISMATCH");
        var original = refund(p);
        paymentClock.advance(Duration.ofHours(1));
        pg.inquireReturns(r -> new PgInquiry.Unknown("SECRET"));
        job.runOnce();
        var current = reads.inspect(p.getOrderId());
        assertThat(current.restrictionStartedAt()).isEqualTo(original.getRestrictionStartedAt());
        assertThat(current.restrictionReason()).isEqualTo("PG_TRANSACTION_MISMATCH");
        assertThat(current.lastError()).isEqualTo("PG_CANCEL_INQUIRY_UNKNOWN");
        assertThat(current.automaticallyQueryable()).isTrue();
        assertThat(current.automaticCancelAllowed()).isFalse();
    }

    @Test
    void repairedAuthenticationCanRetryAfterReadOnlyResume() {
        Payment p = rejected("UNAUTHORIZED_KEY");
        operations.execute(
                request(p, RefundOperation.Action.RESUME, RefundOperation.Reason.RESUME_OBSERVATION), "operator");
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.APPLIED);
        assertThat(refund(p).getCancelAttempt()).isEqualTo(1);
    }

    @Test
    void lateSuccessEvidenceInvalidatesDefiniteRejectionForNewKeyRetry() {
        Payment p = payment(true);
        refundTransactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        var old = refundTransactions.claim(p.getOrderId()).orElseThrow();
        paymentClock.advance(Duration.ofSeconds(30));
        var current = refundTransactions.claim(p.getOrderId()).orElseThrow();
        refundTransactions.apply(current, new PgResult.Rejected("UNAUTHORIZED_KEY", "ignored"));
        refundTransactions.apply(old, new PgResult.Approved("c", 10_000, now()));
        assertThat(refund(p).getFailureCode()).isEqualTo("PG_CANCEL_RESULT_CONFLICT");
        assertThat(operations
                        .execute(
                                request(
                                        p,
                                        RefundOperation.Action.RETRY,
                                        RefundOperation.Reason.AUTH_CONFIGURATION_FIXED),
                                "operator")
                        .result())
                .isEqualTo(RefundOperation.Result.DENIED);
        assertThat(refund(p).getCancelAttempt()).isZero();
    }
}
