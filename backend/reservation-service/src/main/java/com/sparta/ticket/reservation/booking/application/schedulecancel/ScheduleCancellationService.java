package com.sparta.ticket.reservation.booking.application.schedulecancel;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.cancel.ReservationCancelService;
import com.sparta.ticket.reservation.booking.application.port.RefundReason;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import com.sparta.ticket.reservation.booking.domain.ScheduleCancellationRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 취소 기록을 읽어 회차의 예약을 묶음으로 정리한다. 건마다 트랜잭션이 따로라 한 건의 실패가 나머지를 되돌리지 않는다.
 * 결제 중과 취소 중은 건드리지 않는다. 결제 중은 결과가 나면 선점이나 확정이 되어 다음 차례에 고르고, 취소 중은 취소 확인이 끝낸다.
 * 인스턴스가 여러 개여도 조건부 UPDATE가 한 번만 성공하므로 분산 락을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCancellationService {

    private final ScheduleCancellationRepository cancellationRepository;
    private final ReservationRepository reservationRepository;
    private final ScheduleCancellationTransactions transactions;
    private final ReservationCancelService cancelService;
    private final BookingMetrics metrics;

    /**
     * 끝나지 않은 회차를 받은 순서로 정리하고 처리한 예약 수를 돌려준다. 시간 예산이 다 되면 다음 예약을 시작하지 않는다.
     * 이미 시작한 환불 요청 하나만큼은 넘칠 수 있다.
     */
    public int cleanUp(int batchSize, Duration completeAfter, Duration timeBudget) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        int processed = 0;
        for (Long scheduleId : cancellationRepository.findUnfinishedScheduleIds(batchSize)) {
            processed += cleanUpSchedule(scheduleId, batchSize, deadline);
            completeIfDone(scheduleId, completeAfter);
        }
        reportConfirmedAfterCompleted();
        return processed;
    }

    /** 완료한 회차는 다시 고르지 않으므로 여기 남은 확정 예약은 정리가 끝내지 못한다. 사람이 환불해야 하니 알리기만 한다. */
    private void reportConfirmedAfterCompleted() {
        long count = reservationRepository.countConfirmedAfterCompleted();
        metrics.confirmedAfterCompleted(count);
        if (count > 0) {
            log.error("정리를 완료한 회차에 확정 예약이 남았다. 환불이 빠졌으므로 관리자가 확인한다. count={}", count);
        }
    }

    private int cleanUpSchedule(Long scheduleId, int batchSize, long deadline) {
        int processed = 0;
        for (Long reservationId : reservationRepository.findCancelableIds(scheduleId, batchSize)) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                transactions
                        .closeHoldOrStartRefund(reservationId)
                        .ifPresent(orderId ->
                                cancelService.refundAndFinish(reservationId, orderId, RefundReason.SCHEDULE_CANCELED));
                processed++;
            } catch (RuntimeException e) {
                log.warn("회차 취소 정리에 실패했다. 다음 실행에서 다시 고른다. reservationId={}", reservationId, e);
            }
        }
        return processed;
    }

    /**
     * Note: 취소 기록을 받은 직후에는 기록을 보기 전에 시작한 결제 시작 트랜잭션이 커밋 전일 수 있다. 그때 세면 0건이라 완료해 버리고,
     * 그 결제가 성공해도 정리가 다시 고르지 않아 환불되지 않는다. 그런 트랜잭션이 끝날 만큼 completeAfter를 기다린다.
     */
    private void completeIfDone(Long scheduleId, Duration completeAfter) {
        if (reservationRepository.countActive(scheduleId) == 0 && transactions.complete(scheduleId, completeAfter)) {
            log.info("회차 취소 정리를 끝냈다. scheduleId={}", scheduleId);
        }
    }
}
