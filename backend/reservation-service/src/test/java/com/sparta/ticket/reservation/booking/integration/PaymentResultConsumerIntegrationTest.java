package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.application.reconcile.UnconfirmedRefundService;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * payment 없이 테스트가 직접 토픽에 결제 결과 메시지를 넣어 소비자를 검증한다.
 * 동기 승인 응답을 놓친 결제의 결과를 payment가 메시지로 알려 주면, 결과 확인 스케줄러를 기다리지 않고 예약이 끝난다.
 */
class PaymentResultConsumerIntegrationTest extends BookingIntegrationTest {

    private static final String TOPIC = "payment.result";
    private static final String DLT = "payment.result.DLT";
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String APPLIED_METER = "reservation.payment_result.applied";
    private static final String DEAD_LETTER_METER = "reservation.payment_result.dead_letter";
    private static final String DELIVERY_FAILED_METER = "reservation.payment_result.delivery_failed";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    @Qualifier("bookingMainDataSource")
    private HikariDataSource requestPool;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private UnconfirmedRefundService refundService;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    /** 승인 응답을 놓쳐 결제 중으로 남은 예약과 그 시도의 orderId. */
    private record InProgress(Long reservationId, String orderId, Long seatId) {}

    private Long hold(Long seatId) {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(seatId),
                clock.now().plusMinutes(10)));
    }

    private String checkout(Long reservationId) {
        return paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
    }

    private InProgress unansweredConfirm() {
        Long seatId = TestIds.next();
        Long reservationId = hold(seatId);
        String orderId = checkout(reservationId);
        payment.confirmTimesOut();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        return new InProgress(reservationId, orderId, seatId);
    }

    private static String message(String eventId, InProgress target, String status) {
        return message(eventId, target.orderId(), target.reservationId(), status);
    }

    private static String message(String eventId, String orderId, Long reservationId, String status) {
        return """
                {"eventId":"%s","schemaVersion":1,"occurredAt":"2026-11-01T20:00:10+09:00","orderId":"%s",\
                "reservationId":%d,"paymentId":77,"status":"%s","amount":10000,"failureType":null,"failureCode":null,\
                "approvedAt":"2026-11-01T20:00:09+09:00"}""".formatted(eventId, orderId, reservationId, status);
    }

    private void send(Long reservationId, String payload) throws Exception {
        kafkaTemplate.send(TOPIC, String.valueOf(reservationId), payload).get();
    }

    private void sendResult(InProgress target, String status) throws Exception {
        send(target.reservationId(), message(UUID.randomUUID().toString(), target, status));
    }

    private void awaitStatus(Long reservationId, String expected) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(statusOf(reservationId)).isEqualTo(expected));
    }

    private List<String> ticketCodes(Long reservationId) {
        return jdbcTemplate.queryForList(
                "SELECT ticket_code FROM p_tickets WHERE reservation_id = ?", String.class, reservationId);
    }

    /** 사용자 요청 풀의 연결을 모두 쥔 채로 상태를 읽는다. 결제 결과 반영은 이 풀이 비어 있어도 끝나야 한다. */
    private static String statusVia(Connection connection, Long reservationId) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT status FROM p_reservations WHERE reservation_id = " + reservationId)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("사용자 요청이 DB 연결 풀을 모두 차지해도 결제 결과는 반영된다")
    void confirmsWhileRequestPoolIsExhausted() throws Exception {
        InProgress target = unansweredConfirm();
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < requestPool.getMaximumPoolSize(); i++) {
                held.add(requestPool.getConnection());
            }
            sendResult(target, "DONE");

            Connection reader = held.get(0);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(statusVia(reader, target.reservationId()))
                            .isEqualTo("CONFIRMED"));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("결제 중인 예약에 성공 메시지가 오면 결과 확인을 기다리지 않고 확정하고 좌석을 팔고 티켓 코드를 발급한다")
    void confirmsOnSuccessMessage() throws Exception {
        InProgress target = unansweredConfirm();
        double appliedBefore = count(APPLIED_METER);

        sendResult(target, "DONE");

        awaitStatus(target.reservationId(), "CONFIRMED");
        assertThat(seats.sold).containsExactly(List.of(target.seatId()));
        assertThat(ticketCodes(target.reservationId())).hasSize(1).doesNotContainNull();
        assertThat(count(APPLIED_METER) - appliedBefore).isEqualTo(1);
        assertThat(payment.lookedUpOrderIds).as("payment에 묻지 않고 메시지로 끝낸다").isEmpty();
    }

    @Test
    @DisplayName("실패 메시지는 선점 시간이 남았으면 HOLD로 돌리고, 지났으면 EXPIRED로 끝내며 좌석을 돌려준다")
    void appliesFailureMessage() throws Exception {
        InProgress stillHeld = unansweredConfirm();
        InProgress expired = unansweredConfirm();

        sendResult(stillHeld, "FAILED");
        awaitStatus(stillHeld.reservationId(), "HOLD");

        clock.advance(Duration.ofMinutes(11));
        sendResult(expired, "FAILED");
        awaitStatus(expired.reservationId(), "EXPIRED");

        assertThat(seats.released).containsExactly(List.of(expired.seatId()));
        assertThat(seats.sold).isEmpty();
    }

    @Test
    @DisplayName("같은 메시지가 다시 오거나 같은 결과가 새 메시지로 와도 예약은 한 번만 바뀐다")
    void ignoresDuplicateResults() throws Exception {
        InProgress target = unansweredConfirm();
        InProgress marker = unansweredConfirm();
        String eventId = UUID.randomUUID().toString();
        double lateBefore = lateSuccessCount();
        double appliedBefore = count(APPLIED_METER);

        send(target.reservationId(), message(eventId, target, "DONE"));
        send(target.reservationId(), message(eventId, target, "DONE"));
        sendResult(target, "DONE");
        sendResult(marker, "DONE");

        awaitStatus(marker.reservationId(), "CONFIRMED");
        assertThat(statusOf(target.reservationId())).isEqualTo("CONFIRMED");
        assertThat(seats.sold).containsExactlyInAnyOrder(List.of(target.seatId()), List.of(marker.seatId()));
        assertThat(ticketCodes(target.reservationId())).hasSize(1);
        assertThat(lateSuccessCount()).as("같은 성공을 환불 대상으로 세지 않는다").isEqualTo(lateBefore);
        assertThat(count(APPLIED_METER) - appliedBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("지난 시도의 orderId로 성공 메시지가 오면 지금 예약은 그대로 두고 알리기만 한다. 그 결제의 환불은 payment가 한다")
    void reportsLateSuccessFromMessage() throws Exception {
        Long reservationId = hold(TestIds.next());
        String pastOrderId = checkout(reservationId);
        String orderId = checkout(reservationId);
        payment.confirmTimesOut();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        InProgress marker = unansweredConfirm();
        double lateBefore = lateSuccessCount();

        send(reservationId, message(UUID.randomUUID().toString(), pastOrderId, reservationId, "DONE"));
        sendResult(marker, "DONE");

        awaitStatus(marker.reservationId(), "CONFIRMED");
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(lateSuccessCount() - lateBefore).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(refundService.refund(Duration.ofMinutes(1), 10, Duration.ofSeconds(5)))
                .isZero();
        assertThat(payment.refundedOrderIds).isEmpty();
    }

    @Test
    @DisplayName("같은 eventId로 다시 온 지난 시도의 성공은 두 번 세지 않는다")
    void ignoresRedeliveredLateSuccess() throws Exception {
        Long reservationId = hold(TestIds.next());
        String pastOrderId = checkout(reservationId);
        String orderId = checkout(reservationId);
        payment.confirmTimesOut();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        InProgress marker = unansweredConfirm();
        double lateBefore = lateSuccessCount();
        String eventId = UUID.randomUUID().toString();

        send(reservationId, message(eventId, pastOrderId, reservationId, "DONE"));
        send(reservationId, message(eventId, pastOrderId, reservationId, "DONE"));
        sendResult(marker, "DONE");

        awaitStatus(marker.reservationId(), "CONFIRMED");
        assertThat(statusOf(reservationId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(lateSuccessCount() - lateBefore)
                .as("재전달된 같은 메시지는 받은 기록으로 걸러져 자동 환불 대상 수를 부풀리지 않는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("메시지가 승인 응답보다 먼저 오면 메시지로 확정하고, 뒤에 온 응답은 같은 성공이라 무시한다")
    void messageBeforeHttpResponse() throws Exception {
        Long seatId = TestIds.next();
        Long reservationId = hold(seatId);
        String orderId = checkout(reservationId);
        CountDownLatch confirmArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        payment.confirmAnswers(() -> {
            confirmArrived.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ConfirmOutcome.succeeded();
        });
        double lateBefore = lateSuccessCount();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> confirming = pool.submit(
                    () -> paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId)));
            assertThat(confirmArrived.await(10, TimeUnit.SECONDS)).isTrue();

            send(reservationId, message(UUID.randomUUID().toString(), orderId, reservationId, "DONE"));
            awaitStatus(reservationId, "CONFIRMED");

            release.countDown();
            confirming.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(seats.sold).containsExactly(List.of(seatId));
        assertThat(ticketCodes(reservationId)).hasSize(1);
        assertThat(lateSuccessCount()).isEqualTo(lateBefore);
    }

    @Test
    @DisplayName("해석할 수 없는 메시지는 DLT로 보내고 뒤의 메시지를 막지 않는다")
    void poisonGoesToDltAndDoesNotBlock() throws Exception {
        InProgress healthy = unansweredConfirm();
        InProgress ignored = unansweredConfirm();
        String broken = "{not-json-" + UUID.randomUUID();
        String futureVersion = message(UUID.randomUUID().toString(), ignored, "DONE")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        String unknownStatus = message(UUID.randomUUID().toString(), ignored, "IN_PROGRESS");
        String noOrderId = message(UUID.randomUUID().toString(), ignored, "DONE")
                .replace("\"orderId\":\"" + ignored.orderId() + "\",", "");
        double deadLettersBefore = count(DEAD_LETTER_METER);

        send(ignored.reservationId(), broken);
        send(ignored.reservationId(), futureVersion);
        send(ignored.reservationId(), unknownStatus);
        send(ignored.reservationId(), noOrderId);
        sendResult(healthy, "DONE");

        awaitStatus(healthy.reservationId(), "CONFIRMED");
        assertThat(statusOf(ignored.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(deadLetters()).contains(broken, futureVersion, unknownStatus, noOrderId));
        assertThat(count(DEAD_LETTER_METER) - deadLettersBefore)
                .as("DLT로 옮긴 메시지를 경보가 볼 수 있게 센다")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("반영이 실패하면 메시지를 잃지 않고 DB가 돌아온 뒤 반영한다")
    void retriesUntilDatabaseRecovers() throws Exception {
        InProgress target = unansweredConfirm();
        double failedBefore = count(DELIVERY_FAILED_METER);
        jdbcTemplate.execute("ALTER TABLE p_payment_result_events RENAME TO p_payment_result_events_down");
        try {
            sendResult(target, "DONE");
            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(count(DELIVERY_FAILED_METER) - failedBefore)
                            .as("파티션이 멈춘 동안 실패를 경보가 볼 수 있게 센다")
                            .isGreaterThan(0));
            assertThat(statusOf(target.reservationId())).isEqualTo("PAYMENT_IN_PROGRESS");
        } finally {
            jdbcTemplate.execute("ALTER TABLE p_payment_result_events_down RENAME TO p_payment_result_events");
        }

        awaitStatus(target.reservationId(), "CONFIRMED");
        assertThat(deadLetters()).noneMatch(payload -> payload.contains(target.orderId()));
    }

    private List<String> deadLetters() {
        try (Consumer<String, String> consumer =
                consumerFactory.createConsumer("dlt-check-" + UUID.randomUUID(), "dlt-check")) {
            List<TopicPartition> partitions = consumer.partitionsFor(DLT).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                    .map(record -> record.value())
                    .toList();
        }
    }
}
