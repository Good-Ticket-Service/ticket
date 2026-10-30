package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.dto.SeatSetupRetryPolicy;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.application.port.SeatSetupReader;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 준비 한 번의 시작과 결과 반영. 둘 다 회차 행을 잠그는 짧은 새 트랜잭션이고, 그 사이의 좌석 호출은 트랜잭션 밖이다.
 * Note: REQUIRES_NEW다. 시작과 반영이 각각 바로 커밋돼야 호출 동안 행 잠금·커넥션을 쥐지 않는다. 바깥 트랜잭션에 합류하면 둘 다 깨진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SeatSetupAttempts {

    static final String NO_SEATS = "공연장에 좌석이 없어 좌석을 준비할 수 없습니다.";
    static final String PRICE_MISSING = "판매 대상 좌석 등급의 가격이 없습니다. 등급별 가격을 설정해 주세요.";
    static final String REPEATED_FAILURE = "좌석 준비가 반복해서 실패했습니다. 운영자에게 문의해 주세요.";

    private final ScheduleStore scheduleStore;
    private final SeatSetupReader seatSetupReader;
    private final SeatSetupRetryPolicy policy;
    private final Clock clock;

    /**
     * 보낼 차례인 회차면 시도 횟수를 올리고 보낼 요청을 만든다. 보내지 않을 때는 빈 값이다.
     * 좌석 서비스가 400으로 거절할 요청(좌석 0개, 판매 대상 좌석의 가격 누락)은 보내지 않고 바로 FAILED로 둔다(docs 「좌석 준비」).
     */
    public Optional<SeatSetupCommand> start(Long scheduleId) {
        Schedule schedule = scheduleStore.findByIdForUpdate(scheduleId).orElse(null);
        if (schedule == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 다른 실행이 시도 중(임대 시간 안)이거나 간격을 기다리는 중이면 아무것도 하지 않는다. 한도 검사보다 먼저 봐야 한다.
        // 먼저 보지 않으면 진행 중인 마지막 시도를 FAILED로 확정하고, 그 시도의 성공 결과를 버리게 된다(리뷰 R1-1)
        if (!schedule.isSeatSetupDue(now)) {
            return Optional.empty();
        }
        // 마지막 시도 중 서버가 멈춰 결과를 반영하지 못한 채 임대 시간이 지난 회차
        if (schedule.getSeatSetupAttempts() >= policy.maxAttempts()) {
            failRepeatedly(schedule, "마지막 시도의 결과를 반영하지 못한 채 임대 시간이 지남", REPEATED_FAILURE);
            scheduleStore.saveSeatSetup(schedule);
            return Optional.empty();
        }
        if (!schedule.startSeatSetupAttempt(now, policy.delayAfter(schedule.getSeatSetupAttempts() + 1))) {
            return Optional.empty();
        }

        List<SeatSetupCommand.Line> seats = seatSetupReader.findSeatLines(schedule.getPerformanceId());
        Optional<SeatSetupCommand> command = Optional.empty();
        if (seats.isEmpty()) {
            schedule.failSeatSetup(NO_SEATS);
        } else if (seats.stream().anyMatch(seat -> seat.sellable() && seat.basePrice() == null)) {
            schedule.failSeatSetup(PRICE_MISSING);
        } else {
            command = Optional.of(new SeatSetupCommand(
                    schedule.getSeatSetupRequestId(), schedule.getId(), schedule.getSeatConfigVersion(), seats));
        }
        scheduleStore.saveSeatSetup(schedule);
        return command;
    }

    /** 결과가 회차의 현재 requestId·configVersion에 대한 것일 때만 반영한다. 그 사이 구성이 바뀌었으면 지난 결과라 버린다. */
    public void finish(SeatSetupCommand command, SeatSetupOutcome outcome) {
        Schedule schedule =
                scheduleStore.findByIdForUpdate(command.scheduleId()).orElse(null);
        if (schedule == null || !schedule.isCurrentSeatSetup(command.requestId(), command.configVersion())) {
            log.info(
                    "지난 좌석 준비 결과를 버린다. scheduleId={}, requestId={}, configVersion={}",
                    command.scheduleId(),
                    command.requestId(),
                    command.configVersion());
            return;
        }
        if (outcome instanceof SeatSetupOutcome.Ready ready) {
            if (ready.requestId().equals(command.requestId()) && ready.configVersion() == command.configVersion()) {
                schedule.completeSeatSetup();
            } else {
                retryLater(schedule, "응답의 requestId·configVersion이 요청과 다름: " + ready, null);
            }
        } else if (outcome instanceof SeatSetupOutcome.Rejected rejected) {
            log.warn("좌석 준비가 거절됐다. scheduleId={}, code={}", schedule.getId(), rejected.code());
            schedule.failSeatSetup(rejected.reason());
        } else if (outcome instanceof SeatSetupOutcome.Stale) {
            log.warn(
                    "좌석 쪽에 더 높은 구성 버전이 있어 결과를 무시한다. scheduleId={}, configVersion={}",
                    schedule.getId(),
                    command.configVersion());
        } else if (outcome instanceof SeatSetupOutcome.Unavailable unavailable) {
            retryLater(schedule, unavailable.detail(), unavailable.failureReason());
        }
        scheduleStore.saveSeatSetup(schedule);
    }

    /**
     * 일시 실패. 다음 시도 시각은 start에서 이미 옮겼으므로 PENDING을 유지한다. 한도에 닿았으면 FAILED.
     * failureReason이 있으면(예: 좌석이 선점·판매 중) FAILED 사유로 그 값을 남겨 판매자가 원인을 알 수 있게 한다.
     */
    private void retryLater(Schedule schedule, String detail, String failureReason) {
        if (schedule.getSeatSetupAttempts() >= policy.maxAttempts()) {
            failRepeatedly(schedule, detail, failureReason == null ? REPEATED_FAILURE : failureReason);
            return;
        }
        log.warn(
                "좌석 준비 일시 실패, 다시 보낸다. scheduleId={}, attempts={}, nextAttemptAt={}, detail={}",
                schedule.getId(),
                schedule.getSeatSetupAttempts(),
                schedule.getSeatSetupNextAttemptAt(),
                detail);
    }

    /** Note: 운영 알림은 우선 ERROR 로그로 남긴다. 공개 후 FAILED 회차의 운영 절차는 정하지 않았다(architecture 14절). */
    private void failRepeatedly(Schedule schedule, String detail, String reason) {
        log.error(
                "좌석 준비 재시도 한도 초과로 FAILED. scheduleId={}, attempts={}, detail={}",
                schedule.getId(),
                schedule.getSeatSetupAttempts(),
                detail);
        schedule.failSeatSetup(reason);
    }
}
