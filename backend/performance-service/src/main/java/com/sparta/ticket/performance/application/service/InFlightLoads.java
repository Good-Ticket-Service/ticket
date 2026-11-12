package com.sparta.ticket.performance.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 같은 키를 동시에 채우는 조회를 서버 안에서 하나로 합친다(single-flight, 이슈 #398).
 * 먼저 온 요청이 로더가 되어 직접 조회하고, 그 사이 같은 키로 온 요청은 로더의 결과(값·빈 값·예외)를 함께 받는다.
 * 결과를 보관하는 캐시가 아니다. 로더가 끝나면 성공·빈 값·예외 모두 항목을 지운다.
 * Note: 서버 사이는 합치지 않는다(분산 잠금 없음). 서로 다른 서버는 같은 키를 각자 한 번씩 조회할 수 있다.
 * 기다리는 요청은 join()으로 기다려 인터럽트에 반응하지 않는다. 기다리는 쪽이 끊겨도 로더의 작업은 취소되지 않는다.
 * 기다리는 요청은 로더가 끝날 때까지 기다리므로, 로더가 늦어지면 같은 키의 요청도 함께 늦어진다.
 * Note: 지금 설정에서 로더가 끝나는 시간의 상한은 정해져 있지 않다. Hikari의 connectionTimeout(기본 30초)은 연결을 얻을 때까지만
 * 제한하고 SQL 실행 시간은 제한하지 않으며, 트랜잭션·쿼리 제한 시간은 두지 않았다. 상한이 필요하면 그 제한을 따로 정한다.
 */
final class InFlightLoads<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final Counter loads;
    private final Counter joins;

    /** name으로 로더 실행 수(role=loader)와 합쳐진 요청 수(role=joined)를 세고, 진행 중인 키 수를 name.in-flight로 낸다. */
    InFlightLoads(MeterRegistry meters, String name) {
        this.loads = meters.counter(name, "role", "loader");
        this.joins = meters.counter(name, "role", "joined");
        meters.gaugeMapSize(name + ".in-flight", List.of(), inFlight);
    }

    V load(K key, Supplier<V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            joins.increment();
            return await(running);
        }
        loads.increment();
        try {
            V value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            // 자기 항목만 지운다. 이미 지워지고 새 로더가 넣은 항목을 지우면 그 로더의 대기자가 따로 조회하게 된다
            inFlight.remove(key, mine);
        }
    }

    /** 로더가 던진 예외를 감싸지 않고 그대로 다시 던진다. */
    private V await(CompletableFuture<V> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** 테스트용. 진행 중인 키 수. */
    int size() {
        return inFlight.size();
    }
}
