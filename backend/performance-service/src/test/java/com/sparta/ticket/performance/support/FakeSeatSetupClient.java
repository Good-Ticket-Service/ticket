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
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * 통합 테스트용 좌석 준비 구현체. 정해 둔 결과를 차례로 돌려주고, 없으면 요청을 그대로 받아 성공(Ready)으로 답한다.
 * 받은 요청과, 호출되는 순간 사용 중이던 DB 커넥션 수를 기록한다(좌석 호출 중 커넥션을 쥐지 않는지 확인).
 */
public class FakeSeatSetupClient implements SeatSetupClient {

    private final HikariDataSource dataSource;
    private final Deque<Function<SeatSetupCommand, SeatSetupOutcome>> answers = new ArrayDeque<>();
    private final List<SeatSetupCommand> requests = new ArrayList<>();
    private final List<Integer> activeConnections = new ArrayList<>();

    public FakeSeatSetupClient(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource.unwrap(HikariDataSource.class);
    }

    @Override
    public synchronized SeatSetupOutcome request(SeatSetupCommand command) {
        requests.add(command);
        activeConnections.add(dataSource.getHikariPoolMXBean().getActiveConnections());
        Function<SeatSetupCommand, SeatSetupOutcome> answer = answers.poll();
        return answer == null ? ready(command) : answer.apply(command);
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

    public synchronized List<SeatSetupCommand> requests() {
        return List.copyOf(requests);
    }

    public synchronized List<Integer> activeConnections() {
        return List.copyOf(activeConnections);
    }

    public synchronized void reset() {
        answers.clear();
        requests.clear();
        activeConnections.clear();
    }
}
