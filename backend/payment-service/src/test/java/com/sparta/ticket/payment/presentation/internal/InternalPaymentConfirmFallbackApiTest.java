package com.sparta.ticket.payment.presentation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalService;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** #422 HTTP 승인의 메시지 대체 승인(confirmBefore 있음). 메시지 승인과 같은 시한·지문·PG별 자리로 처리한다. */
class InternalPaymentConfirmFallbackApiTest extends PaymentIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(42_200_000);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentService service;

    @Autowired
    private MessageApprovalService messages;

    @Autowired
    private PaymentRepository payments;

    private Payment ready(PgProvider provider) {
        String order = "fallback-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                order,
                IDS.incrementAndGet(),
                1L,
                2L,
                10L,
                20L,
                provider,
                10000,
                1000,
                LocalDateTime.now(paymentClock).plusMinutes(1)));
        return payments.findByOrderId(order).orElseThrow();
    }

    /** 예매 Outbox 메시지의 confirmBefore. 선점 만료 + 3분(선점 만료 + 5분 이내). */
    private static LocalDateTime before(Payment payment) {
        return payment.getExpiresAt().plusMinutes(3);
    }

    private static String token(Payment payment) {
        return "token-" + payment.getOrderId();
    }

    private ResultActions confirm(Payment payment, String token, int amount, String confirmBefore) throws Exception {
        return mvc.perform(post("/internal/payments/{orderId}/confirm", payment.getOrderId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pgAuthToken\":\"" + token + "\",\"amount\":" + amount + ",\"confirmBefore\":\""
                        + confirmBefore + "\"}"));
    }

    private ResultActions confirm(Payment payment) throws Exception {
        return confirm(payment, token(payment), payment.getAmount(), kst(before(payment)));
    }

    private static String kst(LocalDateTime value) {
        return value.atZone(SEOUL).toOffsetDateTime().toString();
    }

    private MessageConfirmCommand message(Payment payment) {
        return new MessageConfirmCommand(
                payment.getOrderId(), payment.getReservationId(), payment.getAmount(), before(payment), token(payment));
    }

    private Payment load(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(PgProvider.class)
    void afterHoldExpiryBeforeConfirmBeforeApprovesOnceAndLaterSameMessageIsAlreadyDone(PgProvider provider)
            throws Exception {
        var payment = ready(provider);
        paymentClock.advance(Duration.ofSeconds(90));

        confirm(payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        var done = load(payment);
        assertThat(done.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(done.getMessageConfirmFingerprint())
                .isEqualTo(message(payment).fingerprint());
        assertThat(messages.advance(message(payment), () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        confirm(payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void noPgPermitIsServiceUnavailableWithRetryAfterAndPaymentStaysReady() throws Exception {
        var payment = ready(PgProvider.TOSS);
        pg.approvalAllowed(false);

        confirm(payment)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        var waiting = load(payment);
        assertThat(waiting.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(waiting.getMessageConfirmBefore()).isNotNull();
        assertThat(pg.approveCalls()).isZero();

        pg.approvalAllowed(true);
        confirm(payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void anotherOffsetForTheSameInstantIsTheSameRequest() throws Exception {
        var payment = ready(PgProvider.KAKAO);
        pg.approvalAllowed(false);
        String utc = before(payment)
                .atZone(SEOUL)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toOffsetDateTime()
                .toString();
        confirm(payment, token(payment), payment.getAmount(), utc).andExpect(status().isServiceUnavailable());

        pg.approvalAllowed(true);
        assertThat(messages.advance(message(payment), () -> true)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void differentFingerprintFromRegisteredMessageIsConflict() throws Exception {
        var payment = ready(PgProvider.TOSS);
        pg.approvalAllowed(false);
        assertThat(messages.advance(
                        new com.sparta.ticket.payment.application.service.payment.MessageApprovalAttempt(
                                message(payment)),
                        () -> true,
                        () -> {},
                        true))
                .isEqualTo(MessageApprovalService.Outcome.DEFER);
        pg.approvalAllowed(true);

        confirm(payment, "other-token", payment.getAmount(), kst(before(payment)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_CONFLICT"));
        confirm(
                        payment,
                        token(payment),
                        payment.getAmount(),
                        kst(before(payment).plusSeconds(1)))
                .andExpect(status().isConflict());
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void confirmBeforeLaterThanHoldExpiryPlusFiveMinutesIsConflict() throws Exception {
        var payment = ready(PgProvider.TOSS);
        confirm(
                        payment,
                        token(payment),
                        payment.getAmount(),
                        kst(payment.getExpiresAt().plusMinutes(5).plusSeconds(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_CONFLICT"));
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void passedConfirmBeforeFailsAsExpiredWithoutPg() throws Exception {
        var payment = ready(PgProvider.TOSS);
        paymentClock.advance(Duration.ofMinutes(5));

        confirm(payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("EXPIRED"));
        assertThat(pg.approveCalls()).isZero();
    }

    @Test
    void amountMismatchIsBadRequest() throws Exception {
        var payment = ready(PgProvider.TOSS);
        confirm(payment, token(payment), payment.getAmount() + 1, kst(before(payment)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(load(payment).getMessageConfirmBefore()).isNull();
    }

    @Test
    void alreadyHttpApprovedPaymentReturnsCurrentStateWithoutPg() throws Exception {
        var payment = ready(PgProvider.TOSS);
        service.confirm(
                payment.getOrderId(),
                new com.sparta.ticket.payment.application.dto.payment.ConfirmCommand(
                        token(payment), payment.getAmount()));

        confirm(payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(pg.approveCalls()).isEqualTo(1);
    }

    @Test
    void concurrentFallbackAndMessageCallPgOnce() throws Exception {
        var payment = ready(PgProvider.TOSS);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pg.approveReturns(request -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new PgResult.Approved(request.transactionId(), request.amount(), LocalDateTime.now(paymentClock));
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> http =
                    () -> confirm(payment).andReturn().getResponse().getStatus();
            var first = pool.submit(http);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> messages.advance(message(payment), () -> true));
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(MessageApprovalService.Outcome.COMPLETE);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(load(payment).getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(pg.approveCalls()).isEqualTo(1);
    }
}
