package com.sparta.ticket.performance.infrastructure.client;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.port.SeatSetupClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 좌석 쪽 seat-setups 엔드포인트가 생기기 전까지 쓰는 임시 구현체. 호출하지 않고 성공으로 처리한다(2026-10-30 결정).
 * performance.seat-setup.client=stub일 때만 쓴다. 회차는 READY가 되지만 좌석 서비스에는 회차 좌석이 생기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "performance.seat-setup.client", havingValue = "stub")
public class StubSeatSetupClient implements SeatSetupClient {

    @Override
    public SeatSetupOutcome request(SeatSetupCommand command) {
        int sellableCount = (int)
                command.seats().stream().filter(SeatSetupCommand.Line::sellable).count();
        log.info(
                "임시 구현체: 좌석 준비를 보내지 않고 성공으로 처리한다. scheduleId={}, configVersion={}, seats={}",
                command.scheduleId(),
                command.configVersion(),
                command.seats().size());
        return new SeatSetupOutcome.Ready(
                command.requestId(), command.configVersion(), command.seats().size(), sellableCount);
    }
}
