package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.dto.ReservationResult;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup;
import com.sparta.ticket.reservation.booking.application.port.PaymentLookup.Status;
import com.sparta.ticket.reservation.booking.application.reconcile.PaymentReconcileService;
import com.sparta.ticket.reservation.booking.infrastructure.messaging.OutboxPublisher;
import com.sparta.ticket.reservation.booking.infrastructure.payment.PaymentGuardProperties;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.SwitchableConfirmSettings;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * 승인을 메시지로 요청하는 모드. payment 소비자가 없으므로 테스트가 토픽을 직접 읽고, 결제 조회 결과는 가짜로 정한다.
 * PG가 느려도 승인 요청이 요청 스레드를 잡지 않고, 결제 호출이 막혀도 503으로 돌려보내지 않는지 본다.
 */
class PaymentConfirmMessageIntegrationTest extends BookingIntegrationTest {

    private static final String TOPIC = "payment.confirm.requested";
    private static final Duration THRESHOLD = Duration.ofMinutes(1);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final String UNRECEIVED_METER = "reservation.payment.confirm.unreceived";
    private static final String PUBLISH_FAILED_METER = "reservation.outbox.publish_failed";

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private PaymentReconcileService reconcileService;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private PaymentGuardProperties guardProperties;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private ObjectMapper objectMapper;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    private record Attempt(Long reservationId, String orderId) {}

