package com.sparta.ticket.reservation.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.dto.CheckoutCommand;
import com.sparta.ticket.reservation.booking.application.dto.ConfirmCommand;
import com.sparta.ticket.reservation.booking.application.facade.BookingFacade;
import com.sparta.ticket.reservation.booking.application.payment.PaymentResultApplier;
import com.sparta.ticket.reservation.booking.application.payment.ReservationPaymentService;
import com.sparta.ticket.reservation.booking.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.booking.support.BookingIntegrationTest;
import com.sparta.ticket.reservation.booking.support.TestHoldTargets;
import com.sparta.ticket.reservation.booking.support.TestIds;
import com.sparta.ticket.reservation.booking.support.TestSeats;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 결제 결과를 기다리는 롱폴링. 상태 조회에 wait를 주면 결과가 정해지는 즉시 응답하고, 기다리는 동안 요청 스레드와 DB 연결을 잡지 않는다.
 * 서버가 여러 대면 결과를 받은 서버가 Redis로 다른 서버에 알린다.
 */
class PaymentResultLongPollIntegrationTest extends BookingIntegrationTest {

    private static final String STATUS = "/api/reservations/{id}/status";
    /** 다른 인스턴스가 결과를 알리는 Redis 채널. 메시지는 "예약ID:상태:커밋 뒤 시각(epoch ms)"이다. */
    private static final String SETTLED_CHANNEL = "booking:payment-settled";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingFacade bookingFacade;

    @Autowired
    private ReservationPaymentService paymentService;

    @Autowired
    private PaymentResultApplier applier;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private BookingMetrics metrics;

    @Autowired
    @Qualifier("bookingMainDataSource")
    private HikariDataSource requestPool;

    private final UserContext user = new UserContext(TestIds.next(), "hong01", Role.USER);

    private record Attempt(Long reservationId, String orderId) {}

    private Long hold() {
        return transactionTemplate.execute(status -> bookingFacade.hold(
                user.userId(),
                TestHoldTargets.of(TestIds.next()),
                TestSeats.priced(TestIds.next()),
                clock.now().plusMinutes(10)));
    }

    /** 승인을 메시지로 요청해 결과를 기다리는 예약. */
    private Attempt inProgress() {
        confirmSettings.useMessage();
        Long reservationId = hold();
        String orderId = paymentService
                .checkout(user, reservationId, new CheckoutCommand(null, "TOSS"))
                .orderId();
        paymentService.confirm(user, reservationId, new ConfirmCommand(orderId, "pk-" + orderId));
        return new Attempt(reservationId, orderId);
    }

    private MockHttpServletRequestBuilder as(Long userId, MockHttpServletRequestBuilder request) {
        return request.header(UserHeaders.USER_ID, userId.toString())
                .header(UserHeaders.USERNAME, "hong01")
                .header(UserHeaders.ROLE, "USER");
    }

    private MvcResult waitStatus(Long reservationId, String wait) throws Exception {
        return mockMvc.perform(as(user.userId(), get(STATUS, reservationId).param("wait", wait)))
                .andReturn();
    }

