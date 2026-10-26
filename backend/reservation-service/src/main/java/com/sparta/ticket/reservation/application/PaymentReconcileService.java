package com.sparta.ticket.reservation.application;

import com.sparta.ticket.reservation.application.ReservationTransactions.PendingPayment;
import com.sparta.ticket.reservation.application.port.ConfirmOutcome;
import com.sparta.ticket.reservation.application.port.PaymentLookup;
import com.sparta.ticket.reservation.application.port.PaymentPort;
import com.sparta.ticket.reservation.domain.ReservationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 중으로 오래 머문 예약의 결과를 payment에 물어 마무리한다(F10, F4). 원격 호출은 트랜잭션 밖에서 하고,
 * 결과 반영은 PaymentResultApplier에 맡긴다. 같은 승인을 다시 보내는 곳은 여기뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconcileService {

    private final ReservationRepository reservationRepository;
    private final ReservationTransactions transactions;
    private final PaymentResultApplier resultApplier;
    private final PaymentPort paymentPort;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /**
     * threshold 이상 결제 중인 예약을 한 묶음 확인하고, 상태가 바뀐 건수를 돌려준다.
     * threshold는 처리 중인 승인 요청을 또 보내지 않도록 승인 호출의 최대 대기보다 길어야 한다.
     * 시간 예산이 다 되면 다음 원격 호출을 시작하지 않는다. 이미 시작한 호출 하나만큼은 넘칠 수 있다.
     */
    public int reconcile(Duration threshold, Duration stuckAfter, int batchSize, Duration timeBudget) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        LocalDateTime now = changeStamps.now();
        int changed = 0;
        for (Long reservationId : reservationRepository.findStalePaymentIds(now.minus(threshold), batchSize)) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (reconcileOne(reservationId, deadline)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.warn("결제 결과 확인에 실패했다. 다음 실행에서 다시 고른다. reservationId={}", reservationId, e);
            }
        }
        updateBacklog(now.minus(stuckAfter));
        return changed;
    }

    private boolean reconcileOne(Long reservationId, long deadline) {
        PendingPayment pending = transactions.readPendingPayment(reservationId).orElse(null);
        if (pending == null) {
            return false;
        }
        transactions.markPaymentChecked(reservationId);
        PaymentLookup lookup = paymentPort.find(pending.orderId());
        return switch (lookup.status()) {
            case SUCCEEDED, FAILED -> resultApplier.apply(reservationId, pending.orderId(), lookup.toOutcome());
            case NOT_RECEIVED -> System.nanoTime() <= deadline && resend(pending);
            case IN_PROGRESS -> false;
            case UNKNOWN_ORDER -> {
                log.error(
                        "payment가 모르는 orderId다. 준비가 성공한 뒤에만 기록하므로 생기면 안 된다. reservationId={}, orderId={}",
                        reservationId,
                        pending.orderId());
                yield false;
            }
        };
    }

    /** payment가 승인 요청을 받지 못했다. 같은 orderId, 인증값, 금액으로 다시 보낸다. payment는 orderId 기준으로 멱등이다. */
    private boolean resend(PendingPayment pending) {
        metrics.resentConfirm();
        ConfirmOutcome outcome;
        try {
            outcome = paymentPort.confirm(pending.orderId(), pending.pgAuthToken(), pending.amount());
        } catch (RuntimeException e) {
            log.warn("승인 재요청의 결과를 모른다. reservationId={}", pending.reservationId(), e);
            return false;
        }
        return resultApplier.apply(pending.reservationId(), pending.orderId(), outcome);
    }

    private void updateBacklog(LocalDateTime stuckBefore) {
        long stuck = reservationRepository.countStalePayments(stuckBefore);
        metrics.paymentBacklog(reservationRepository.countPaymentsInProgress(), stuck);
        if (stuck > 0) {
            log.warn("결제 중으로 상한 시간을 넘긴 예약이 {}건이다. 관리자 확인이 필요하다. 기준 시각={}", stuck, stuckBefore);
        }
    }
}
