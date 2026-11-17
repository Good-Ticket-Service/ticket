package com.sparta.ticket.performance.support;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.port.SeatSetupClient;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * 통합 테스트용 좌석 준비 구현체. 정해 둔 결과를 차례로 돌려주고, 없으면 요청을 그대로 받아 성공(Ready)으로 답한다.
 * 받은 요청과, 호출되는 순간 사용 중이던 DB 커넥션 수를 기록한다(좌석 호출 중 커넥션을 쥐지 않는지 확인).
 * 비동기 시험(이슈 #466)을 위해 응답을 문(gate)으로 막을 수 있고, 동시에 진행 중인 호출 수와 그 최댓값을 센다.
 * Note: 기록만 잠금 안에서 하고, 응답을 만드는 일(막힌 문 기다리기 포함)은 잠금 밖에서 한다. 잠금 안에서 기다리면 다른 스레드의
 * 호출이 들어오지 못해 동시 호출 수를 셀 수 없다.
 */
public class FakeSeatSetupClient implements SeatSetupClient {

    private static final long GATE_TIMEOUT_SECONDS = 10;

    private final HikariDataSource dataSource;
    private final Deque<Function<SeatSetupCommand, SeatSetupOutcome>> answers = new ArrayDeque<>();
    private final List<SeatSetupCommand> requests = new ArrayList<>();
    private final List<Integer> activeConnections = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final Semaphore started = new Semaphore(0);
    private CountDownLatch gate;

    public FakeSeatSetupClient(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource.unwrap(HikariDataSource.class);
    }

    @Override
    public SeatSetupOutcome request(SeatSetupCommand command) {
        Function<SeatSetupCommand, SeatSetupOutcome> answer;
        CountDownLatch currentGate;
        synchronized (this) {
            requests.add(command);
            activeConnections.add(dataSource.getHikariPoolMXBean().getActiveConnections());
            answer = answers.poll();
            currentGate = gate;
        }
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        started.release();
        try {
            if (currentGate != null) {
                awaitGate(currentGate);
            }
            return answer == null ? ready(command) : answer.apply(command);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void awaitGate(CountDownLatch currentGate) {
        try {
            if (!currentGate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("좌석 응답 문이 제한 시간 안에 열리지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static SeatSetupOutcome ready(SeatSetupCommand command) {
        int sellable = (int)
                command.seats().stream().filter(SeatSetupCommand.Line::sellable).count();
        return new SeatSetupOutcome.Ready(
                command.requestId(), command.configVersion(), command.seats().size(), sellable);
    }

    /** 다음 호출의 결과를 정한다. 여러 번 부르면 순서대로 쓴다. */
    public synchronized void answer(Function<SeatSetupCommand, SeatSetupOutcome> answer) {
        answers.add(answer);
    }

    public synchronized void answer(SeatSetupOutcome outcome) {
        answers.add(command -> outcome);
    }

    /** 이후 호출이 {@link #openGate()} 전까지 응답하지 않고 기다리게 한다(최대 10초). */
    public synchronized void closeGate() {
        gate = new CountDownLatch(1);
    }

    /** 막아 둔 호출을 모두 풀고, 이후 호출은 기다리지 않게 한다. */
    public synchronized void openGate() {
        if (gate != null) {
            gate.countDown();
            gate = null;
        }
    }

    /** 호출이 count번 시작될 때까지 기다린다. 제한 시간 안에 시작되면 true. */
    public boolean awaitStarted(int count, long timeoutSeconds) throws InterruptedException {
        return started.tryAcquire(count, timeoutSeconds, TimeUnit.SECONDS);
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public synchronized List<SeatSetupCommand> requests() {
        return List.copyOf(requests);
    }

    public synchronized List<Integer> activeConnections() {
        return List.copyOf(activeConnections);
    }

    public synchronized void reset() {
        openGate();
        answers.clear();
        requests.clear();
        activeConnections.clear();
        inFlight.set(0);
        maxInFlight.set(0);
        started.drainPermits();
    }
}
