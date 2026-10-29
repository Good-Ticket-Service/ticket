package com.sparta.ticket.fakepg.application.service.fault;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.fault.FaultConfig;
import com.sparta.ticket.fakepg.domain.model.fault.FaultStats;
import com.sparta.ticket.fakepg.domain.model.fault.TamperMode;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리 API로 건 장애 설정을 보관하고, 요청 처리 중 장애를 일으킨다. 설정은 메모리에만 두며 재시작하면 "장애 없음"으로 돌아간다.
 * 장애가 실제로 걸린 횟수를 함께 센다. 횟수도 메모리에만 두며, 설정 초기화와 따로 비운다.
 */
@Component
@RequiredArgsConstructor
public class FaultInjector {

    private final Map<Target, FaultConfig> configs = new ConcurrentHashMap<>();
    private final Map<Target, RateWindow> windows = new ConcurrentHashMap<>();
    private final Map<Target, Counters> counters = new ConcurrentHashMap<>();
    private final FaultRandom faultRandom;
    private final Clock clock;

    public FaultConfig get(PgProvider provider, PgOperation operation) {
        return configs.getOrDefault(new Target(provider, operation), FaultConfig.none());
    }

    public void set(PgProvider provider, PgOperation operation, FaultConfig config) {
        configs.put(new Target(provider, operation), config);
        windows.remove(new Target(provider, operation));
    }

    public void reset() {
        configs.clear();
        windows.clear();
    }

    /** 설정한 장애만 "제공자.동작" 이름순으로 돌려준다. */
    public Map<String, FaultConfig> all() {
        Map<String, FaultConfig> result = new TreeMap<>();
        configs.forEach((target, config) -> result.put(target.provider() + "." + target.operation(), config));
        return result;
    }

    /** 장애가 걸린 횟수를 "제공자.동작" 이름순으로 돌려준다. 요청이 한 번도 없던 API는 빠진다. */
    public Map<String, FaultStats> stats() {
        Map<String, FaultStats> result = new TreeMap<>();
        counters.forEach((target, count) -> result.put(target.provider() + "." + target.operation(), count.snapshot()));
        return result;
    }

    public void resetStats() {
        counters.clear();
    }

    /** null이면 실행마다 다른 난수. 판단 순서도 처음부터 다시 시작한다. */
    public void reseed(Long seed) {
        faultRandom.reseed(seed);
    }

    public Long seed() {
        return faultRandom.seed();
    }

    /** 초당 처리 한도를 넘으면 처리하지 않고 429. 1초 단위 고정 창으로 센다. 모든 요청의 첫 단계라 요청 수도 여기서 센다. */
    public void checkRateLimit(PgProvider provider, PgOperation operation) {
        Counters count = count(provider, operation);
        count.requests.increment();
        int limit = get(provider, operation).rateLimitPerSecond();
        if (limit <= 0) {
            return;
        }
        long second = clock.millis() / 1000;
        RateWindow window = windows.computeIfAbsent(new Target(provider, operation), target -> new RateWindow());
        if (!window.tryAcquire(second, limit)) {
            count.rateLimited.increment();
            throw new PgException(PgErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /** 처리 전에 지연시키고, 확률에 따라 처리하지 않은 채 500을 낸다. */
    public void beforeProcessing(PgProvider provider, PgOperation operation) {
        FaultConfig config = get(provider, operation);
        if (config.latencyMs() > 0) {
            count(provider, operation).latencyApplied.increment();
        }
        sleep(config.latencyMs());
        if (hit(config.serverErrorRate())) {
            count(provider, operation).serverErrors.increment();
            throw new PgException(PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING);
        }
    }

    public boolean shouldFail(PgProvider provider, PgOperation operation) {
        return counted(hit(get(provider, operation).failRate()), count(provider, operation).rejections);
    }

    public boolean shouldDelayApproval(PgProvider provider) {
        return counted(
                hit(get(provider, PgOperation.CONFIRM).delayedApprovalRate()),
                count(provider, PgOperation.CONFIRM).delayedApprovals);
    }

    public long delayedApprovalMs(PgProvider provider) {
        return get(provider, PgOperation.CONFIRM).delayedApprovalMs();
    }

    public boolean shouldLoseResponse(PgProvider provider, PgOperation operation) {
        return counted(hit(get(provider, operation).responseLossRate()), count(provider, operation).responseLosses);
    }

    /** 확률에 따라 성공 응답을 망가뜨릴 방식을 돌려준다. 처리 결과는 바꾸지 않는다. */
    public Optional<TamperMode> tamper(PgProvider provider, PgOperation operation) {
        FaultConfig config = get(provider, operation);
        return counted(hit(config.tamperRate()), count(provider, operation).tampered)
                ? Optional.of(config.tamperMode())
                : Optional.empty();
    }

    /** 응답을 붙잡아 클라이언트의 읽기 타임아웃을 일으킨다. */
    public void hold(PgProvider provider, PgOperation operation) {
        sleep(get(provider, operation).holdMs());
    }

    private Counters count(PgProvider provider, PgOperation operation) {
        return counters.computeIfAbsent(new Target(provider, operation), target -> new Counters());
    }

    private static boolean counted(boolean hit, LongAdder counter) {
        if (hit) {
            counter.increment();
        }
        return hit;
    }

    private boolean hit(double rate) {
        return rate > 0 && (rate >= 1 || faultRandom.nextDouble() < rate);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Target(PgProvider provider, PgOperation operation) {}

    private static final class Counters {

        private final LongAdder requests = new LongAdder();
        private final LongAdder latencyApplied = new LongAdder();
        private final LongAdder rateLimited = new LongAdder();
        private final LongAdder serverErrors = new LongAdder();
        private final LongAdder rejections = new LongAdder();
        private final LongAdder delayedApprovals = new LongAdder();
        private final LongAdder responseLosses = new LongAdder();
        private final LongAdder tampered = new LongAdder();

        FaultStats snapshot() {
            return new FaultStats(
                    requests.sum(),
                    latencyApplied.sum(),
                    rateLimited.sum(),
                    serverErrors.sum(),
                    rejections.sum(),
                    delayedApprovals.sum(),
                    responseLosses.sum(),
                    tampered.sum());
        }
    }

    private static final class RateWindow {

        private long second = -1;
        private int count;

        synchronized boolean tryAcquire(long now, int limit) {
            if (now != second) {
                second = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }
}