    @Test
    @DisplayName("결제 중에 wait로 조회하면 응답을 미뤘다가 결과가 반영되는 즉시 확정으로 답한다")
    void answersAsSoonAsSettled() throws Exception {
        Attempt attempt = inProgress();

        MvcResult pending = waitStatus(attempt.reservationId(), "10");

        assertThat(pending.getRequest().isAsyncStarted()).isTrue();
        long started = System.nanoTime();
        applier.apply(attempt.reservationId(), attempt.orderId(), ConfirmOutcome.succeeded());
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(header().doesNotExist("Retry-After"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("wait 안에 결과가 정해지지 않으면 결제 중으로 답한다")
    void answersInProgressWhenWaitEnds() throws Exception {
        Attempt attempt = inProgress();
        long started = System.nanoTime();

        MvcResult pending = waitStatus(attempt.reservationId(), "1");

        assertThat(pending.getRequest().isAsyncStarted()).isTrue();
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isBetween(Duration.ofMillis(500), Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("결과가 이미 정해진 예약은 wait로 조회해도 기다리지 않고 바로 답한다")
    void answersAtOnceWhenAlreadySettled() throws Exception {
        Long reservationId = hold();
        long started = System.nanoTime();

        MvcResult result = waitStatus(reservationId, "10");

        if (result.getRequest().isAsyncStarted()) {
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(jsonPath("$.data.status").value("HOLD"));
        } else {
            assertThat(result.getResponse().getContentAsString()).contains("\"HOLD\"");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("기다리는 동안 요청 스레드가 쓰는 DB 연결을 잡고 있지 않는다")
    void holdsNoConnectionWhileWaiting() throws Exception {
        Attempt attempt = inProgress();

        MvcResult pending = waitStatus(attempt.reservationId(), "10");

        assertThat(pending.getRequest().isAsyncStarted()).isTrue();
        await().atMost(Duration.ofSeconds(2))
                .until(() -> requestPool.getHikariPoolMXBean().getActiveConnections() == 0);
        applier.apply(attempt.reservationId(), attempt.orderId(), ConfirmOutcome.succeeded());
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("결과 반영이 롤백되면 기다리는 조회에 알리지 않는다")
    void doesNotAnswerWhenSettlementRollsBack() throws Exception {
        Attempt attempt = inProgress();
        MvcResult pending = waitStatus(attempt.reservationId(), "10");
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();

        transactionTemplate.executeWithoutResult(status -> {
            applier.apply(attempt.reservationId(), attempt.orderId(), ConfirmOutcome.succeeded());
            status.setRollbackOnly();
        });

        assertThatThrownBy(() -> pending.getAsyncResult(500)).isInstanceOf(IllegalStateException.class);
        applier.apply(attempt.reservationId(), attempt.orderId(), ConfirmOutcome.succeeded());
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("다른 서버가 Redis로 알린 결과에도 기다리던 조회가 바로 답한다")
    void answersWhenAnotherInstanceSettles() throws Exception {
        Attempt attempt = inProgress();
        MvcResult pending = waitStatus(attempt.reservationId(), "10");
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();

        redis.convertAndSend(SETTLED_CHANNEL, attempt.reservationId() + ":CONFIRMED:" + System.currentTimeMillis());

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("결과 알림을 놓치면 wait 끝에 결제 중으로 답하고, 다음 wait 조회가 등록 직후 읽어 결과를 본다")
    void nextWaitSeesResultWhenNotificationWasMissed() throws Exception {
        Attempt attempt = inProgress();
        MvcResult pending = waitStatus(attempt.reservationId(), "1");
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();

        // 알림 없이 상태만 바뀐 경우(다른 인스턴스의 알림이 빠짐)를 만든다
        jdbcTemplate.update(
                "UPDATE p_reservations SET status = 'CONFIRMED' WHERE reservation_id = ?", attempt.reservationId());

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_IN_PROGRESS"));
        MvcResult next = waitStatus(attempt.reservationId(), "10");
        if (next.getRequest().isAsyncStarted()) {
            mockMvc.perform(asyncDispatch(next))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        } else {
            assertThat(next.getResponse().getContentAsString()).contains("\"CONFIRMED\"");
        }
    }

    @Test
    @DisplayName("대기를 등록하기 전에 정해진 결과의 알림(지난 결제 시도의 것일 수 있음)으로는 답하지 않는다")
    void ignoresSettlementFromBeforeRegistration() throws Exception {
        Attempt attempt = inProgress();
        MvcResult pending = waitStatus(attempt.reservationId(), "10");
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();

        long beforeRegistration = System.currentTimeMillis() - 60_000;
        redis.convertAndSend(SETTLED_CHANNEL, attempt.reservationId() + ":HOLD:" + beforeRegistration);

        assertThatThrownBy(() -> pending.getAsyncResult(1_000)).isInstanceOf(IllegalStateException.class);
        redis.convertAndSend(SETTLED_CHANNEL, attempt.reservationId() + ":CONFIRMED:" + System.currentTimeMillis());
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("답한 조회는 대기 목록에서 빠진다")
    void removesWaiterOnceAnswered() throws Exception {
        long before = metrics.resultWaitersCount();
        Attempt settled = inProgress();
        Attempt timedOut = inProgress();

        MvcResult first = waitStatus(settled.reservationId(), "10");
        MvcResult second = waitStatus(timedOut.reservationId(), "1");
        assertThat(metrics.resultWaitersCount()).isEqualTo(before + 2);
        applier.apply(settled.reservationId(), settled.orderId(), ConfirmOutcome.succeeded());
        mockMvc.perform(asyncDispatch(first));
        mockMvc.perform(asyncDispatch(second));

        await().atMost(Duration.ofSeconds(2)).until(() -> metrics.resultWaitersCount() == before);
    }

    @Test
    @DisplayName("결과를 반영한 인스턴스는 커밋 뒤 Redis 채널로 다른 인스턴스에 알린다")
    void broadcastsSettlementToOtherInstances() throws Exception {
        Attempt attempt = inProgress();
        List<String> received = new CopyOnWriteArrayList<>();
        RedisMessageListenerContainer otherInstance = new RedisMessageListenerContainer();
        otherInstance.setConnectionFactory(redisConnectionFactory);
        otherInstance.addMessageListener(
                (message, pattern) -> received.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(SETTLED_CHANNEL));
        otherInstance.afterPropertiesSet();
        otherInstance.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(otherInstance::isListening);

            applier.apply(attempt.reservationId(), attempt.orderId(), ConfirmOutcome.succeeded());

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> received.stream()
                            .anyMatch(body -> body.startsWith(attempt.reservationId() + ":CONFIRMED:")));
        } finally {
            otherInstance.stop();
            otherInstance.destroy();
        }
    }

    @Test
    @DisplayName("wait는 1~10초다")
    void rejectsWaitOutOfRange() throws Exception {
        Long reservationId = hold();

        for (String wait : new String[] {"0", "11"}) {
            mockMvc.perform(as(user.userId(), get(STATUS, reservationId).param("wait", wait)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    @DisplayName("wait로 조회해도 본인만 볼 수 있다")
    void waitIsOwnerOnly() throws Exception {
        Attempt attempt = inProgress();

        mockMvc.perform(as(TestIds.next(), get(STATUS, attempt.reservationId()).param("wait", "10")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
