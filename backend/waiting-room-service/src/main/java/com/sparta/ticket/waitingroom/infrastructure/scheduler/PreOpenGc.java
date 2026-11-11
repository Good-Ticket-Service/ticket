package com.sparta.ticket.waitingroom.infrastructure.scheduler;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.waitingroom.domain.WaitingRoom;
import com.sparta.ticket.waitingroom.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * room 오픈 lead 전에 JVM GC를 한 번 부른다. 오픈 직후 동시 진입 중에 young GC 멈춤(130~150ms)이 끼지 않게, 힙을 미리 비워 둔다.
 * 1초마다 끝나지 않은 room을 보고, 오픈까지 lead 이하로 남은 room이 처음 보이면 GC를 부른다. 같은 때 열리는 room이 여럿이면 GC는 한 번이다.
 *
 * Note: System.gc()는 G1 기본 설정에서 Full GC라 서버 전체가 잠깐 멈춘다(EC2 c7i.large·힙 2GB 측정 231~248ms). 오픈 전이라 진입은 없지만
 * 다른 room의 순번 조회는 그만큼 늦어진다. -XX:+DisableExplicitGC를 켜면 아무 일도 하지 않는다.
 * Note: 서버가 여러 대면 서버마다 자기 JVM에서 한 번씩 부른다. 서버끼리 맞출 일은 없다.
 */
@Slf4j
public class PreOpenGc implements SmartLifecycle {

    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);

    private final WaitingRoomRepository waitingRoomRepository;
    private final Clock clock;
    private final Duration lead;
    private final Runnable gc;
    private final Set<Long> collectedRoomIds = ConcurrentHashMap.newKeySet();
    private ThreadPoolTaskScheduler scheduler;

    public PreOpenGc(WaitingRoomRepository waitingRoomRepository, Clock clock, Duration lead, Runnable gc) {
        this.waitingRoomRepository = waitingRoomRepository;
        this.clock = clock;
        this.lead = lead;
        this.gc = gc;
    }

    /** 오픈까지 lead 이하로 남았고 아직 GC를 부르지 않은 room이 있으면 GC를 한 번 부른다. */
    void check() {
        List<WaitingRoom> rooms;
        try {
            rooms = waitingRoomRepository.findActive();
        } catch (BusinessException e) {
            log.warn("오픈 전 GC: room 목록을 읽지 못했다. 다음 확인에서 다시 본다.", e);
            return;
        }
        Instant now = clock.instant();
        List<Long> due = rooms.stream()
                .filter(room -> isDue(room, now))
                .map(WaitingRoom::roomId)
                .filter(roomId -> !collectedRoomIds.contains(roomId))
                .toList();
        // 끝난 room은 목록에서 빠지므로 기억한 ID도 같이 지운다
        collectedRoomIds.retainAll(rooms.stream().map(WaitingRoom::roomId).toList());
        if (due.isEmpty()) {
            return;
        }
        collectedRoomIds.addAll(due);
        long started = System.nanoTime();
        gc.run();
        log.info("오픈 전 GC: room {} 오픈 {} 전, {}ms", due, lead, (System.nanoTime() - started) / 1_000_000);
    }

    private boolean isDue(WaitingRoom room, Instant now) {
        Duration left = Duration.between(now, room.openAt());
        return !left.isNegative() && left.compareTo(lead) <= 0;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("pre-open-gc-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::check, CHECK_INTERVAL);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
