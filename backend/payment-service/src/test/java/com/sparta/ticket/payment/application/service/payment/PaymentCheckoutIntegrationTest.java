package com.sparta.ticket.payment.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.CheckoutResult;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureType;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 준비 흐름(docs/api.md 결제 내부)을 실제 PostgreSQL로 검증한다. 유니크 제약·행 잠금이 운영과 같이 동작해야 동시 요청
 * 결과를 믿을 수 있다. PG 준비는 픽스처가 돌려준다.
 */
class PaymentCheckoutIntegrationTest extends PaymentIntegrationTest {

    /** 컨테이너를 다른 테스트 클래스와 공유하므로 예매 ID가 겹치지 않게 다른 범위에서 시작한다. */
    private static final AtomicLong RESERVATION_IDS = new AtomicLong(2_000_000);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static long newReservationId() {
        return RESERVATION_IDS.incrementAndGet();
    }

    private static String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    private static CheckoutCommand command(String orderId, long reservationId, PgProvider provider) {
        return command(orderId, reservationId, provider, 5_000);
    }

    private static CheckoutCommand command(String orderId, long reservationId, PgProvider provider, int discount) {
        return new CheckoutCommand(
                orderId,
                reservationId,
                1L,
                2L,
                10L,
                20L,
                provider,
                50_000,
                discount,
                LocalDateTime.now().plusMinutes(10));
    }

    private Payment load(String orderId) {
        return paymentRepository.findByOrderId(orderId).orElseThrow();
    }

