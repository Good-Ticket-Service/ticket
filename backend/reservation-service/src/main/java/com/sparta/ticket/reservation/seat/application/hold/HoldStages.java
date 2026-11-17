package com.sparta.ticket.reservation.seat.application.hold;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 선점·결제하기의 단계별 시간(#449 후속 측정). 멈춘 순간의 스레드 덤프로는 어느 단계가 DB 연결을 기다렸는지만 보여, 단계마다 걸린 시간과
 * DB 연결 대기 시간을 따로 잰다.
 *
 * 지표(라벨은 고정된 값만 쓴다. 회원·holdId 같은 값은 넣지 않는다)
 *   - reservation.seat.hold.stage{flow=hold|convert, stage=..., mode=SeatHoldModeSwitch.label()}: 단계 시간
 *     hold: total, seat_read, sale_validation, redis_hold, db_tx, lock
 *     convert: total, begin, find, seat_read, db_tx, lock, finish
 *     lock은 db_tx 안의 좌석 잠금 쿼리(잠금 대기 포함)다.
 *   - reservation.seat.hold.conn_wait{flow, stage, mode}: DB 연결을 얻기까지 걸린 시간. 연결을 요청한 때 진행 중이던 단계로 나눈다.
 *     연결 대기는 측정 서버가 DataSource를 감싸 {@link #connectionWait}로 알린다.
 *
 * Note: 단계는 요청 스레드에 한 겹씩 쌓는다. 안쪽 단계가 끝나면 바깥 단계로 돌아간다. 예외로 끝난 단계도 기록한다.
 */
@Component
public class HoldStages {

    private static final ThreadLocal<String[]> CURRENT = new ThreadLocal<>();

    private static volatile HoldStages instance;

    private final MeterRegistry meters;
    private final SeatHoldModeSwitch modeSwitch;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public HoldStages(MeterRegistry meters, SeatHoldModeSwitch modeSwitch) {
        this.meters = meters;
        this.modeSwitch = modeSwitch;
        instance = this;
    }

    public <T> T time(String flow, String stage, Supplier<T> work) {
        String[] previous = CURRENT.get();
        CURRENT.set(new String[] {flow, stage});
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            timer("reservation.seat.hold.stage", flow, stage).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public void run(String flow, String stage, Runnable work) {
        time(flow, stage, () -> {
            work.run();
            return null;
        });
    }

    /** DB 연결을 얻은 시간. 선점·결제하기 단계 밖(조회 API 등)이면 기록하지 않는다. */
    public static void connectionWait(long nanos) {
        String[] current = CURRENT.get();
        HoldStages stages = instance;
        if (current == null || stages == null) {
            return;
        }
        stages.timer("reservation.seat.hold.conn_wait", current[0], current[1]).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 연결을 얻은 순간의 흐름·단계·모드를 기억하고 풀에 돌려줄 때 점유 시간을 기록한다. */
    public static ConnectionUsage connectionUsageStarted() {
        HoldStages stages = instance;
        if (stages == null) return new ConnectionUsage(null, "other", "other", "DB", System.nanoTime());
        String[] current = CURRENT.get();
        String flow = current == null ? "other" : current[0];
        String stage = current == null ? "other" : current[1];
        return new ConnectionUsage(stages, flow, stage, stages.modeSwitch.label(), System.nanoTime());
    }

    public static final class ConnectionUsage {
        private final HoldStages stages;
        private final String flow;
        private final String stage;
        private final String mode;
        private final long started;
        private final AtomicBoolean recorded = new AtomicBoolean();

        private ConnectionUsage(HoldStages stages, String flow, String stage, String mode, long started) {
            this.stages = stages;
            this.flow = flow;
            this.stage = stage;
            this.mode = mode;
            this.started = started;
        }

        public void record() {
            if (stages != null && recorded.compareAndSet(false, true)) {
                stages.timer("reservation.seat.hold.conn_usage", flow, stage, mode)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        }
    }

    private Timer timer(String name, String flow, String stage) {
        // 실험 경로는 모드 라벨을 따로 둔다(REDIS1C, REDIS-S)
        return timer(name, flow, stage, modeSwitch.label());
    }

    private Timer timer(String name, String flow, String stage, String mode) {
        return timers.computeIfAbsent(
                name + "|" + flow + "|" + stage + "|" + mode,
                key -> Timer.builder(name)
                        .tag("flow", flow)
                        .tag("stage", stage)
                        .tag("mode", mode)
                        .publishPercentileHistogram()
                        .register(meters));
    }
}
