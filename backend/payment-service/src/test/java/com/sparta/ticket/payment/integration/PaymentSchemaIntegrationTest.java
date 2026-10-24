package com.sparta.ticket.payment.integration;

import static com.sparta.ticket.payment.PaymentFixture.NOW;
import static com.sparta.ticket.payment.PaymentFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.payment.domain.Payment;
import com.sparta.ticket.payment.domain.PaymentFailureCode;
import com.sparta.ticket.payment.domain.PaymentRepository;
import com.sparta.ticket.payment.domain.PaymentStatus;
import com.sparta.ticket.payment.domain.Refund;
import com.sparta.ticket.payment.domain.RefundReason;
import com.sparta.ticket.payment.domain.RefundRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** schema.sql의 유니크·부분 유니크·CHECK 제약이 설계(docs/payment/plan.md 7절)대로 동작하는지 실제 PostgreSQL로 확인한다. */
class PaymentSchemaIntegrationTest extends PostgresIntegrationTest {

    /** 컨테이너를 테스트 클래스끼리 공유하므로 예매 ID가 겹치지 않게 큰 값에서 시작한다. */
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(1_000_000);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static long newReservationId() {
        return RESERVATION_IDS.incrementAndGet();
    }

    private static String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    private Payment saveReady(long reservationId) {
        return paymentRepository.saveAndFlush(Payment.ready(order(newOrderId(), reservationId), 5_000));
    }

    @Test
    @DisplayName("같은 예매에 살아 있는 결제(READY)는 두 건이 될 수 없다")
    void oneLivePaymentPerReservation() {
        long reservationId = newReservationId();
        saveReady(reservationId);

        assertThatThrownBy(() -> saveReady(reservationId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("끝난 결제(FAILED)가 있어도 같은 예매에 새 준비를 만들 수 있다")
    void failedPaymentDoesNotBlockNewCheckout() {
        long reservationId = newReservationId();
        Payment first = saveReady(reservationId);
        first.fail(PaymentFailureCode.SUPERSEDED, null);
        paymentRepository.saveAndFlush(first);

        Payment second = saveReady(reservationId);

        assertThat(second.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("같은 주문번호로 결제를 두 번 만들 수 없다")
    void uniqueOrderId() {
        String orderId = newOrderId();
        paymentRepository.saveAndFlush(Payment.ready(order(orderId, newReservationId()), 0));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(Payment.ready(order(orderId, newReservationId()), 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 PG 거래 ID(paymentKey·tid)는 두 결제에 쓸 수 없다")
    void uniquePgTransactionId() {
        Payment first = saveReady(newReservationId());
        first.startApproval("same-key", NOW, Duration.ofSeconds(30));
        paymentRepository.saveAndFlush(first);

        Payment second = saveReady(newReservationId());
        second.startApproval("same-key", NOW, Duration.ofSeconds(30));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 결제에 환불 행은 하나뿐이다")
    void oneRefundPerPayment() {
        Payment payment = saveReady(newReservationId());
        refundRepository.saveAndFlush(Refund.requested(payment, RefundReason.USER_CANCEL, 45_000, NOW, NOW));

        assertThatThrownBy(() -> refundRepository.saveAndFlush(
                        Refund.requested(payment, RefundReason.AUTO_VOID, 45_000, NOW, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("금액이 0이거나 정가 - 할인과 다르면 DB가 거절한다")
    void amountCheck() {
        assertThatThrownBy(() -> insertPaymentRow(1_000, 1_000, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPaymentRow(1_000, 100, 800)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPaymentRow(1_000, -1, 1_001))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("행을 잠그고 읽은 결제의 상태 전이가 커밋된다")
    void lockAndTransition() {
        Payment saved = saveReady(newReservationId());

        transactionTemplate.executeWithoutResult(status -> {
            Payment locked = paymentRepository.lockById(saved.getId()).orElseThrow();
            locked.startApproval("lock-key-" + saved.getId(), NOW, Duration.ofSeconds(30));
        });

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(reloaded.getRecovery().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(refundRepository.findByPaymentId(saved.getId())).isEmpty();
    }

    private void insertPaymentRow(int totalPrice, int discountAmount, int amount) {
        jdbcTemplate.update("""
                INSERT INTO p_payments (order_id, reservation_id, user_id, seller_id, performance_id, schedule_id,
                    pg_provider, expires_at, total_price, discount_amount, amount, status,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), 1, 1, 'TOSS', now(), ?, ?, ?, 'READY',
                    now(), 'test', now(), 'test')
                """, newOrderId(), newReservationId(), totalPrice, discountAmount, amount);
    }
}