    private int livePayments(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM p_payments WHERE reservation_id = ? AND status IN ('READY','IN_PROGRESS','DONE')",
                Integer.class,
                reservationId);
    }

    /** 테스트 준비용: 저장된 결제를 다른 상태로 옮긴다. */
    private void change(String orderId, java.util.function.Consumer<Payment> transition) {
        transactionTemplate.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            transition.accept(payment);
            paymentRepository.saveAndFlush(payment);
        });
    }

    private static void assertError(Runnable call, PaymentErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Nested
    @DisplayName("준비와 재현")
    class CheckoutAndReplay {

        @Test
        @DisplayName("토스는 PG 준비 없이 할인 후 금액으로 READY가 된다")
        void tossReady() {
            String orderId = newOrderId();

            CheckoutResult result = paymentService.checkout(command(orderId, newReservationId(), PgProvider.TOSS));

            assertThat(result.status()).isEqualTo(PaymentStatus.READY);
            assertThat(result.amount()).isEqualTo(45_000);
            assertThat(result.checkoutUrl()).isNull();
            assertThat(pg.readyCalls()).isZero();
        }

        @Test
        @DisplayName("카카오는 PG 준비를 불러 tid와 결제창 URL을 저장한다")
        void kakaoReady() {
            String orderId = newOrderId();

            CheckoutResult result = paymentService.checkout(command(orderId, newReservationId(), PgProvider.KAKAO));

            assertThat(result.status()).isEqualTo(PaymentStatus.READY);
            assertThat(result.checkoutUrl()).startsWith("https://fake-kakao/checkout/");
            assertThat(load(orderId).getPgTransactionId()).isNotNull();
            assertThat(pg.readyCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 orderId·같은 내용은 PG 준비를 다시 부르지 않고 같은 응답을 재현한다")
        void replay() {
            CheckoutCommand command = command(newOrderId(), newReservationId(), PgProvider.KAKAO);

            CheckoutResult first = paymentService.checkout(command);
            CheckoutResult second = paymentService.checkout(command);

            assertThat(second).isEqualTo(first);
            assertThat(pg.readyCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 orderId에 다른 내용(할인액 변경 포함)은 409 PAYMENT_ORDER_CONFLICT다")
        void orderConflict() {
            String orderId = newOrderId();
            long reservationId = newReservationId();
            paymentService.checkout(command(orderId, reservationId, PgProvider.TOSS, 5_000));

            assertError(
                    () -> paymentService.checkout(command(orderId, reservationId, PgProvider.TOSS, 4_000)),
                    PaymentErrorCode.PAYMENT_ORDER_CONFLICT);
            assertError(
                    () -> paymentService.checkout(command(orderId, reservationId, PgProvider.KAKAO, 5_000)),
                    PaymentErrorCode.PAYMENT_ORDER_CONFLICT);
        }
    }

    @Nested
    @DisplayName("같은 예매의 새 준비")
    class NewCheckoutForSameReservation {

        @Test
        @DisplayName("새 준비가 이전 READY를 FAILED(SUPERSEDED)로 대체한다")
        void supersedesPreviousReady() {
            long reservationId = newReservationId();
            String previous = newOrderId();
            paymentService.checkout(command(previous, reservationId, PgProvider.TOSS));

            paymentService.checkout(command(newOrderId(), reservationId, PgProvider.KAKAO));

            assertThat(load(previous).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(load(previous).getFailureCode()).isEqualTo("SUPERSEDED");
            assertThat(livePayments(reservationId)).isEqualTo(1);
        }

        @Test
        @DisplayName("승인 요청을 받은 READY와 IN_PROGRESS는 409 PAYMENT_ALREADY_IN_PROGRESS, DONE은 PAYMENT_ALREADY_DONE")
        void rejectsWhenApprovalStartedOrDone() {
            long confirmRequested = newReservationId();
            String confirmRequestedOrder = newOrderId();
            paymentService.checkout(command(confirmRequestedOrder, confirmRequested, PgProvider.TOSS));
            change(confirmRequestedOrder, payment -> payment.requestConfirm(LocalDateTime.now()));

            long inProgress = newReservationId();
            String inProgressOrder = newOrderId();
            paymentService.checkout(command(inProgressOrder, inProgress, PgProvider.TOSS));
            change(
                    inProgressOrder,
                    payment -> payment.startApproval(
                            "pk-" + inProgressOrder, LocalDateTime.now(), Duration.ofSeconds(10)));

            long done = newReservationId();
            String doneOrder = newOrderId();
            paymentService.checkout(command(doneOrder, done, PgProvider.TOSS));
            change(doneOrder, payment -> {
                payment.startApproval("pk-" + doneOrder, LocalDateTime.now(), Duration.ofSeconds(10));
                payment.approve(null, LocalDateTime.now());
            });

            assertError(
                    () -> paymentService.checkout(command(newOrderId(), confirmRequested, PgProvider.TOSS)),
                    PaymentErrorCode.PAYMENT_ALREADY_IN_PROGRESS);
            assertError(
                    () -> paymentService.checkout(command(newOrderId(), inProgress, PgProvider.TOSS)),
                    PaymentErrorCode.PAYMENT_ALREADY_IN_PROGRESS);
            assertError(
                    () -> paymentService.checkout(command(newOrderId(), done, PgProvider.TOSS)),
                    PaymentErrorCode.PAYMENT_ALREADY_DONE);
            assertThat(load(confirmRequestedOrder).getStatus()).isEqualTo(PaymentStatus.READY);
        }
    }

    @Nested
    @DisplayName("카카오 준비 실패와 재개")
    class KakaoReadyFailure {

        @Test
        @DisplayName("PG 준비 실패는 HTTP 오류가 아니라 FAILED(READY_FAILED, CLOSED) 결과다")
        void readyFailed() {
            pg.readyReturns(() -> new PgReadyResult.Failed("카카오 장애"));

            CheckoutResult result =
                    paymentService.checkout(command(newOrderId(), newReservationId(), PgProvider.KAKAO));

            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.failureCode()).isEqualTo("READY_FAILED");
            assertThat(result.failureType()).isEqualTo(PaymentFailureType.CLOSED);
            assertThat(result.checkoutUrl()).isNull();
        }

        @Test
        @DisplayName("준비 직후 중단돼 tid 없이 남은 READY는 같은 orderId 재요청 때 준비를 다시 부른다")
        void resumesReadyWithoutTid() {
            CheckoutCommand command = command(newOrderId(), newReservationId(), PgProvider.KAKAO);
            pg.readyReturns(() -> {
                throw new IllegalStateException("준비 직후 서버 중단");
            });
            assertThatThrownBy(() -> paymentService.checkout(command)).isInstanceOf(IllegalStateException.class);
            assertThat(load(command.orderId()).getPgTransactionId()).isNull();

            CheckoutResult resumed = paymentService.checkout(command);

            assertThat(resumed.status()).isEqualTo(PaymentStatus.READY);
            assertThat(resumed.checkoutUrl()).isNotNull();
            assertThat(pg.readyCalls()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("동시 요청")
    class Concurrency {

        private <T> List<Future<T>> runTogether(List<Callable<T>> tasks) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<T>> futures = new ArrayList<>();
                for (Callable<T> task : tasks) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return task.call();
                    }));
                }
                start.countDown();
                for (Future<T> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ignored) {
                        // 결과는 호출한 쪽이 Future로 확인한다
                    }
                }
                return futures;
            } finally {
                executor.shutdownNow();
            }
        }

        private static Throwable failure(Future<?> future) {
            try {
                future.get();
                return null;
            } catch (Exception e) {
                return e.getCause();
            }
        }

        @Test
        @DisplayName("같은 orderId 동시 10건은 결제 한 건을 만들고 모두 같은 결제로 응답한다")
        void sameOrderIdConcurrently() throws Exception {
            CheckoutCommand command = command(newOrderId(), newReservationId(), PgProvider.TOSS);
            List<Callable<CheckoutResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> paymentService.checkout(command));
            }

            List<Future<CheckoutResult>> futures = runTogether(tasks);

            Long paymentId = load(command.orderId()).getId();
            for (Future<CheckoutResult> future : futures) {
                assertThat(failure(future)).isNull();
                assertThat(future.get().paymentId()).isEqualTo(paymentId);
            }
        }

        @Test
        @DisplayName("같은 예매·다른 orderId 동시 5건은 살아 있는 결제를 하나만 남기고 나머지는 409 또는 대체된다")
        void sameReservationConcurrently() throws Exception {
            long reservationId = newReservationId();
            List<Callable<CheckoutResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String orderId = newOrderId();
                tasks.add(() -> paymentService.checkout(command(orderId, reservationId, PgProvider.TOSS)));
            }

            List<Future<CheckoutResult>> futures = runTogether(tasks);

            assertThat(livePayments(reservationId)).isEqualTo(1);
            for (Future<CheckoutResult> future : futures) {
                Throwable failure = failure(future);
                if (failure != null) {
                    assertThat(failure).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) failure).getErrorCode())
                            .isEqualTo(PaymentErrorCode.PAYMENT_CHECKOUT_CONFLICT);
                }
            }
        }

        @Test
        @DisplayName("tid 없는 카카오 READY를 동시에 재개해도 tid는 하나만 저장되고 두 응답이 같은 결제창을 준다")
        void concurrentResumeKeepsFirstTid() throws Exception {
            CheckoutCommand command = command(newOrderId(), newReservationId(), PgProvider.KAKAO);
            pg.readyReturns(() -> {
                throw new IllegalStateException("준비 직후 서버 중단");
            });
            assertThatThrownBy(() -> paymentService.checkout(command)).isInstanceOf(IllegalStateException.class);
            pg.holdReady(2);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<CheckoutResult> first = executor.submit(() -> paymentService.checkout(command));
                Future<CheckoutResult> second = executor.submit(() -> paymentService.checkout(command));
                pg.awaitReadyEntered(2);
                pg.releaseReady();

                assertThat(first.get().checkoutUrl()).isEqualTo(second.get().checkoutUrl());
            } finally {
                executor.shutdownNow();
            }
            Payment stored = load(command.orderId());
            assertThat(stored.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(stored.getCheckoutUrl()).endsWith(stored.getPgTransactionId());
        }

        @Test
        @DisplayName("준비 응답이 오기 전에 새 준비가 대체하면, 늦은 응답은 대체된 결제에 결제창을 저장하지 않는다")
        void lateReadyAfterSupersede() throws Exception {
            long reservationId = newReservationId();
            CheckoutCommand stale = command(newOrderId(), reservationId, PgProvider.KAKAO);
            pg.holdReady(1);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<CheckoutResult> staleResult = executor.submit(() -> paymentService.checkout(stale));
                pg.awaitReadyEntered(1);
                paymentService.checkout(command(newOrderId(), reservationId, PgProvider.TOSS));
                pg.releaseReady();

                assertThat(staleResult.get().status()).isEqualTo(PaymentStatus.FAILED);
                assertThat(staleResult.get().failureCode()).isEqualTo("SUPERSEDED");
                assertThat(staleResult.get().checkoutUrl()).isNull();
            } finally {
                executor.shutdownNow();
            }
            assertThat(load(stale.orderId()).getPgTransactionId()).isNull();
            assertThat(livePayments(reservationId)).isEqualTo(1);
        }
    }
}
