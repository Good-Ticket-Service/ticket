package com.sparta.ticket.gateway.queue;

import com.sparta.ticket.gateway.queue.WaitingRoomClient.ProtectedRoom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 대기열이 보호하는 scope 목록. 기동하면 바로 한 번, 그 뒤 정해진 간격마다 reservation-service에서 받아 메모리에 둔다.
 * 좌석 요청마다 대기열 서비스를 부르지 않으려는 것이다. room은 생성 뒤 바뀌지 않고 openAt이 지금 + 1분 이후라 오픈 전에 목록에 들어온다.
 *
 * Note: 받기에 실패하면 마지막 목록을 그대로 쓰고 다음 간격에 다시 받는다. 한 번도 받지 못했으면 UNKNOWN이고, 검사하는 쪽은
 * 대기열 없이 통과시키지 않고 503으로 거절한다(fail-closed). 보호 끝(closeAt)은 목록을 받은 시각이 아니라 요청 시각으로 비교하므로,
 * 목록이 오래돼도 끝난 room의 scope는 제때 풀린다.
 */
@Slf4j
public class ProtectedScopes implements SmartLifecycle {

    public enum Protection {
        /** 보호 중인 room의 scope다. 입장 토큰을 검사한다. */
        PROTECTED,
        /** 보호하는 room이 없거나 끝났다. 입장 토큰 없이 통과한다. */
        UNPROTECTED,
        /** 목록을 한 번도 받지 못해 알 수 없다. */
        UNKNOWN
    }

    private final Supplier<Mono<List<ProtectedRoom>>> loader;
    private final Duration interval;
    private final Clock clock;

    /** scope → 보호 끝 시각. null이면 아직 한 번도 받지 못했다. 통째로 바꿔 끼우므로 읽는 쪽은 잠금이 필요 없다. */
    private volatile Map<String, Instant> closeAtByScope;

    private volatile Disposable polling;

    public ProtectedScopes(Supplier<Mono<List<ProtectedRoom>>> loader, Duration interval, Clock clock) {
        this.loader = loader;
        this.interval = interval;
        this.clock = clock;
    }

    public Protection protection(String scope) {
        Map<String, Instant> current = closeAtByScope;
        if (current == null) {
            return Protection.UNKNOWN;
        }
        Instant closeAt = current.get(scope);
        return closeAt != null && clock.instant().isBefore(closeAt) ? Protection.PROTECTED : Protection.UNPROTECTED;
    }

    /** 목록을 한 번 받아 바꿔 끼운다. 실패하면 경고만 남기고 마지막 목록을 둔다. 에러로 끝나지 않으므로 반복 구독이 멈추지 않는다. */
    public Mono<Void> refresh() {
        return Mono.defer(loader).doOnNext(this::replace).then().onErrorResume(e -> {
            log.warn("대기열 보호 목록을 받지 못해 마지막 목록을 계속 쓴다: {}", e.toString());
            return Mono.empty();
        });
    }

    /** 끝나지 않은 room끼리는 scope가 겹치지 않지만(생성 때 409), 겹쳐 오더라도 늦게 끝나는 쪽을 따른다. */
    private void replace(List<ProtectedRoom> rooms) {
        Map<String, Instant> next = new HashMap<>();
        rooms.forEach(room ->
                room.scopes().forEach(scope -> next.merge(scope, room.closeAt(), (a, b) -> a.isAfter(b) ? a : b)));
        closeAtByScope = Map.copyOf(next);
    }

    /** 받기와 대기를 이어 반복한다. 다음 받기는 앞 받기가 끝난 뒤 간격만큼 기다렸다가 하므로 겹쳐 돌지 않는다. */
    @Override
    public void start() {
        polling = refresh().then(Mono.delay(interval)).repeat().subscribe();
    }

    @Override
    public void stop() {
        Disposable current = polling;
        if (current != null) {
            current.dispose();
        }
        polling = null;
    }

    @Override
    public boolean isRunning() {
        Disposable current = polling;
        return current != null && !current.isDisposed();
    }
}