    private Long hold() {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));
    }

    private Attempt checkedOut() {
        Long reservationId = hold();
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        return new Attempt(reservationId, orderId);
    }

    private ReservationResult confirm(Attempt attempt) {
        return paymentService.confirm(
                user, attempt.reservationId(), new ConfirmCommand(attempt.orderId(), "pk-" + attempt.orderId()));
    }

    private Attempt requestedByMessage() {
        confirmSettings.useMessage();
        Attempt attempt = checkedOut();
        confirm(attempt);
        return attempt;
    }

    private int outboxSize() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM p_booking_outbox", Integer.class);
    }

    private int reconcile() {
        return reconcileService.reconcile(THRESHOLD, STUCK_AFTER, 10, BUDGET);
    }

    private List<ConsumerRecord<String, String>> requestsFor(Set<String> orderIds, int expected) throws Exception {
        return requestsFor(orderIds, expected, Duration.ofSeconds(30));
    }

    /**
     * 이 토픽에서 orderIds에 해당하는 레코드를 처음부터 모두 읽는다. 기대한 수를 채운 뒤에도 잠시 더 읽어 중복을 잡는다.
     * wait 안에 기대한 수가 오지 않으면 그때까지 찾은 것만 돌려준다.
     */
    private List<ConsumerRecord<String, String>> requestsFor(Set<String> orderIds, int expected, Duration wait)
            throws Exception {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer =
                consumerFactory.createConsumer("confirm-requested-test-" + UUID.randomUUID(), "test")) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + wait.toNanos();
            long settleUntil = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, settleUntil)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (orderIds.contains(
                            objectMapper.readTree(record.value()).get("orderId").asText())) {
                        found.add(record);
                    }
                }
                if (found.size() >= expected && settleUntil == Long.MAX_VALUE) {
                    settleUntil = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                }
            }
        }
        return found;
    }

    /** 결제 호출이 막힐 때까지 응답 없는 동기 승인을 쌓는다. 결제 준비가 503이 되면 막힌 것이다. */
    private void blockPaymentCalls() {
        payment.confirmTimesOut();
        for (int i = 0; i < guardProperties.windowSize(); i++) {
            Long reservationId = hold();
            String orderId;
            try {
                orderId = paymentService
                        .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                        .orderId();
            } catch (BusinessException e) {
                return;
            }
            confirm(new Attempt(reservationId, orderId));
        }
        throw new AssertionError("응답 없는 승인이 이어졌는데 결제 호출이 막히지 않았다");
    }

    @Test
    @DisplayName("메시지 모드 승인은 payment를 부르지 않고 결제 중으로 답하며, 승인 요청을 같은 트랜잭션에 남긴다")
    void answersWithoutWaitingForPayment() {
        confirmSettings.useMessage();
        Attempt attempt = checkedOut();

        ReservationResult result = confirm(attempt);

        assertThat(result.status().name()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(payment.confirmedOrderIds).isEmpty();
        assertThat(outboxSize()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT pg_auth_token FROM p_reservations WHERE reservation_id = ?",
                        String.class,
                        attempt.reservationId()))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT confirm_before FROM p_reservations WHERE reservation_id = ?",
                        LocalDateTime.class,
                        attempt.reservationId()))
                .isEqualTo(clock.now().plus(SwitchableConfirmSettings.WINDOW));
    }

    @Test
    @DisplayName("결제 호출이 막혀 있어도 메시지 모드 승인은 503 없이 받는다")
    void acceptsWhilePaymentCallsAreBlocked() {
        Attempt waiting = checkedOut();
        blockPaymentCalls();
        int calls = payment.confirmedOrderIds.size();
        confirmSettings.useMessage();

        ReservationResult result = confirm(waiting);

        assertThat(result.status().name()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(payment.confirmedOrderIds).hasSize(calls);
    }

    @Test
    @DisplayName("선점이 끝난 예약은 메시지로도 승인을 받지 않아, 시한은 늘 선점 만료 + window 안이다")
    void rejectsAfterHoldExpired() {
        confirmSettings.useMessage();
        Attempt attempt = checkedOut();
        clock.advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> confirm(attempt))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.HOLD_EXPIRED));

        assertThat(outboxSize()).isZero();
        assertThat(statusOf(attempt.reservationId())).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("같은 승인을 다시 보내도 승인 요청은 하나만 남는다")
    void duplicateConfirmLeavesOneRequest() {
        Attempt attempt = requestedByMessage();

        ReservationResult again = confirm(attempt);

        assertThat(again.status().name()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(outboxSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행기는 승인 요청을 예약 키로 보내고 보낸 행을 지운다")
    void publishesRequestAndRemovesIt() throws Exception {
        Attempt attempt = requestedByMessage();
        long amount = jdbcTemplate.queryForObject(
                "SELECT payment_amount FROM p_reservations WHERE reservation_id = ?",
                Long.class,
                attempt.reservationId());

        assertThat(publisher.publish(100, SEND_TIMEOUT)).isEqualTo(1);

        assertThat(outboxSize()).isZero();
        List<ConsumerRecord<String, String>> records = requestsFor(Set.of(attempt.orderId()), 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(attempt.reservationId().toString());
        JsonNode body = objectMapper.readTree(records.get(0).value());
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("eventId").asText()).isNotBlank();
        assertThat(body.get("reservationId").asLong()).isEqualTo(attempt.reservationId());
        assertThat(body.get("amount").asLong()).isEqualTo(amount);
        assertThat(body.get("pgAuthToken").asText()).isEqualTo("pk-" + attempt.orderId());
        assertThat(body.get("confirmBefore").asText()).isEqualTo("2026-11-01T20:05:00+09:00");
    }

    @Test
    @DisplayName("승인 요청을 커밋하자마자 발행해, 발행 스케줄러의 다음 차례를 기다리지 않는다")
    void publishesRightAfterCommit() throws Exception {
        outboxWakeup.publishOnCommit();

        Attempt attempt = requestedByMessage();

        // 통합 테스트는 발행 스케줄러를 끄므로 여기서 보이는 레코드는 커밋 직후 발행만으로 나간 것이다
        assertThat(requestsFor(Set.of(attempt.orderId()), 1, Duration.ofSeconds(5)))
                .hasSize(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> outboxSize() == 0);
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("발행이 브로커를 기다리며 멈춰도 승인은 기다리지 않고 결제 중으로 답하고, 요청은 Outbox에 남는다")
    void answersWhilePublishingIsStuck() {
        outboxWakeup.stallPublishing();
        confirmSettings.useMessage();
        long started = System.nanoTime();

        for (int i = 0; i < 3; i++) {
            assertThat(confirm(checkedOut()).status().name()).isEqualTo("PAYMENT_IN_PROGRESS");
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(outboxSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("보내지 못한 메시지는 지우지 않고 남겨 다음 차례에 다시 보낸다")
    void keepsMessageThatCouldNotBeSent() {
        double failed = count(PUBLISH_FAILED_METER);
        // 토픽 이름에 쓸 수 없는 문자를 넣어 브로커가 받지 못하게 한다
        jdbcTemplate.update(
                "INSERT INTO p_booking_outbox (topic, message_key, payload, created_at) VALUES (?, ?, ?, ?)",
                "invalid topic!",
                "1",
                "{}",
                clock.now());

        assertThat(publisher.publish(100, SEND_TIMEOUT)).isZero();

        assertThat(outboxSize()).isEqualTo(1);
        assertThat(count(PUBLISH_FAILED_METER)).isEqualTo(failed + 1);
    }

    @Test
    @DisplayName("발행기 두 개가 동시에 돌아도 승인 요청을 한 번씩만 보낸다")
    void concurrentPublishersSendOnce() throws Exception {
        confirmSettings.useMessage();
        Set<String> orderIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            Attempt attempt = checkedOut();
            confirm(attempt);
            orderIds.add(attempt.orderId());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return publisher.publish(100, SEND_TIMEOUT);
                }));
            }
            start.countDown();
            int sent = 0;
            for (Future<Integer> run : runs) {
                sent += run.get();
            }

            assertThat(sent).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outboxSize()).isZero();
        assertThat(requestsFor(orderIds, 20)).hasSize(20);
    }

    @Test
    @DisplayName("메시지로 요청한 승인은 시한과 여유 시간이 지나기 전에는 결과 확인이 payment에 묻지 않는다")
    void doesNotLookUpBeforeDeadline() {
        Attempt attempt = requestedByMessage();
        clock.advance(SwitchableConfirmSettings.WINDOW
                .plus(SwitchableConfirmSettings.GRACE)
                .minusSeconds(1));
        payment.lookupReturns(attempt.orderId(), PaymentLookup.of(Status.NOT_RECEIVED));

        reconcile();

        assertThat(payment.lookedUpOrderIds).isEmpty();
        assertThat(statusOf(attempt.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("시한이 지나도 payment가 승인 요청을 받지 못했으면 다시 보내지 않고 실패로 끝낸다")
    void failsRequestThatNeverArrived() {
        double unreceived = count(UNRECEIVED_METER);
        Attempt attempt = requestedByMessage();
        clock.advance(SwitchableConfirmSettings.WINDOW
                .plus(SwitchableConfirmSettings.GRACE)
                .plusSeconds(1));
        payment.lookupReturns(attempt.orderId(), PaymentLookup.of(Status.NOT_RECEIVED));

        reconcile();

        assertThat(payment.lookedUpOrderIds).containsExactly(attempt.orderId());
        assertThat(payment.confirmedOrderIds).isEmpty();
        assertThat(statusOf(attempt.reservationId())).isEqualTo("HOLD");
        assertThat(count(UNRECEIVED_METER)).isEqualTo(unreceived + 1);
    }

    @Test
    @DisplayName("동기 승인으로 받지 못한 요청은 지금처럼 같은 인증값으로 다시 보낸다")
    void syncRequestIsStillResent() {
        Attempt attempt = checkedOut();
        payment.confirmTimesOut();
        confirm(attempt);
        payment.confirmReturns(ConfirmOutcome.succeeded());
        clock.advance(THRESHOLD.plusSeconds(1));
        payment.lookupReturns(attempt.orderId(), PaymentLookup.of(Status.NOT_RECEIVED));

        reconcile();

        assertThat(payment.confirmedOrderIds).containsExactly(attempt.orderId(), attempt.orderId());
        assertThat(statusOf(attempt.reservationId())).isEqualTo("CONFIRMED");
    }
}
