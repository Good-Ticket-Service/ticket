package com.sparta.ticket.reservation.booking.infrastructure.redis;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.port.PaymentSettledEvents;
import com.sparta.ticket.reservation.booking.application.query.PaymentResultWaiters;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 결제 결과가 정해졌다고 이 인스턴스의 기다리는 조회에 알리고, Redis 채널로 다른 인스턴스에도 알린다.
 * 결과를 받은 인스턴스와 사용자가 기다리는 인스턴스가 다를 수 있기 때문이다. 자기가 보낸 알림도 다시 받지만, 이미 답한 대기는 목록에 없어 아무 일도 없다.
 *
 * <p>커밋된 뒤에만 알린다. 롤백된 결과로 답하면 사용자가 보는 상태와 DB가 어긋난다.
 *
 * <p>Redis로 보내기는 결과를 반영한 스레드(Kafka 소비, 결과 확인, 동기 승인)가 아니라 전용 스레드 하나에서 한다. Redis가 느리거나 멈추면
 * 명령 제한 시간만큼 기다리는데, 그 대기가 결과 반영을 늦추면 안 된다. 보낼 것이 밀려 대기열이 차면 버린다.
 * 알림을 못 받은 인스턴스의 조회는 대기 시간 끝에 결제 중으로 답하고 다음 조회가 등록 직후 읽어 결과를 보므로, 결과를 잃지 않고 늦어질 뿐이다.
 */
@Slf4j
@Component
public class PaymentSettledBroadcaster implements PaymentSettledEvents, MessageListener, DisposableBean {

    /** 메시지는 "예약ID:상태:커밋 뒤 시각(epoch ms)"이다. */
    public static final String CHANNEL = "booking:payment-settled";

    private static final int QUEUE_CAPACITY = 10_000;

    private final PaymentResultWaiters waiters;
    private final StringRedisTemplate redis;
    private final BookingMetrics metrics;
    private final ThreadPoolExecutor sender;

    public PaymentSettledBroadcaster(PaymentResultWaiters waiters, StringRedisTemplate redis, BookingMetrics metrics) {
        this.waiters = waiters;
        this.redis = redis;
        this.metrics = metrics;
        this.sender = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "booking-settled-broadcast");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> metrics.settledBroadcastFailed());
    }

    @Override
    public void settled(Long reservationId, ReservationStatus status) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            broadcast(reservationId, status);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcast(reservationId, status);
            }
        });
    }

    /** 커밋된 뒤의 시각을 알림에 싣는다. 받는 쪽은 이 시각보다 늦게 등록한 대기(다음 결제 시도일 수 있음)에는 답하지 않는다. */
    private void broadcast(Long reservationId, ReservationStatus status) {
        long settledAtMillis = System.currentTimeMillis();
        try {
            waiters.settle(reservationId, status, settledAtMillis);
        } catch (RuntimeException e) {
            log.warn("이 인스턴스의 기다리던 조회에 결제 결과를 알리지 못했다. reservationId={}", reservationId, e);
        }
        sender.execute(() -> publish(reservationId, status, settledAtMillis));
    }

    private void publish(Long reservationId, ReservationStatus status, long settledAtMillis) {
        try {
            redis.convertAndSend(CHANNEL, reservationId + ":" + status.name() + ":" + settledAtMillis);
        } catch (RuntimeException e) {
            metrics.settledBroadcastFailed();
            log.debug("결제 결과를 다른 인스턴스에 알리지 못했다. reservationId={}", reservationId, e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String[] parts = body.split(":");
        try {
            waiters.settle(Long.valueOf(parts[0]), ReservationStatus.valueOf(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            log.warn("해석할 수 없는 결제 결과 알림을 버린다. body={}", body);
        }
    }

    @Override
    public void destroy() {
        sender.shutdownNow();
    }
}
