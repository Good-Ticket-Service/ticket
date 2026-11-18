package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.port.HoldSeatCache;
import com.sparta.ticket.reservation.seat.domain.SeatHoldSnapshot;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 선점용 좌석 고정 정보를 서버 메모리에 두는 캐시(#459).
 *
 * Note: 고정 정보는 판매 중에 바뀌지 않지만 좌석 구성 변경(재준비)은 판매 전에 일어날 수 있다. 그 서버에서는 커밋 뒤 지우고(SeatSetupService),
 * 다른 서버는 TTL로 낡은 값을 버린다. 낡은 값으로 선점하면 판매 검증의 구성 버전 불일치나 결제하기 트랜잭션의 재판정이 409로 막는다.
 * Note: 회차 하나가 2만 석이면 수 MB라 회차 수 상한을 작게 둔다. 판매 중인 회차만 들어온다.
 */
@Component
public class CaffeineHoldSeatCache implements HoldSeatCache {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long MAX_SCHEDULES = 50;

    private final Cache<Long, Map<Long, SeatHoldSnapshot>> cache;

    public CaffeineHoldSeatCache(Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(TTL)
                .maximumSize(MAX_SCHEDULES)
                .build();
    }

    @Override
    public Map<Long, SeatHoldSnapshot> getOrLoad(Long scheduleId, Supplier<Map<Long, SeatHoldSnapshot>> loader) {
        return cache.get(scheduleId, id -> loader.get());
    }

    @Override
    public void invalidate(Long scheduleId) {
        cache.invalidate(scheduleId);
    }
}
