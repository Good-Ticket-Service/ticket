package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.dto.SeatSetupRecoveryAttempt;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.application.port.SeatSetupClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** DB 트랜잭션을 유지하지 않는 복구 HTTP 호출 조정자. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatSetupRecoveryService {
    private final SeatSetupRecoveryAttempts attempts;
    private final SeatSetupClient client;
    private final ScheduleStore schedules;
    private final Clock clock;

    public void process(Long scheduleId) {
        Optional<SeatSetupRecoveryAttempt> claimed = attempts.start(scheduleId);
        if (claimed.isEmpty()) {
            return;
        }
        SeatSetupRecoveryAttempt attempt = claimed.get();
        SeatSetupOutcome result;
        try {
            result = client.request(attempt.command());
        } catch (RuntimeException exception) {
            result = new SeatSetupOutcome.Unavailable(exception.getClass().getSimpleName());
        }
        attempts.finish(attempt, result);
    }

    public void processDue(int limit) {
        for (Long id : schedules.findSeatSetupRecoveryDueIds(LocalDateTime.now(clock), limit)) {
            try {
                process(id);
            } catch (RuntimeException exception) {
                log.error("좌석 준비 복구 처리 오류. scheduleId={}", id, exception);
            }
        }
    }
}
