package com.sparta.ticket.performance.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;
import com.sparta.ticket.performance.application.dto.SeatSetupRecoveryAttempt;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import com.sparta.ticket.performance.support.FakeSeatSetupClient;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL에 남은 마지막 시도·복구 임대와 응답 경합을 검증한다. */
@Sql(
        value = {"/sql/truncate.sql", "/sql/seller-schedule-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class SeatSetupRecoveryIntegrationTest extends PerformanceIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScheduleStore schedules;

    @Autowired
    private SeatSetupAttempts normal;

    @Autowired
    private SeatSetupRecoveryAttempts recovery;

    @Autowired
    private SeatSetupRecoveryService service;

    @Autowired
    private TransactionTemplate transactions;

    private long pending() {
        return transactions.execute(status -> schedules
                .save(Schedule.register(
                        410L, NOW.plusDays(60), NOW.plusDays(1), NOW.plusDays(59), "recovery-request", NOW))
                .getId());
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM p_schedules WHERE id=?", id);
    }

    private long exhausted() {
        long id = pending();
        jdbc.update("UPDATE p_schedules SET seat_setup_attempts=8 WHERE id=?", id);
        normal.start(id);
        return id;
    }

    private SeatSetupOutcome ready(SeatSetupRecoveryAttempt attempt) {
        return FakeSeatSetupClient.ready(attempt.command());
    }

    @Test
    void recoversLastInterruptedAttemptWithoutChangingRequestOrVersion() {
        long id = exhausted();
        assertThat(row(id))
                .containsEntry("seat_setup_status", "FAILED")
                .containsEntry("seat_setup_failure_kind", "RESULT_UNKNOWN");
        clock.set(NOW.plusSeconds(30));
        service.processDue(20);
        assertThat(row(id))
                .containsEntry("seat_setup_status", "READY")
                .containsEntry("seat_setup_attempts", 8)
                .containsEntry("seat_setup_recovery_attempts", 1)
                .containsEntry("seat_setup_request_id", "recovery-request")
                .containsEntry("seat_config_version", 1L)
                .containsEntry("version", 0L);
        assertThat(seatSetupClient.activeConnections()).containsExactly(0);
    }

    @Test
    void recoversTimeoutOnLastNormalAttempt() {
        long id = pending();
        jdbc.update("UPDATE p_schedules SET seat_setup_attempts=7 WHERE id=?", id);
        SeatSetupCommand command = normal.start(id).orElseThrow();
        normal.finish(command, new SeatSetupOutcome.Unavailable("read timeout"));
        clock.set(NOW.plusSeconds(30));
        service.process(id);
        assertThat(row(id)).containsEntry("seat_setup_status", "READY");
    }

    @Test
    void doesNotAutomaticallyRecoverInvalidOrUnclassifiedFailures() {
        long id = pending();
        SeatSetupCommand command = normal.start(id).orElseThrow();
        normal.finish(command, new SeatSetupOutcome.Rejected("VALIDATION_ERROR", "요청 오류"));
        clock.set(NOW.plusDays(1));
        service.processDue(20);
        assertThat(row(id))
                .containsEntry("seat_setup_failure_kind", "INVALID_REQUEST")
                .containsEntry("seat_setup_recovery_attempts", 0);
        assertThatThrownBy(() -> recovery.queue(id, command.requestId(), 1, "operator", "확인"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(seatSetupClient.requests()).isEmpty();
    }

    @Test
    void rejectsOldFailureAfterRecoveryLeaseHasBeenTakenOver() {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        SeatSetupRecoveryAttempt old = recovery.start(id).orElseThrow();
        assertThat(recovery.start(id)).isEmpty();
        clock.set(NOW.plusSeconds(46));
        SeatSetupRecoveryAttempt current = recovery.start(id).orElseThrow();
        recovery.finish(old, new SeatSetupOutcome.Unavailable("late failure"));
        assertThat(row(id)).containsEntry("seat_setup_recovery_attempts", 2);
        recovery.finish(current, ready(current));
        recovery.finish(old, new SeatSetupOutcome.Unavailable("older failure"));
        assertThat(row(id)).containsEntry("seat_setup_status", "READY");
    }

    @Test
    void ignoresResultAfterConfigChangedOrPerformanceCanceled() {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        SeatSetupRecoveryAttempt old = recovery.start(id).orElseThrow();
        transactions.executeWithoutResult(status -> {
            Schedule schedule = schedules.findByIdForUpdate(id).orElseThrow();
            schedule.requireSeatSetupForNewConfig("new-request", LocalDateTime.now(clock));
            schedules.saveSeatSetup(schedule);
        });
        recovery.finish(old, ready(old));
        assertThat(row(id))
                .containsEntry("seat_setup_status", "PENDING")
                .containsEntry("seat_setup_request_id", "new-request");
        jdbc.update("UPDATE p_performances SET operation_status='CANCELED' WHERE id=410");
        service.process(id);
        assertThat(seatSetupClient.requests()).isEmpty();
    }

    @Test
    void operatorCanRequeueAfterRecoveryBudgetIsExhaustedWithoutResettingAttempts() {
        long id = exhausted();
        for (int attempt = 0; attempt < 3; attempt++) {
            clock.set(NOW.plusMinutes(attempt + 1));
            // 두 번째 실패 후 간격은 60초다. 충분히 지난 시각으로 이동한다.
            if (attempt == 2) {
                clock.set(NOW.plusMinutes(4));
            }
            seatSetupClient.answer(new SeatSetupOutcome.Unavailable("offline"));
            service.process(id);
        }
        assertThat(row(id))
                .containsEntry("seat_setup_recovery_attempts", 3)
                .containsEntry("seat_setup_recovery_next_at", null);
        assertThatThrownBy(() -> recovery.queue(id, "old-request", 1, "operator", "확인"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(recovery.queue(id, "recovery-request", 1, "operator", "통신 복구 후 재확인"))
                .isTrue();
        assertThat(recovery.queue(id, "recovery-request", 1, "operator", "중복 명령"))
                .isFalse();
        service.process(id);
        assertThat(row(id))
                .containsEntry("seat_setup_status", "READY")
                .containsEntry("seat_setup_recovery_attempts", 4);
    }

    @Test
    void exhaustedRecoveryLeaseBecomesManualActionWithoutUnboundedCalls() {
        long id = exhausted();
        for (int attempt = 0; attempt < 3; attempt++) {
            clock.set(NOW.plusMinutes(attempt + 1));
            recovery.start(id).orElseThrow();
        }
        clock.set(NOW.plusMinutes(4));
        service.processDue(20);
        assertThat(row(id))
                .containsEntry("seat_setup_recovery_attempts", 3)
                .containsEntry("seat_setup_recovery_next_at", null)
                .containsEntry("seat_setup_failure_kind", "RESULT_UNKNOWN");
        assertThat(seatSetupClient.requests()).isEmpty();
    }

    @Test
    void acceptsLateSuccessAfterFinalRecoveryLeaseWasCleared() {
        long id = exhausted();
        SeatSetupRecoveryAttempt last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            clock.set(NOW.plusMinutes(attempt + 1));
            last = recovery.start(id).orElseThrow();
        }
        clock.set(NOW.plusMinutes(4));
        assertThat(recovery.start(id)).isEmpty();
        recovery.finish(last, new SeatSetupOutcome.Unavailable("late failure"));
        assertThat(row(id)).containsEntry("seat_setup_recovery_next_at", null);
        recovery.finish(last, ready(last));
        assertThat(row(id))
                .containsEntry("seat_setup_status", "READY")
                .containsEntry("seat_setup_recovery_attempts", 3);
    }

    @Test
    void rejectsLateSuccessAfterOperatorStartsANewRecoveryAttempt() {
        long id = exhausted();
        SeatSetupRecoveryAttempt last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            clock.set(NOW.plusMinutes(attempt + 1));
            last = recovery.start(id).orElseThrow();
        }
        clock.set(NOW.plusMinutes(4));
        assertThat(recovery.start(id)).isEmpty();
        recovery.queue(id, "recovery-request", 1, "operator", "재확인");
        SeatSetupRecoveryAttempt current = recovery.start(id).orElseThrow();
        recovery.finish(last, ready(last));
        assertThat(row(id)).containsEntry("seat_setup_status", "FAILED");
        recovery.finish(current, ready(current));
        assertThat(row(id)).containsEntry("seat_setup_status", "READY");
    }

    @Test
    void twoWorkersCannotClaimTheSameRecovery() throws Exception {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<SeatSetupRecoveryAttempt>> first = pool.submit(() -> {
                go.await();
                return recovery.start(id);
            });
            Future<Optional<SeatSetupRecoveryAttempt>> second = pool.submit(() -> {
                go.await();
                return recovery.start(id);
            });
            go.countDown();
            int claims = (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claims).isEqualTo(1);
            assertThat(row(id)).containsEntry("seat_setup_recovery_attempts", 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void canceledPerformanceCannotAcceptAnInFlightSuccess() {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        SeatSetupRecoveryAttempt claimed = recovery.start(id).orElseThrow();
        jdbc.update("UPDATE p_performances SET operation_status='CANCELED' WHERE id=410");
        recovery.finish(claimed, ready(claimed));
        assertThat(row(id))
                .containsEntry("seat_setup_status", "FAILED")
                .containsEntry("seat_setup_recovery_next_at", null);
    }

    @Test
    void mismatchedSuccessCannotMarkRecoveryReady() {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        SeatSetupRecoveryAttempt claimed = recovery.start(id).orElseThrow();
        recovery.finish(claimed, new SeatSetupOutcome.Ready("other-request", 1, 3, 2));
        assertThat(row(id)).containsEntry("seat_setup_status", "FAILED");
    }

    @Test
    void legacyUnclassifiedFailureIsNotAutomaticallyOrManuallyRequeued() {
        long id = pending();
        jdbc.update("UPDATE p_schedules SET seat_setup_status='FAILED',seat_setup_next_attempt_at=NULL WHERE id=?", id);
        clock.set(NOW.plusDays(1));
        service.processDue(20);
        assertThatThrownBy(() -> recovery.queue(id, "recovery-request", 1, "operator", "확인"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(id))
                .containsEntry("seat_setup_failure_kind", null)
                .containsEntry("seat_setup_recovery_attempts", 0);
        assertThat(seatSetupClient.requests()).isEmpty();
    }

    @Test
    void deletedPerformanceCannotAcceptAnInFlightSuccess() {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        SeatSetupRecoveryAttempt claimed = recovery.start(id).orElseThrow();
        jdbc.update("UPDATE p_performances SET deleted_at=CURRENT_TIMESTAMP WHERE id=410");
        recovery.finish(claimed, ready(claimed));
        assertThat(row(id))
                .containsEntry("seat_setup_status", "FAILED")
                .containsEntry("seat_setup_recovery_next_at", null);
        assertThat(seatSetupClient.requests()).isEmpty();
    }

    @Test
    void deletedScheduleCannotAcceptAnInFlightSuccess() {
        long id = exhausted();
        clock.set(NOW.plusSeconds(30));
        SeatSetupRecoveryAttempt claimed = recovery.start(id).orElseThrow();
        jdbc.update("UPDATE p_schedules SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", id);
        recovery.finish(claimed, ready(claimed));
        clock.set(NOW.plusDays(1));
        service.processDue(20);
        assertThat(row(id))
                .containsEntry("seat_setup_status", "FAILED")
                .containsEntry("seat_setup_recovery_attempts", 1);
        assertThat(seatSetupClient.requests()).isEmpty();
    }
}
