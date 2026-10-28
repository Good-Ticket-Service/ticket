package com.sparta.ticket.payment.application.service.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.refund.RefundClaim;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RefundIntegrationTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(9_000_000);

    @Autowired
    private PaymentService paymentsService;

    @Autowired
    private PaymentTransactions paymentTransactions;

    @Autowired
    private RefundService service;

    @Autowired
    private RefundTransactions transactions;

    @Autowired
    private RefundRecoveryJob job;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private RefundRepository refunds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void isolate() {
        jdbc.update("DELETE FROM p_refunds");
        jdbc.update("DELETE FROM p_payments");
        pg.inquireReturns(request -> {
            Payment payment = payments.findByOrderId(request.orderId()).orElseThrow();
            int amount = refunds.findByPaymentId(payment.getId())
                    .map(Refund::getRefundAmount)
                    .orElse(payment.getAmount());
            return new PgInquiry.Approved(payment.getPgTransactionId(), amount, now());
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(paymentClock);
    }

    private Payment ready() {
        String order = "refund-" + UUID.randomUUID();
        paymentsService.checkout(new CheckoutCommand(
                order, IDS.incrementAndGet(), 1L, 2L, 10L, 20L, PgProvider.TOSS, 50_000, 5_000, now().plusMinutes(10)));
        return payments.findByOrderId(order).orElseThrow();
    }

    private Payment progress() {
        Payment p = ready();
        paymentTransactions.requestConfirm(p.getOrderId(), new ConfirmCommand("key-" + p.getId(), 45_000));
        return paymentTransactions.markInProgress(p.getId(), "key-" + p.getId()).orElseThrow();
    }

    private Payment done() {
        Payment p = progress();
        paymentTransactions.complete(p.getId(), approved(p, 45_000));
        return p;
    }

    private PgResult.Approved approved(Payment p, int amount) {
        return new PgResult.Approved(p.getPgTransactionId(), amount, now());
    }

    private Refund refund(Payment p) {
        return refunds.findByPaymentId(p.getId()).orElseThrow();
    }

    private Payment load(Payment p) {
        return payments.findById(p.getId()).orElseThrow();
    }

    private RefundClaim claim(Payment p) {
        return transactions.claim(p.getOrderId()).orElseThrow();
    }

    @Test
    void readyAbandonedWithoutRefund() {
        Payment p = ready();
        assertThat(service.refund(p.getOrderId(), RefundReason.USER_CANCEL).refundStatus())
                .isEqualTo("NOT_REQUIRED");
        assertThat(load(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(refunds.findByPaymentId(p.getId())).isEmpty();
        assertThat(pg.cancelCalls()).isZero();
    }

    @Test
    void failedWithoutFunds() {
        Payment p = progress();
        paymentTransactions.complete(p.getId(), new PgResult.Rejected("DECLINED", "ignored"));
        assertThat(service.refund(p.getOrderId(), RefundReason.USER_CANCEL).refundStatus())
                .isEqualTo("NOT_REQUIRED");
    }

    @Test
    void pendingApprovalAndWaitingTransitions() {
        Payment p = progress();
        assertThat(service.refund(p.getOrderId(), RefundReason.USER_CANCEL).refundStatus())
                .isEqualTo("WAITING");
        paymentTransactions.complete(p.getId(), approved(p, 45_000));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(load(p).getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void waitingSkippedThenLateApprovalActuallyRefunded() {
        Payment p = progress();
        transactions.accept(p.getOrderId(), RefundReason.CONFIRM_FAILED);
        paymentTransactions.complete(p.getId(), new PgResult.Rejected("DECLINED", "ignored"));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.SKIPPED);
        paymentTransactions.complete(p.getId(), approved(p, 45_000));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(refund(p).getReason()).isEqualTo(RefundReason.CONFIRM_FAILED);
        assertThat(load(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_CANCEL", "SCHEDULE_CANCELED", "CONFIRM_FAILED"})
    void fullRefundAndReplay(String reason) {
        Payment p = done();
        pg.cancelReturns(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(request.idempotencyKey()).isEqualTo(p.getOrderId() + ":cancel:0");
            return new PgResult.Approved("cancel-id", request.amount(), now());
        });
        assertThat(service.refund(p.getOrderId(), RefundReason.valueOf(reason)).refundStatus())
                .isEqualTo("DONE");
        assertThat(service.refund(p.getOrderId(), RefundReason.USER_CANCEL).refundAmount())
                .isEqualTo(45_000);
        assertThat(pg.cancelCalls()).isEqualTo(1);
        assertThat(refund(p).getReason().name()).isEqualTo(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PG_BUSY", "PG_UNAVAILABLE"})
    void transientRejectedRetriesSameKey(String code) {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Rejected(code, "sensitive"));
        assertThat(service.refund(p.getOrderId(), RefundReason.USER_CANCEL).refundStatus())
                .isEqualTo("REQUESTED");
        assertThat(refund(p).isRecoveryBlocked()).isFalse();
        pg.cancelReturns(r -> {
            assertThat(r.idempotencyKey()).endsWith(":cancel:0");
            return new PgResult.Approved("c", r.amount(), now());
        });
        paymentClock.advance(Duration.ofSeconds(30));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
    }

    @Test
    void rejectAndAdminRetryNewGeneration() {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Rejected("CANCEL_DENIED", "secret"));
        service.refund(p.getOrderId(), RefundReason.USER_CANCEL);
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund(p).getFailureMessage()).isEqualTo("PG_CANCEL_REJECTED");
        transactions.retry(p.getOrderId());
        pg.cancelReturns(r -> {
            assertThat(r.idempotencyKey()).endsWith(":cancel:1");
            return new PgResult.Approved("c", r.amount(), now());
        });
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThatThrownBy(() -> transactions.retry(p.getOrderId()))
                .isInstanceOf(com.sparta.ticket.common.exception.BusinessException.class);
    }

    @Test
    void oldRejectionNeverOverwritesNewOwner() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        RefundClaim old = claim(p);
        paymentClock.advance(Duration.ofSeconds(30));
        RefundClaim current = claim(p);
        transactions.apply(old, new PgResult.Rejected("DENIED", "ignored"));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        transactions.apply(current, new PgResult.Approved("c", 45_000, now()));
        transactions.apply(old, new PgResult.Rejected("DENIED", "ignored"));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
    }

    @Test
    void oldSuccessRequiresOriginalTransactionVerification() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        RefundClaim old = claim(p);
        paymentClock.advance(Duration.ofSeconds(30));
        RefundClaim current = claim(p);
        transactions.apply(current, new PgResult.Rejected("DENIED", "ignored"));
        assertThat(transactions.apply(old, new PgResult.Approved("c", 45_000, now())))
                .isTrue();
        assertThat(refund(p).getLastError()).isEqualTo("STALE_CANCEL_SUCCESS_REQUIRES_VERIFICATION");
        transactions.confirmCanceled(old, new PgInquiry.Unknown("조회 장애"));
        assertThat(refund(p).getLastError()).isEqualTo("STALE_CANCEL_SUCCESS_REQUIRES_VERIFICATION");
        transactions.confirmCanceled(old, new PgInquiry.Canceled("other", now(), 45_000, 0, "c"));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.FAILED);
        transactions.confirmCanceled(old, new PgInquiry.Canceled(p.getPgTransactionId(), now(), 45_000, 0, "c"));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
    }

    @Test
    void slowBoundaryDoesNotBlockOrRequireUnblock() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        LocalDateTime requested = refund(p).getRequestedAt();
        paymentClock.advance(Duration.ofMinutes(60).minusNanos(1000));
        assertThat(claim(p).mode()).isEqualTo(com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode.ACTIVE);
        paymentClock.advance(Duration.ofSeconds(30));
        RefundClaim slow = claim(p);
        assertThat(slow.inquireFirst()).isTrue();
        assertThat(slow.mode()).isEqualTo(com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode.SLOW);
        pg.inquireReturns(r -> new PgInquiry.Unknown("lost"));
        service.execute(slow);
        assertThat(refund(p).isRecoveryBlocked()).isFalse();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund(p).getRequestedAt()).isEqualTo(requested);
        assertThat(refund(p).getNextAttemptAt()).isAfterOrEqualTo(now().plusSeconds(479));
        assertThatThrownBy(() -> transactions.unblock(p.getOrderId()))
                .isInstanceOf(com.sparta.ticket.common.exception.BusinessException.class);
    }

    @Test
    void committedIntentSurvivesBeforeFirstCall() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        claim(p);
        assertThat(pg.cancelCalls()).isZero();
        paymentClock.advance(Duration.ofSeconds(30));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
    }

    @Test
    void successSaveFailureLeavesRecoverableIntent() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        RefundClaim c = claim(p);
        publisher.fail = true;
        assertThatThrownBy(() -> transactions.apply(c, new PgResult.Approved("c", 45_000, now())))
                .isInstanceOf(RuntimeException.class);
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(load(p).getStatus()).isEqualTo(PaymentStatus.DONE);
        publisher.fail = false;
        paymentClock.advance(Duration.ofSeconds(30));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
    }

    @Test
    void invalidSuccessRemainsPending() {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Approved("c", 1, now()));
        assertThat(service.refund(p.getOrderId(), RefundReason.USER_CANCEL).refundStatus())
                .isEqualTo("REQUESTED");
        assertThat(load(p).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void autoVoidKeepsFailedAndNewAttemptUntouched() {
        Payment old = progress();
        paymentTransactions.complete(old.getId(), new PgResult.Rejected("DECLINED", "ignored"));
        String newOrder = "new-attempt-" + UUID.randomUUID();
        paymentsService.checkout(new CheckoutCommand(
                newOrder,
                old.getReservationId(),
                1L,
                2L,
                10L,
                20L,
                PgProvider.TOSS,
                50_000,
                5_000,
                now().plusMinutes(10)));
        paymentsService.confirm(newOrder, new ConfirmCommand("key-new-" + old.getId(), 45_000));
        Payment current = payments.findByOrderId(newOrder).orElseThrow();
        paymentTransactions.complete(old.getId(), approved(old, 43_000));
        job.runOnce();
        assertThat(refund(old).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(refund(old).getRefundAmount()).isEqualTo(43_000);
        assertThat(load(old).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(load(current).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void concurrentRequestsCreateAndExecuteOneRefund() throws Exception {
        Payment p = done();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                start.await();
                return service.refund(p.getOrderId(), RefundReason.USER_CANCEL);
            });
            var second = pool.submit(() -> {
                start.await();
                return service.refund(p.getOrderId(), RefundReason.SCHEDULE_CANCELED);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_refunds", Integer.class))
                    .isEqualTo(1);
            assertThat(pg.cancelCalls()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneClaimantAndDueWorkIsLimited() throws Exception {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> transactions.claimOne(new ArrayList<>(List.of(0L))));
            var b = pool.submit(() -> transactions.claimOne(new ArrayList<>(List.of(0L))));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).stream()
                            .filter(java.util.Optional::isPresent)
                            .count())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void internalHttpRawResponseAndReasonValidation() throws Exception {
        Payment p = ready();
        mvc.perform(post("/internal/payments/{orderId}/refund", p.getOrderId())
                        .contentType("application/json")
                        .content("{\"reason\":\"USER_CANCEL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/internal/payments/{orderId}/refund", p.getOrderId())
                        .contentType("application/json")
                        .content("{\"reason\":\"AUTO_VOID\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/payments/missing/refund")
                        .contentType("application/json")
                        .content("{\"reason\":\"USER_CANCEL\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"TOSS,true", "KAKAO,true", "TOSS,false", "KAKAO,false"})
    void actualHttpCancelResponseLossRecoversWithoutNewCancellation(String provider, boolean responseLost) {
        String base = com.sparta.ticket.payment.support.pg.FakePgContainer.baseUrl();
        var admin = new com.sparta.ticket.payment.support.pg.FakePgAdmin(base);
        var props = new com.sparta.ticket.payment.infrastructure.pg.PgProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                new com.sparta.ticket.payment.infrastructure.pg.PgProperties.Toss(base, "test_sk_fake_toss"),
                new com.sparta.ticket.payment.infrastructure.pg.PgProperties.Kakao(
                        base,
                        "DEV_fake_kakao",
                        "TC0ONETIME",
                        "http://localhost/approve",
                        "http://localhost/cancel",
                        "http://localhost/fail"));
        com.sparta.ticket.payment.application.port.pg.PgClient http = provider.equals("TOSS")
                ? new com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient(
                        org.springframework.web.client.RestClient.builder(),
                        props,
                        new com.fasterxml.jackson.databind.ObjectMapper())
                : new com.sparta.ticket.payment.infrastructure.pg.kakao.KakaoPgClient(
                        org.springframework.web.client.RestClient.builder(),
                        props,
                        new com.fasterxml.jackson.databind.ObjectMapper());
        admin.clearFaults();
        try {
            String order = "http-refund-" + UUID.randomUUID();
            pg.readyReturns(() -> http.ready(new com.sparta.ticket.payment.application.port.pg.PgClient.PgReadyRequest(
                    order, 1L, 45_000, "티켓")));
            paymentsService.checkout(new CheckoutCommand(
                    order,
                    IDS.incrementAndGet(),
                    1L,
                    2L,
                    10L,
                    20L,
                    PgProvider.valueOf(provider),
                    50_000,
                    5_000,
                    now().plusMinutes(10)));
            Payment p = payments.findByOrderId(order).orElseThrow();
            String token = provider.equals("TOSS")
                    ? admin.tossCheckout(order, 45_000)
                    : admin.kakaoUserApprove(p.getPgTransactionId());
            pg.approveReturns(http::approve);
            pg.cancelReturns(http::cancel);
            pg.inquireReturns(http::inquire);
            assertThat(paymentsService
                            .confirm(order, new ConfirmCommand(token, 45_000))
                            .status())
                    .isEqualTo(PaymentStatus.DONE);
            admin.fault(
                    provider,
                    "CANCEL",
                    responseLost ? "{\"responseLossRate\":1,\"holdMs\":3000}" : "{\"serverErrorRate\":1}");
            assertThat(service.refund(order, RefundReason.USER_CANCEL).refundStatus())
                    .isEqualTo("REQUESTED");
            assertThat(admin.transactionStatus(order)).isEqualTo(responseLost ? "CANCELED" : "DONE");
            admin.clearFaults();
            paymentClock.advance(Duration.ofHours(2));
            job.runOnce();
            assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
            assertThat(refund(p).getRefundAmount()).isEqualTo(45_000);
            assertThat(load(p).getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(refund(p).getCancelAttempt()).isZero();
        } finally {
            admin.clearFaults();
        }
    }

    @Test
    void refundAndApprovalRaceConverges() throws Exception {
        Payment p = progress();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                start.await();
                transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
                return true;
            });
            var b = pool.submit(() -> {
                start.await();
                paymentTransactions.complete(p.getId(), approved(p, 45_000));
                return true;
            });
            start.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
            job.runOnce();
            assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p_refunds", Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleOldGenerationRejectionAfterRetryIsIgnored() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        RefundClaim old = claim(p);
        paymentClock.advance(Duration.ofSeconds(30));
        RefundClaim current = claim(p);
        transactions.apply(current, new PgResult.Rejected("DECLINED", "ignore"));
        transactions.retry(p.getOrderId());
        RefundClaim next = claim(p);
        transactions.apply(old, new PgResult.Rejected("OLD_DENIED", "ignore"));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund(p).getCancelAttempt()).isEqualTo(1);
        transactions.apply(next, new PgResult.Approved("c", 45_000, now()));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
    }

    @Test
    void budgetDoesNotPreclaimRemainingWork() {
        Payment first = done();
        Payment second = done();
        transactions.accept(first.getOrderId(), RefundReason.USER_CANCEL);
        transactions.accept(second.getOrderId(), RefundReason.USER_CANCEL);
        var bounded = new RefundRecoveryJob(
                transactions,
                service,
                new RefundRecoveryProperties(50, 30, 300, 60, 1),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        pg.cancelReturns(r -> {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1100));
            return new PgResult.Unknown("slow");
        });
        bounded.runOnce();
        assertThat(pg.cancelCalls()).isEqualTo(1);
        assertThat(refund(second).getExecutionVersion()).isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1, 0, 1})
    void sixtyMinuteBoundary(long microseconds) {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        paymentClock.advance(Duration.ofMinutes(60).plusNanos(microseconds * 1000));
        RefundClaim c = claim(p);
        assertThat(c.mode().name()).isEqualTo(microseconds < 0 ? "ACTIVE" : "SLOW");
        assertThat(refund(p).isRecoveryBlocked()).isFalse();
    }

    @Test
    void twoHourOutageRecoversWithSameKeyAndOriginalAge() {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Unknown("outage"));
        service.refund(p.getOrderId(), RefundReason.USER_CANCEL);
        LocalDateTime requested = refund(p).getRequestedAt();
        paymentClock.advance(Duration.ofHours(2));
        pg.cancelReturns(r -> {
            assertThat(r.idempotencyKey()).endsWith(":cancel:0");
            return new PgResult.Approved("c", r.amount(), now());
        });
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(refund(p).getRequestedAt()).isEqualTo(requested);
        assertThat(refund(p).getCancelAttempt()).isZero();
    }

    @Test
    void unknownAndNotFoundOnlyQueryWithoutNewCancel() {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Unknown("outage"));
        service.refund(p.getOrderId(), RefundReason.USER_CANCEL);
        paymentClock.advance(Duration.ofHours(2));
        pg.inquireReturns(r -> new PgInquiry.NotFound());
        job.runOnce();
        assertThat(pg.cancelCalls()).isEqualTo(1);
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund(p).getNextAttemptAt()).isAfter(now().plusSeconds(479));
        paymentClock.advance(Duration.ofMinutes(31));
        pg.inquireReturns(r -> new PgInquiry.Unknown("outage"));
        job.runOnce();
        assertThat(pg.cancelCalls()).isEqualTo(1);
        assertThat(refund(p).getSlowAttempts()).isEqualTo(2);
        assertThat(refund(p).isRecoveryBlocked()).isFalse();
    }

    @Test
    void mismatchedSuccessNeverRetriesCancelEvenWithApprovedInquiry() {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Approved("c", 1, now()));
        service.refund(p.getOrderId(), RefundReason.USER_CANCEL);
        assertThat(refund(p).getRecoveryMode().name()).isEqualTo("VERIFY_ONLY");
        paymentClock.advance(Duration.ofMinutes(31));
        job.runOnce();
        assertThat(pg.cancelCalls()).isEqualTo(1);
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund(p).isRecoveryBlocked()).isFalse();
        assertThat(refund(p).getVerificationReason()).isEqualTo("PG_INCONSISTENT_CANCELLATION");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"identity", "amount", "partial"})
    void inconsistentInquiryForcesQueryOnly(String problem) {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        claim(p);
        paymentClock.advance(Duration.ofSeconds(30));
        pg.inquireReturns(r -> switch (problem) {
            case "identity" -> new PgInquiry.Approved("OTHER", 45_000, now());
            case "amount" -> new PgInquiry.Approved(p.getPgTransactionId(), 1, now());
            default -> new PgInquiry.Inconsistent("PG_PARTIAL_CANCELLATION");
        });
        job.runOnce();
        assertThat(refund(p).getRecoveryMode().name()).isEqualTo("VERIFY_ONLY");
        assertThat(pg.cancelCalls()).isZero();
        pg.inquireReturns(r -> new PgInquiry.Canceled(p.getPgTransactionId(), now(), 45_000, 0, "verified"));
        paymentClock.advance(Duration.ofMinutes(31));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(pg.cancelCalls()).isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1, 0, 1})
    void keyAgeBoundaryDoesNotRenewKey(long microseconds) {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        LocalDateTime original = refund(p).getCancelKeyCreatedAt();
        paymentClock.advance(Duration.ofHours(24).plusNanos(microseconds * 1000));
        job.runOnce();
        assertThat(pg.cancelCalls()).isEqualTo(microseconds < 0 ? 1 : 0);
        assertThat(refund(p).getCancelKeyCreatedAt()).isEqualTo(original);
        assertThat(refund(p).getCancelAttempt()).isZero();
        if (microseconds >= 0) assertThat(refund(p).getRecoveryMode().name()).isEqualTo("VERIFY_ONLY");
    }

    @Test
    void keyExpiredStillAcceptsVerifiedCancellation() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        paymentClock.advance(Duration.ofDays(2));
        pg.inquireReturns(r -> new PgInquiry.Canceled(p.getPgTransactionId(), now(), 45_000, 0, "verified"));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(pg.cancelCalls()).isZero();
    }

    @Test
    void pausedRejectionDoesNotRunAutomatically() {
        Payment p = done();
        pg.cancelReturns(r -> new PgResult.Rejected("CANCEL_DENIED", "ignored"));
        service.refund(p.getOrderId(), RefundReason.USER_CANCEL);
        paymentClock.advance(Duration.ofHours(2));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund(p).getRecoveryMode().name()).isEqualTo("PAUSED");
        assertThat(pg.inquiryCalls()).isZero();
    }

    @Test
    void oldOwnerCannotAllowCancelAfterNewOwnerSelectedVerificationOnly() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        RefundClaim old = claim(p);
        paymentClock.advance(Duration.ofSeconds(30));
        RefundClaim current = claim(p);
        transactions.applyInquiry(current, new PgInquiry.Inconsistent("partial"));
        assertThat(transactions.authorizeCancel(old)).isFalse();
        assertThat(transactions.authorizeCancel(current)).isFalse();
        transactions.apply(old, new PgResult.Rejected("OLD", "ignored"));
        assertThat(refund(p).getRecoveryMode().name()).isEqualTo("VERIFY_ONLY");
    }

    @Test
    void slowAndActiveWorkBothProgressWithOneSlotBudget() {
        Payment slow = done();
        transactions.accept(slow.getOrderId(), RefundReason.USER_CANCEL);
        jdbc.update(
                "UPDATE p_refunds SET recovery_mode='SLOW',slow_started_at=requested_at WHERE payment_id=?",
                slow.getId());
        Payment first = done();
        Payment second = done();
        transactions.accept(first.getOrderId(), RefundReason.USER_CANCEL);
        transactions.accept(second.getOrderId(), RefundReason.USER_CANCEL);
        pg.cancelReturns(r -> new PgResult.Unknown("pending"));
        pg.inquireReturns(r -> new PgInquiry.Unknown("pending"));
        var bounded = new RefundRecoveryJob(
                transactions,
                service,
                new RefundRecoveryProperties(1, 30, 300, 60, 15),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        bounded.runOnce();
        bounded.runOnce();
        assertThat(pg.cancelCalls()).isEqualTo(1);
        assertThat(pg.inquiryCalls()).isEqualTo(1);
        assertThat(refund(slow).getExecutionVersion()).isPositive();
    }

    @Test
    void staleSuccessLeavesDurableVerificationEvenForFailedRefund() {
        Payment p = done();
        transactions.accept(p.getOrderId(), RefundReason.USER_CANCEL);
        RefundClaim old = claim(p);
        paymentClock.advance(Duration.ofSeconds(30));
        RefundClaim current = claim(p);
        transactions.apply(current, new PgResult.Rejected("DENIED", "ignored"));
        transactions.apply(old, new PgResult.Approved("c", 45_000, now()));
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund(p).getRecoveryMode().name()).isEqualTo("VERIFY_ONLY");
        pg.inquireReturns(r -> new PgInquiry.Canceled(p.getPgTransactionId(), now(), 45_000, 0, "c"));
        paymentClock.advance(Duration.ofMinutes(31));
        job.runOnce();
        assertThat(refund(p).getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(pg.cancelCalls()).isZero();
    }
}
