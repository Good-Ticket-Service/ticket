package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.application.port.SeatSetupClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회차 좌석 준비 한 번 = 시작(트랜잭션) → 좌석 호출(트랜잭션 밖) → 결과 반영(트랜잭션). 커밋 뒤 첫 호출과 자동 재시도가 같은 경로를 쓴다.
 * Note: 이 클래스에는 @Transactional을 붙이지 않는다. 좌석 호출 동안 DB 커넥션을 쥐지 않기 위해서다(팀 규칙).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatSetupProcessor {

    private final SeatSetupAttempts attempts;
    private final SeatSetupClient seatSetupClient;
    private final ScheduleStore scheduleStore;
    private final Clock clock;

    public void process(Long scheduleId) {
        Optional<SeatSetupCommand> command = attempts.start(scheduleId);
        if (command.isEmpty()) {
            return;
        }
        SeatSetupOutcome outcome;
        try {
            outcome = seatSetupClient.request(command.get());
        } catch (RuntimeException e) {
            // 포트 구현은 예외를 결과로 바꾸지만, 예상 못 한 예외도 일시 실패로 보고 다시 보낸다
            outcome = new SeatSetupOutcome.Unavailable(e.toString());
        }
        attempts.finish(command.get(), outcome);
    }

    /** 다음 시도 시각이 지난 PENDING 회차를 최대 limit개 처리한다. 재시도 스케줄러가 부른다. 처리한 회차 수를 돌려준다. */
    public int processDue(int limit) {
        List<Long> scheduleIds = scheduleStore.findSeatSetupDueIds(LocalDateTime.now(clock), limit);
        for (Long scheduleId : scheduleIds) {
            processSafely(scheduleId);
        }
        return scheduleIds.size();
    }

    /** 한 회차의 실패가 다른 회차 처리나 호출한 쪽(커밋된 판매자 요청)으로 번지지 않게 한다. */
    void processSafely(Long scheduleId) {
        try {
            process(scheduleId);
        } catch (RuntimeException e) {
            log.error("좌석 준비 처리 중 오류. 다음 재시도에서 다시 처리한다. scheduleId={}", scheduleId, e);
        }
    }
}
