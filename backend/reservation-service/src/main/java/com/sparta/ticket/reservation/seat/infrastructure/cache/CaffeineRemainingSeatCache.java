package com.sparta.ticket.reservation.seat.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sparta.ticket.reservation.seat.application.port.RemainingSeatCache;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeatProperties;
import com.sparta.ticket.reservation.seat.application.remaining.RemainingSeats;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 등급별 잔여석을 서버 메모리에 짧게 두는 캐시. gateway의 사용자 조회 캐시(CachedUserLookup)와 같은 Caffeine을 쓴다.
 *
 * Note: Redis 같은 공유 캐시를 쓰지 않는 이유. 화면 안내용이라 서버마다 캐시가 따로여도 각자 TTL만큼만 낡아 문제없고, 서버가 N대여도
 * 회차당 DB 집계는 TTL마다 N번이다. 서버 간에 같은 값을 보여 줘야 하거나 선점·해제 때 바로 지워야 하면 공유 캐시를 다시 검토한다.
 *
 * Note: Cache.get(key, loader)는 같은 키를 동시에 요청하면 한 요청만 loader를 실행하고 나머지는 그 결과를 기다려 받는다.
 * 캐시가 만료된 순간 요청이 몰려도 집계는 한 번이다. loader가 예외를 던지면 저장하지 않아 다음 요청이 다시 집계한다.
 * gateway는 WebFlux라 AsyncCache를 쓰지만 reservation-service는 MVC라 동기 Cache로 같은 효과를 낸다.
 */
@Component
public class CaffeineRemainingSeatCache implements RemainingSeatCache {

    /** 캐시에 둘 회차 수 상한. 지난 회차가 메모리에 계속 쌓이지 않게 한다. TTL이 짧아 보통은 닿지 않는다. */
    private static final long MAX_SCHEDULES = 10_000;

    private final Cache<Long, RemainingSeats> cache;

    public CaffeineRemainingSeatCache(RemainingSeatProperties properties, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(MAX_SCHEDULES)
                .build();
    }

    @Override
    public Lookup getOrLoad(Long scheduleId, Supplier<RemainingSeats> loader) {
        // 이번 호출이 loader를 실행했는지로 cached를 정한다. 다른 요청의 집계를 기다려 받은 경우도 cached다
        AtomicBoolean loaded = new AtomicBoolean(false);
        RemainingSeats seats = cache.get(scheduleId, id -> {
            loaded.set(true);
            return loader.get();
        });
        return new Lookup(seats, !loaded.get());
    }
}
