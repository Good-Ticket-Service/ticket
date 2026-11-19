package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.domain.ReservedSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis 점유 좌석 중 DB에서 이미 풀린 좌석을 지우는 정합 작업(#459). 커밋 뒤 지우기(RedisSeatStateSync)가 Redis 오류로 빠졌거나,
 * 올리기(RedisSeatStateLoader)가 DB를 읽은 뒤 풀린 좌석을 올린 경우를 고친다.
 *
 * 순서: Redis를 먼저 읽고 DB를 나중에 읽는다. Redis에 있는 값은 그 예약의 DB 커밋 뒤에 들어간 것이라(결제하기 완료·올리기), 나중에 읽은
 * DB에 그 예약이 없으면 그 사이 풀린 것이다. 반대 순서로 읽으면 방금 결제하기를 끝낸 예약을 지울 수 있다.
 * 지울 때도 값이 그 예약일 때만 지운다(free-reserved.lua). 읽은 뒤 같은 좌석에 새 예약이 들어왔으면 남긴다.
 *
 * Note: DB에는 있는데 Redis에 없는 좌석은 넣지 않는다. 그쪽은 결제하기의 좌석 잠금이 막는 방향(늦은 409)이라 안전하고, 넣으려면 DB를
 * 읽은 뒤 풀린 좌석을 다시 잡힌 것으로 만들 위험이 있다.
 */
@Slf4j
@Component
public class RedisSeatStateReconciler {

    /** 정합 간격. 빠진 지우기는 드물어, 좌석을 이 시간만큼 늦게 다시 파는 것으로 충분하다. */
    static final Duration INTERVAL = Duration.ofSeconds(30);

    private final RedisSeatStateLoader loader;
    private final SeatHoldStore store;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatHoldModeSwitch modeSwitch;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seat-state-reconciler");
        thread.setDaemon(true);
        return thread;
    });

    public RedisSeatStateReconciler(
            RedisSeatStateLoader loader,
            SeatHoldStore store,
            ScheduleSeatRepository scheduleSeatRepository,
            SeatHoldModeSwitch modeSwitch) {
        this.loader = loader;
        this.store = store;
        this.scheduleSeatRepository = scheduleSeatRepository;
        this.modeSwitch = modeSwitch;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(
                this::reconcileAll, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void reconcileAll() {
        if (modeSwitch.current() != SeatHoldMode.REDIS) {
            return;
        }
        for (Long scheduleId : loader.loadedSchedules()) {
            try {
                reconcile(scheduleId);
            } catch (RuntimeException e) {
                log.warn("Redis 점유 좌석 정합에 실패했다. 다음 간격에 다시 한다. scheduleId={}", scheduleId, e);
            }
        }
    }

    /** 이 회차의 Redis 점유 좌석 중 DB에서 풀린 좌석을 지우고, 지운 수를 돌려준다. */
    public int reconcile(Long scheduleId) {
        if (!store.isLoaded(scheduleId)) {
            loader.forget(scheduleId);
            return 0;
        }
        Map<Long, Long> inRedis = store.reserved(scheduleId);
        if (inRedis.isEmpty()) {
            return 0;
        }
        Map<Long, Long> inDb = new HashMap<>();
        for (ReservedSeat seat : scheduleSeatRepository.findReservedSeats(scheduleId)) {
            inDb.put(seat.scheduleSeatId(), seat.reservationId());
        }
        Map<Long, Long> stale = new HashMap<>();
        inRedis.forEach((seatId, reservationId) -> {
            if (!Objects.equals(inDb.get(seatId), reservationId)) {
                stale.put(seatId, reservationId);
            }
        });
        int removed = store.freeReserved(scheduleId, stale);
        if (removed > 0) {
            log.info("DB에서 풀렸는데 Redis에 남은 좌석을 지웠다. scheduleId={}, removed={}", scheduleId, removed);
        }
        return removed;
    }
}
