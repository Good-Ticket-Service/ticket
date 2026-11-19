package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.domain.ReservedSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 회차의 DB 점유 좌석(HOLD·SOLD)을 Redis에 올린다(#449 후속). 다 올린 회차는 선점이 DB 좌석 상태를 읽지 않고 Redis로 판정한다.
 *
 * 올리는 때
 *   - 판매 전·측정 준비: {@link #reload}로 지우고 다시 올린다. 트래픽이 없을 때라 경합이 없다.
 *   - 선점이 아직 올리지 않은 회차를 만남: {@link #requestLoad}로 전용 스레드 하나에서 올린다. 그동안 선점은 DB 좌석 읽기로 판정한다.
 *
 * Note: DB를 읽은 뒤 Redis에 쓰기 전에 DB에서 좌석이 풀리면, 이미 풀린 좌석을 Redis에 잡힌 것으로 올릴 수 있다. 좌석을 못 파는 쪽으로만
 * 틀리고 이중 판매는 결제하기의 좌석 잠금이 막는다. 이렇게 남은 값은 정합 작업(RedisSeatStateReconciler)이 지운다.
 */
@Slf4j
@Component
public class RedisSeatStateLoader {

    /** 올린 값과 표시의 수명. 사라지면 다음 선점이 다시 올리게 한다. 회차 판매 기간을 모르는 값이라 하루로 둔다. */
    static final Duration STATE_TTL = Duration.ofDays(1);

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatHoldStore store;
    private final Set<Long> loading = ConcurrentHashMap.newKeySet();

    /** 이 서버가 올린 회차. 정합 작업이 돈다. 서버가 여러 대면 각자 올린 회차를 맞춘다(같은 회차를 여럿이 맞춰도 결과는 같다). */
    private final Set<Long> loaded = ConcurrentHashMap.newKeySet();

    /** 올리는 일에 쓰는 DB 연결을 하나로 묶는다. 대기는 회차 수만큼이면 충분하고, 넘치면 다음 선점이 다시 요청한다. */
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "seat-state-loader");
                thread.setDaemon(true);
                return thread;
            });

    public RedisSeatStateLoader(ScheduleSeatRepository scheduleSeatRepository, SeatHoldStore store) {
        this.scheduleSeatRepository = scheduleSeatRepository;
        this.store = store;
    }

    /** 이 회차를 지금 스레드에서 올린다. 이미 있는 좌석 값은 덮지 않는다. */
    public void load(Long scheduleId) {
        Map<Long, Long> seats = new LinkedHashMap<>();
        for (ReservedSeat seat : scheduleSeatRepository.findReservedSeats(scheduleId)) {
            seats.put(seat.scheduleSeatId(), seat.reservationId());
        }
        store.loadReserved(scheduleId, seats, true, STATE_TTL);
        loaded.add(scheduleId);
        log.info("DB 점유 좌석을 Redis에 올렸다. scheduleId={}, seats={}", scheduleId, seats.size());
    }

    /** 이 서버가 올린 회차들. */
    public Set<Long> loadedSchedules() {
        return Set.copyOf(loaded);
    }

    /** 올림 표시가 사라진 회차를 정합 대상에서 뺀다. 다음 선점이 다시 올리면 다시 들어온다. */
    void forget(Long scheduleId) {
        loaded.remove(scheduleId);
    }

    /** 지우고 다시 올린다. 판매 전·측정 준비처럼 그 회차에 선점이 없을 때만 쓴다. */
    public void reload(Long scheduleId) {
        store.clearReserved(scheduleId);
        load(scheduleId);
    }

    /** 전용 스레드에서 올린다. 같은 회차를 이미 올리는 중이면 아무것도 하지 않는다. */
    public void requestLoad(Long scheduleId) {
        if (!loading.add(scheduleId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    load(scheduleId);
                } catch (RuntimeException e) {
                    log.warn("DB 점유 좌석을 Redis에 올리지 못했다. 다음 선점이 다시 요청한다. scheduleId={}", scheduleId, e);
                } finally {
                    loading.remove(scheduleId);
                }
            });
        } catch (RejectedExecutionException e) {
            loading.remove(scheduleId);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
