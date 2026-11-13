package com.sparta.ticket.reservation.booking.presentation;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.query.PaymentResultWaiters;
import com.sparta.ticket.reservation.booking.application.query.ReservationQueryService;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * 결제 결과를 기다리는 상태 조회(롱폴링). 결제 중이면 응답을 미뤘다가 결과가 정해지는 즉시 답한다.
 * 짧은 폴링은 서버가 결과를 정해도 다음 조회 간격만큼 늦게 보이고, 결제 중이 많으면 그 간격을 늘리므로 더 늦어진다.
 *
 * <p>미루는 동안 요청 스레드와 DB 연결을 잡지 않는다. 남는 것은 열린 연결과 대기 목록의 항목 하나다.
 * 대기 시간은 컨테이너가 아니라 이 클래스의 타이머가 끝내고, 결제 중으로 답한다. 끝낼 때 DB를 다시 읽지 않는다. 결과가 늦어 대기가 많으면
 * 끝나는 대기마다 읽기가 생겨 타이머 하나가 밀리고 사용자 요청과 같은 연결 풀을 쓰기 때문이다. 알림이 빠졌으면 클라이언트의 다음 조회가
 * 등록 직후 읽어 결과를 본다.
 * 결과가 오래 정해지지 않을 때 기다리던 조회가 같은 순간에 끝나 한꺼번에 다시 오지 않도록, 대기 시간을 요청한 값의 80 ~ 100%로 흩는다.
 */
@Slf4j
@Component
public class PaymentResultLongPoll implements DisposableBean {

    /** 타이머가 끝내지 못했을 때 컨테이너가 끝내는 여유. 정상이면 타이머가 먼저 끝낸다. */
    private static final Duration CONTAINER_MARGIN = Duration.ofSeconds(5);

    private final ReservationQueryService queryService;
    private final PaymentResultWaiters waiters;
    private final BookingMetrics metrics;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "booking-result-wait-timer");
        thread.setDaemon(true);
        return thread;
    });

    public PaymentResultLongPoll(
            ReservationQueryService queryService, PaymentResultWaiters waiters, BookingMetrics metrics) {
        this.queryService = queryService;
        this.waiters = waiters;
        this.metrics = metrics;
    }

    /**
     * @param respond 상태를 응답으로 바꾼다. 결과 알림이 온 스레드나 타이머 스레드에서 부르므로 DB를 읽지 않아야 한다
     */
    public <T> DeferredResult<T> await(
            UserContext user, Long reservationId, Duration wait, Function<ReservationStatus, T> respond) {
        DeferredResult<T> result =
                new DeferredResult<>(wait.plus(CONTAINER_MARGIN).toMillis());
        Runnable unregister = waiters.register(reservationId, status -> result.setResult(respond.apply(status)));
        ReservationStatus current;
        try {
            current = queryService.getStatus(user, reservationId).status();
        } catch (RuntimeException e) {
            unregister.run();
            throw e;
        }
        if (current != ReservationStatus.PAYMENT_IN_PROGRESS) {
            unregister.run();
            result.setResult(respond.apply(current));
            return result;
        }
        ScheduledFuture<?> timeout;
        try {
            timeout = timer.schedule(() -> finish(result, respond), jittered(wait), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 멈추는 중이다. 기다리지 않고 결제 중으로 답한다
            unregister.run();
            result.setResult(respond.apply(ReservationStatus.PAYMENT_IN_PROGRESS));
            return result;
        }
        result.onCompletion(() -> {
            unregister.run();
            timeout.cancel(false);
        });
        result.onTimeout(() -> finish(result, respond));
        result.onError(error -> unregister.run());
        return result;
    }

    /** 대기 시간이 끝났다. 결과가 정해지지 않았으므로 결제 중으로 답한다. */
    private <T> void finish(DeferredResult<T> result, Function<ReservationStatus, T> respond) {
        if (result.setResult(respond.apply(ReservationStatus.PAYMENT_IN_PROGRESS))) {
            metrics.resultWaitTimedOut();
        }
    }

    private static long jittered(Duration wait) {
        long millis = wait.toMillis();
        return millis - ThreadLocalRandom.current().nextLong(millis / 5 + 1);
    }

    @Override
    public void destroy() {
        timer.shutdownNow();
    }
}
