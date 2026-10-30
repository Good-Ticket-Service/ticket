package com.sparta.ticket.payment.support;

import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** 정산 통합 시험이 실제 준비·승인 흐름으로 결제를 만들고, 결제별 원장을 확인하는 도우미. 정가 50,000·할인 5,000. */
public final class SettlementPaymentFlow {

    public static final LocalDateTime SCHEDULE_START_AT = LocalDateTime.of(2026, 11, 7, 19, 0);
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(9_500_000);

    private final PaymentService service;
    private final PaymentTransactions transactions;
    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SettlementPaymentFlow(
            PaymentService service,
            PaymentTransactions transactions,
            PaymentRepository payments,
            JdbcTemplate jdbc,
            Clock clock) {
        this.service = service;
        this.transactions = transactions;
        this.payments = payments;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Payment inProgress() {
        return inProgress(2L, 20L);
    }

    /** 판매자·회차를 시험마다 나눠, 판매자·회차 단위 집계가 다른 시험 데이터와 섞이지 않게 한다. */
    public Payment inProgress(long sellerId, long scheduleId) {
        String orderId = "settle-flow-" + UUID.randomUUID();
        service.checkout(new CheckoutCommand(
                orderId,
                RESERVATION_IDS.incrementAndGet(),
                1L,
                sellerId,
                10L,
                scheduleId,
                PgProvider.TOSS,
                50_000,
                5_000,
                LocalDateTime.now(clock).plusMinutes(10),
                SCHEDULE_START_AT));
        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        transactions.requestConfirm(orderId, new ConfirmCommand("key-" + payment.getId(), 45_000));
        return transactions
                .markInProgress(payment.getId(), "key-" + payment.getId())
                .orElseThrow();
    }

    public Payment approvedFor(long sellerId, long scheduleId) {
        Payment payment = inProgress(sellerId, scheduleId);
        transactions.complete(
                payment.getId(), new PgResult.Approved("key-" + payment.getId(), 45_000, LocalDateTime.now(clock)));
        return payments.findById(payment.getId()).orElseThrow();
    }

    public Payment approved(LocalDateTime approvedAt) {
        Payment payment = inProgress();
        transactions.complete(payment.getId(), new PgResult.Approved("key-" + payment.getId(), 45_000, approvedAt));
        return payments.findById(payment.getId()).orElseThrow();
    }

    public Payment approved() {
        return approved(LocalDateTime.now(clock));
    }

    /** 포착이 꺼져 있던 것처럼 결제의 정산 기록을 지운다. */
    public void dropSettlement(Long paymentId) {
        jdbc.update(
                "DELETE FROM p_ledger_entries WHERE transaction_id IN"
                        + " (SELECT id FROM p_ledger_transactions WHERE payment_id = ?)",
                paymentId);
        jdbc.update(
                "DELETE FROM p_ledger_transactions WHERE payment_id = ? AND reverses_transaction_id IS NOT NULL",
                paymentId);
        jdbc.update("DELETE FROM p_ledger_transactions WHERE payment_id = ?", paymentId);
        jdbc.update("DELETE FROM p_settlement_items WHERE payment_id = ?", paymentId);
    }

    public int ledgerTransactions(Long paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM p_ledger_transactions WHERE payment_id = ?", Integer.class, paymentId);
    }

    /** 결제 관련 분개의 계정별 차변 − 대변. */
    public Map<String, Long> balances(Long paymentId) {
        return jdbc
                .queryForList(
                        "SELECT e.account, SUM(e.debit - e.credit) AS net FROM p_ledger_entries e"
                                + " JOIN p_ledger_transactions t ON t.id = e.transaction_id WHERE t.payment_id = ?"
                                + " GROUP BY e.account",
                        paymentId)
                .stream()
                .collect(Collectors.toMap(r -> (String) r.get("account"), r -> ((Number) r.get("net")).longValue()));
    }
}
