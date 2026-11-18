package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.domain.ReservedSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis에 올린 DB 점유 좌석을 DB와 맞추는 정합 작업(#459, #476). 30초마다, 그리고 서버를 띄운 뒤 선점이 처음 쓴 회차는 바로 한 번 돈다.
 *
 * 하는 일
 *   1. DB에서 풀렸는데 Redis에 남은 좌석을 지운다. 커밋 뒤 지우기(RedisSeatStateSync)가 Redis 오류로 빠졌거나, 올리기(RedisSeatStateLoader)가
 *      DB를 읽은 뒤 풀린 좌석을 올린 경우다. Redis를 먼저 읽고 DB를 나중에 읽는다. Redis에 있는 값은 그 예약의 DB 커밋 뒤에 들어간 것이라
 *      (결제하기 완료·올리기), 나중에 읽은 DB에 그 예약이 없으면 그 사이 풀린 것이다. 반대 순서로 읽으면 방금 결제하기를 끝낸 예약을 지울 수
 *      있다. 지울 때도 값이 그 예약일 때만 지운다(free-reserved.lua).
 *   2. DB에서 잡혔는데 Redis에 없는 좌석을 채운다. DB 모드로 바꿨다가 되돌려 그동안의 선점·판매가 Redis에 없는 경우다. 비어 있다고 틀리면
 *      다른 회원이 선점한 뒤 좌석 선택 완료에서 409를 받는다. 이미 있는 값은 덮지 않는다(HSETNX). 채운 뒤 DB를 다시 읽어 그 사이 풀린
 *      좌석은 지운다. 채우기 전에 풀려 커밋 뒤 지우기가 먼저 지나간 좌석을 잡힌 채로 남기지 않기 위해서다.
 *   3. 올림 표시와 올린 좌석의 수명을 늘린다. 판매 중인 회차가 하루 수명으로 사라져 다시 올리지 않게 한다. 하루 동안 선점이 쓰지 않은
 *      회차는 늘리지 않고 정합 대상에서 빼, 키가 수명대로 사라지게 둔다.
 *
 * 맞추기 전에는 어긋난 값으로 판정한다. 어느 쪽이든 이중 판매는 결제하기의 좌석 잠금이 막는다.
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
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seat-state-reconciler");
        thread.setDaemon(true);
        return thread;
    });

    public RedisSeatStateReconciler(
            RedisSeatStateLoader loader,
            SeatHoldStore store,
            ScheduleSeatRepository scheduleSeatRepository,
            SeatHoldModeSwitch modeSwitch,
            Clock clock) {
        this.loader = loader;
        this.store = store;
        this.scheduleSeatRepository = scheduleSeatRepository;
        this.modeSwitch = modeSwitch;
        this.clock = clock;
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

    /**
     * 선점이 이 회차의 올린 값을 썼다. 이 서버에서 처음 쓴 회차면 다음 간격을 기다리지 않고 정합을 한 번 돌린다. 서버를 다시 띄운 뒤
     * 표시가 남은 회차를 바로 맞추기 위해서다.
     */
    void track(Long scheduleId) {
        if (!loader.markUsed(scheduleId)) {
            return;
        }
        try {
            scheduler.execute(() -> reconcileSafely(scheduleId));
        } catch (RejectedExecutionException e) {
            // 서버 종료 중이다
        }
    }

    void reconcileAll() {
        if (modeSwitch.current() != SeatHoldMode.REDIS) {
            return;
        }
        Instant idleSince = clock.instant().minus(RedisSeatStateLoader.STATE_TTL);
        loader.usedSchedules().forEach((scheduleId, lastUsed) -> {
            if (lastUsed.isBefore(idleSince)) {
                loader.forget(scheduleId);
                return;
            }
            reconcileSafely(scheduleId);
        });
    }

    private void reconcileSafely(Long scheduleId) {
        try {
            reconcile(scheduleId);
        } catch (RuntimeException e) {
            log.warn("Redis 점유 좌석 정합에 실패했다. 다음 간격에 다시 한다. scheduleId={}", scheduleId, e);
        }
    }

    /** 이 회차의 Redis 점유 좌석을 DB와 맞추고, 지운 좌석 수를 돌려준다. 올림 표시가 없으면 정합 대상에서 빼고 0이다. */
    public int reconcile(Long scheduleId) {
        if (!store.isLoaded(scheduleId)) {
            loader.forget(scheduleId);
            return 0;
        }
        Map<Long, Long> inRedis = store.reserved(scheduleId);
        Map<Long, Long> inDb = reservedInDb(scheduleId);

        // 1. DB에서 풀렸는데 Redis에 남은 좌석
        Map<Long, Long> stale = new HashMap<>();
        inRedis.forEach((seatId, reservationId) -> {
            if (!Objects.equals(inDb.get(seatId), reservationId)) {
                stale.put(seatId, reservationId);
            }
        });
        int removed = stale.isEmpty() ? 0 : store.freeReserved(scheduleId, stale);

        // 2. DB에서 잡혔는데 Redis에 없는 좌석
        Map<Long, Long> missing = new HashMap<>();
        inDb.forEach((seatId, reservationId) -> {
            if (!inRedis.containsKey(seatId)) {
                missing.put(seatId, reservationId);
            }
        });
        int filled = 0;
        if (!missing.isEmpty()) {
            store.loadReserved(scheduleId, missing, false, RedisSeatStateLoader.STATE_TTL);
            Map<Long, Long> recheck = reservedInDb(scheduleId);
            Map<Long, Long> releasedMeanwhile = new HashMap<>();
            missing.forEach((seatId, reservationId) -> {
                if (!Objects.equals(recheck.get(seatId), reservationId)) {
                    releasedMeanwhile.put(seatId, reservationId);
                }
            });
            if (!releasedMeanwhile.isEmpty()) {
                store.freeReserved(scheduleId, releasedMeanwhile);
            }
            filled = missing.size() - releasedMeanwhile.size();
        }

        // 3. 수명
        store.extendReserved(scheduleId, RedisSeatStateLoader.STATE_TTL);

        if (removed > 0 || filled > 0) {
            log.info("Redis 점유 좌석을 DB와 맞췄다. scheduleId={}, removed={}, filled={}", scheduleId, removed, filled);
        }
        return removed;
    }

    private Map<Long, Long> reservedInDb(Long scheduleId) {
        Map<Long, Long> seats = new HashMap<>();
        for (ReservedSeat seat : scheduleSeatRepository.findReservedSeats(scheduleId)) {
            seats.put(seat.scheduleSeatId(), seat.reservationId());
        }
        return seats;
    }
}
