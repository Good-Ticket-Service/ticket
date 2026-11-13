package com.sparta.ticket.reservation.booking.application.query;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제 결과를 기다리는 상태 조회를 예약별로 모아 두고, 결과가 정해지면 한 번씩 알린다. 이 인스턴스의 메모리에만 있다.
 * 다른 인스턴스가 정한 결과는 그 인스턴스가 보낸 알림으로 받는다.
 *
 * <p>조회는 등록한 뒤에 상태를 읽는다. 읽기 전에 결과가 정해졌으면 읽은 상태로 바로 답하고, 읽은 뒤에 정해졌으면 이 알림으로 답한다.
 * 그래서 읽기와 등록 사이에 정해진 결과를 놓치지 않는다. 알린 대기는 목록에서 빠지므로 같은 결과가 두 번 와도 한 번만 답한다.
 *
 * <p>등록보다 먼저 정해진 결과의 알림은 받지 않는다. 그 결과는 등록 직후의 읽기가 이미 반영했고, 늦게 도착한 알림은 지난 결제 시도의 것일 수 있다.
 * 예를 들어 실패(HOLD) 알림이 Redis에서 밀렸다가, 사용자가 다시 결제해 기다리는 새 시도에 도착하면 진행 중인 결제를 실패로 답하게 된다.
 * 인스턴스 사이 시계 차이로 제때 온 알림을 버리지 않도록 {@link #CLOCK_TOLERANCE_MILLIS}만큼은 받는다. 다시 결제하려면 결제 준비와 PG 인증을
 * 거쳐야 해서 지난 시도의 결과와 새 대기 등록 사이는 이보다 길다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultWaiters {

    static final long CLOCK_TOLERANCE_MILLIS = 1_000;

    private final BookingMetrics metrics;
    private final ConcurrentHashMap<Long, Set<Waiter>> waiting = new ConcurrentHashMap<>();

    /** 대기 하나. 같은 예약을 여러 번 기다려도 각각 따로 센다. */
    private static final class Waiter {
        private final long registeredAtMillis;
        private final Consumer<ReservationStatus> onSettled;

        private Waiter(long registeredAtMillis, Consumer<ReservationStatus> onSettled) {
            this.registeredAtMillis = registeredAtMillis;
            this.onSettled = onSettled;
        }
    }

    /** @return 기다림을 그만둘 때 부르는 해제. 알림을 받았거나 이미 해제했으면 아무것도 하지 않는다 */
    public Runnable register(Long reservationId, Consumer<ReservationStatus> onSettled) {
        Waiter waiter = new Waiter(System.currentTimeMillis(), onSettled);
        waiting.compute(reservationId, (id, waiters) -> {
            Set<Waiter> set = waiters == null ? ConcurrentHashMap.newKeySet() : waiters;
            set.add(waiter);
            metrics.resultWaiterAdded();
            return set;
        });
        return () -> remove(reservationId, waiter);
    }

    private void remove(Long reservationId, Waiter waiter) {
        waiting.computeIfPresent(reservationId, (id, waiters) -> {
            if (waiters.remove(waiter)) {
                metrics.resultWaiterRemoved();
            }
            return waiters.isEmpty() ? null : waiters;
        });
    }

    /**
     * @param settledAtMillis 결과가 커밋된 뒤의 시각. 이 시각보다 늦게 등록한 대기에는 답하지 않는다
     */
    public void settle(Long reservationId, ReservationStatus status, long settledAtMillis) {
        List<Waiter> answered = new ArrayList<>();
        waiting.computeIfPresent(reservationId, (id, waiters) -> {
            waiters.removeIf(waiter -> {
                if (waiter.registeredAtMillis > settledAtMillis + CLOCK_TOLERANCE_MILLIS) {
                    return false;
                }
                answered.add(waiter);
                metrics.resultWaiterRemoved();
                return true;
            });
            return waiters.isEmpty() ? null : waiters;
        });
        // 대기 하나의 응답 처리가 실패해도 같은 예약의 다른 대기와 뒤따르는 알림은 그대로 간다
        for (Waiter waiter : answered) {
            try {
                waiter.onSettled.accept(status);
            } catch (RuntimeException e) {
                log.warn("결제 결과를 기다리던 조회에 답하지 못했다. reservationId={}", reservationId, e);
            }
        }
    }
}
