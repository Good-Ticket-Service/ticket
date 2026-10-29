package com.sparta.ticket.reservation.booking.application.cancel;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 취소 중으로 오래 머문 예약의 환불을 같은 orderId로 다시 요청해 마무리한다. 결제를 먼저 조회하지 않는다.
 * payment는 같은 orderId의 재요청에 처음 사유를 지키고 지금 상태를 돌려주므로, 다시 요청하는 것이 곧 확인이다(#127).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelReconcileService {

    private final ReservationRepository reservationRepository;
    private final CancelTransactions transactions;
    private final ReservationCancelService cancelService;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /**
     * threshold 이상 취소 중인 예약을 한 묶음 다시 요청하고, 취소로 끝낸 건수를 돌려준다.
     * 시간 예산이 다 되면 다음 환불 요청을 시작하지 않는다. 이미 시작한 요청 하나만큼은 넘칠 수 있다.
     */
    public int reconcile(Duration threshold, int batchSize, Duration timeBudget) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        LocalDateTime staleBefore = changeStamps.now().minus(threshold);
        int canceled = 0;
        for (Long reservationId : reservationRepository.findStaleCancelIds(staleBefore, batchSize)) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (reconcileOne(reservationId, staleBefore)) {
                    canceled++;
                }
            } catch (RuntimeException e) {
                log.warn("취소 확인에 실패했다. 다음 실행에서 다시 고른다. reservationId={}", reservationId, e);
            }
        }
        return canceled;
    }

    private boolean reconcileOne(Long reservationId, LocalDateTime staleBefore) {
        Optional<String> orderId = transactions.claimPendingCancel(reservationId, staleBefore);
        if (orderId.isEmpty()) {
            return false;
        }
        metrics.resentRefund();
        return cancelService.refundAndFinish(reservationId, orderId.get());
    }
}
