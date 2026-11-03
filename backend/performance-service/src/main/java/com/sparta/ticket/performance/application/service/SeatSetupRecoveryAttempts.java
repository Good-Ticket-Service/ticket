package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.dto.SeatSetupRecoveryAttempt;
import com.sparta.ticket.performance.application.dto.SeatSetupRecoveryPolicy;
import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.application.port.SeatSetupReader;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import com.sparta.ticket.performance.domain.schedule.SeatSetupFailureKind;
import com.sparta.ticket.performance.domain.schedule.SeatSetupStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 결과 불명 작업 확보와 반영. 부모 공연 → 회차 잠금 순서이며 외부 호출은 이 트랜잭션 밖에서 한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SeatSetupRecoveryAttempts {
    private final ScheduleStore schedules;
    private final PerformanceStore performances;
    private final SeatSetupReader reader;
    private final SeatSetupRecoveryPolicy policy;
    private final Clock clock;

    public Optional<SeatSetupRecoveryAttempt> start(Long scheduleId) {
        Schedule schedule = lockActive(scheduleId);
        if (schedule == null) {
            return Optional.empty();
        }
        if (!schedule.startSeatSetupRecovery(now(), policy.lease())) {
            schedules.saveSeatSetup(schedule);
            return Optional.empty();
        }
        List<SeatSetupCommand.Line> seats = reader.findSeatLines(schedule.getPerformanceId());
        if (seats.isEmpty() || seats.stream().anyMatch(seat -> seat.sellable() && seat.basePrice() == null)) {
            schedule.failSeatSetup(seats.isEmpty() ? SeatSetupAttempts.NO_SEATS : SeatSetupAttempts.PRICE_MISSING);
            schedules.saveSeatSetup(schedule);
            return Optional.empty();
        }
        SeatSetupCommand command = new SeatSetupCommand(
                schedule.getSeatSetupRequestId(), schedule.getId(), schedule.getSeatConfigVersion(), seats);
        int attempt = schedule.getSeatSetupRecoveryAttempts();
        schedules.saveSeatSetup(schedule);
        log.info("좌석 준비 복구 시작. scheduleId={}, requestId={}, attempt={}", scheduleId, command.requestId(), attempt);
        return Optional.of(new SeatSetupRecoveryAttempt(command, attempt));
    }

    public void finish(SeatSetupRecoveryAttempt attempt, SeatSetupOutcome outcome) {
        SeatSetupCommand command = attempt.command();
        Schedule schedule = lockActive(command.scheduleId());
        if (schedule == null
                || !schedule.isCurrentSeatSetupRecovery(
                        command.requestId(), command.configVersion(), attempt.attempt())) {
            return;
        }
        if (outcome instanceof SeatSetupOutcome.Ready ready) {
            // Note: Feign 외의 포트 구현체에서도 현재 요청과 좌석 수가 일치해야 복구를 확정한다.
            long sellable = command.seats().stream()
                    .filter(SeatSetupCommand.Line::sellable)
                    .count();
            if (ready.requestId().equals(command.requestId())
                    && ready.configVersion() == command.configVersion()
                    && ready.seatCount() == command.seats().size()
                    && ready.sellableCount() == sellable) {
                schedule.completeSeatSetup();
            } else {
                schedule.retrySeatSetupRecovery(now(), policy.delayAfter(attempt.attempt() + 1));
            }
        } else if (schedule.getSeatSetupRecoveryNextAt() == null) {
            // Note: 마지막 임대 정리 후에는 늦은 성공만 수용하고 실패로 복구 일정을 되살리지 않는다.
            return;
        } else if (outcome instanceof SeatSetupOutcome.Rejected rejected) {
            schedule.failSeatSetup(rejected.reason(), SeatSetupFailureKind.INVALID_REQUEST);
        } else if (outcome instanceof SeatSetupOutcome.Stale) {
            schedule.failSeatSetup("좌석에 더 최신 구성이 있어 운영 확인이 필요합니다.", SeatSetupFailureKind.STALE_VERSION);
        } else if (outcome instanceof SeatSetupOutcome.Unavailable unavailable) {
            if (unavailable.kind() == SeatSetupFailureKind.SEAT_IN_USE) {
                schedule.failSeatSetup(unavailable.failureReason(), SeatSetupFailureKind.SEAT_IN_USE);
            } else {
                schedule.retrySeatSetupRecovery(now(), policy.delayAfter(attempt.attempt() + 1));
            }
        }
        schedules.saveSeatSetup(schedule);
        log.info(
                "좌석 준비 복구 결과. scheduleId={}, requestId={}, attempt={}, status={}, actionRequired={}",
                schedule.getId(),
                command.requestId(),
                attempt.attempt(),
                schedule.getSeatSetupStatus(),
                schedule.getSeatSetupFailureKind() == SeatSetupFailureKind.RESULT_UNKNOWN
                        && schedule.getSeatSetupRecoveryNextAt() == null);
    }

    public boolean queue(Long scheduleId, String requestId, long configVersion, String operator, String reason) {
        if (operator == null
                || operator.isBlank()
                || operator.length() > 100
                || reason == null
                || reason.isBlank()
                || reason.length() > 500) {
            throw new IllegalArgumentException("실행 주체와 작업 사유를 올바르게 입력해야 합니다.");
        }
        Schedule schedule = lockActive(scheduleId);
        if (schedule == null) {
            throw new IllegalStateException("유효한 공연·회차만 재처리할 수 있습니다.");
        }
        if (!schedule.getSeatSetupRequestId().equals(requestId) || schedule.getSeatConfigVersion() != configVersion) {
            throw new IllegalStateException("현재 요청 ID·구성 버전과 일치하지 않습니다.");
        }
        if (schedule.getSeatSetupStatus() == SeatSetupStatus.READY) {
            return false;
        }
        boolean queued = schedule.queueSeatSetupRecovery(requestId, configVersion, now(), policy.maxAttempts());
        schedules.saveSeatSetup(schedule);
        log.info(
                "운영자 좌석 복구 접수. scheduleId={}, requestId={}, operator={}, reason={}, queued={}",
                scheduleId,
                requestId,
                operator.replaceAll("[\\r\\n]", " "),
                reason.replaceAll("[\\r\\n]", " "),
                queued);
        return queued;
    }

    private Schedule lockActive(Long id) {
        Long performanceId = schedules.findPerformanceId(id).orElse(null);
        if (performanceId == null) {
            return null;
        }
        Performance performance = performances.findByIdForUpdate(performanceId).orElse(null);
        Schedule schedule = schedules.findByIdForUpdate(id).orElse(null);
        if (schedule == null) {
            return null;
        }
        if (performance == null
                || performance.getOperationStatus() != OperationStatus.ACTIVE
                || schedule.getOperationStatus() != OperationStatus.ACTIVE) {
            schedule.stopSeatSetupRecovery();
            schedules.saveSeatSetup(schedule);
            return null;
        }
        return schedule;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
