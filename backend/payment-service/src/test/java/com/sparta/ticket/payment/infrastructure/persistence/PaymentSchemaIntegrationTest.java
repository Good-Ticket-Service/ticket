package com.sparta.ticket.payment.infrastructure.persistence;

import static com.sparta.ticket.payment.support.PaymentFixture.NOW;
import static com.sparta.ticket.payment.support.PaymentFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
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
        return paymentRepository.saveAndFlush(Payment.ready(order(newOrderId(), reservationId)));
    }

    @Test
    @DisplayName("DB에서 다시 읽은 결제도 같은 준비 요청과 일치한다 (나노초까지 있는 만료 시각)")
    void reloadedPaymentMatchesSameOrder() {
        PaymentOrder order =
                order(newOrderId(), newReservationId(), NOW.plusMinutes(10).plusNanos(123_456_789));
        paymentRepository.saveAndFlush(Payment.ready(order));

        Payment reloaded = paymentRepository.findByOrderId(order.orderId()).orElseThrow();

        assertThat(reloaded.matches(order)).isTrue();
        assertThat(reloaded.getDiscountAmount()).isEqualTo(order.discountAmount());
        assertThat(reloaded.getAmount()).isEqualTo(order.totalPrice() - order.discountAmount());
        PaymentOrder changedDiscount = new PaymentOrder(
                order.orderId(),
                order.reservationId(),
                order.userId(),
                order.sellerId(),
                order.performanceId(),
                order.scheduleId(),
                order.pgProvider(),
                order.totalPrice(),
                order.discountAmount() + 1,
                order.expiresAt());
        assertThat(reloaded.matches(changedDiscount)).isFalse();
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
        paymentRepository.saveAndFlush(Payment.ready(order(orderId, newReservationId())));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(Payment.ready(order(orderId, newReservationId()))))
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
    @DisplayName("금액이 100원 미만이거나 정가 - 할인과 다르면 DB가 거절한다")
    void amountCheck() {
        assertThatThrownBy(() -> insertPaymentRow(1_000, 901, 99)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPaymentRow(1_000, 1_000, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPaymentRow(1_000, 100, 800)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPaymentRow(1_000, -1, 1_001))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB도 최소 결제 금액 100원과 쿠폰 미사용을 허용한다")
    void acceptsMinimumAmount() {
        insertPaymentRow(1_000, 900, 100);
        insertPaymentRow(100, 0, 100);
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
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(refundRepository.findByPaymentId(saved.getId())).isEmpty();
    }

    private void insertPaymentRow(int totalPrice, int discountAmount, int amount) {
        jdbcTemplate.update("""
                INSERT INTO p_payments (order_id, reservation_id, user_id, seller_id, performance_id, schedule_id,
                    pg_provider, expires_at, total_price, discount_amount, amount, status,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 1, 2, 1, 1, 'TOSS', now(), ?, ?, ?, 'READY',
                    now(), 'test', now(), 'test')
                """, newOrderId(), newReservationId(), totalPrice, discountAmount, amount);
    }
}
