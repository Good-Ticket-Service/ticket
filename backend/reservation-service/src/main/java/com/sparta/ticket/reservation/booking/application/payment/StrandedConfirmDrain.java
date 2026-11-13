package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.BookingMetrics.DrainResult;
import com.sparta.ticket.reservation.booking.application.ChangeStamps;
import com.sparta.ticket.reservation.booking.application.port.DrainSettings;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmRequests.PaymentConfirmRequest;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import com.sparta.ticket.reservation.booking.application.port.StrandedConfirms;
import com.sparta.ticket.reservation.booking.application.port.StrandedConfirms.ClaimedConfirm;
import com.sparta.ticket.reservation.booking.application.port.SubstituteAnswer;
import com.sparta.ticket.reservation.booking.application.port.SubstituteConfirmPort;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 승인 요청이 브로커로 나가지 못하는 동안, 그 전에 Outbox에 남은 요청을 payment에 HTTP로 대신 보내(#403) 결제 중에 묶인 사용자를
 * 시한 전에 끝낸다. 새 승인은 라우터가 이미 동기로 부르므로 이 클래스는 그 전에 접수한 요청만 맡는다.
 *
 * <p>Outbox 행은 지우지 않는다. 지우면 Outbox가 비어 라우터가 요청이 나간다고 보고, 브로커가 멈춘 채인데 새 승인을 다시 메시지로
 * 남긴다. 그 요청은 기준 시간 뒤 또 묶이고 이 일이 몇 초마다 되풀이된다. 남긴 행은 발행기가 브로커에 넘겨 지우고, 그때가 곧
 * 브로커가 돌아온 때다. 이미 끝난 주문의 요청이 그때 payment에 닿아도 payment는 이미 처리한 것으로 받는다.
 *
 * <p>결과를 모르는 승인은 다시 보내지 않고 결제 조회로 묻는다(#422 계약). 그래서 고를 때 남기는 보낸 시각이 이미 있던 요청은
 * 승인 대신 조회를 부른다. 승인을 다시 보내는 것은 payment가 받지 않았다고 확인됐을 때(자리 없음, 조회에 접수 기록 없음)뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrandedConfirmDrain {

    /**
     * payment가 바로 다시 보내라고 해도 이만큼은 쉰다. Retry-After는 초 단위라 1초가 가장 짧은 값이다. 쉬기는 다음 고르기부터 막고
     * 이미 보낸 요청은 멈추지 않으므로, 결제 호출 한 번보다 짧게 쉬면 다른 작업자의 답이 오기 전에 쉬기가 끝나 쉬는 효과가 없다.
     */
    private static final Duration MIN_PAUSE = Duration.ofSeconds(1);

    private final ConfirmRouter confirmRouter;
    private final PaymentConfirmSettings settings;
    private final StrandedConfirms stranded;
    private final SubstituteConfirmPort payment;
    private final PaymentResultApplier resultApplier;
    private final ChangeStamps changeStamps;
    private final BookingMetrics metrics;

    /** payment가 돌려보낸 뒤 보내지 않는 구간. 인스턴스마다 따로 둔다. */
    private final AtomicReference<Pause> pause = new AtomicReference<>(Pause.NONE);

    /**
     * 요청 하나를 골라 보내고 답을 반영한다. 한 일이 있으면 true, 지금 할 일이 없으면 false라 부르는 쪽은 잠시 쉰다.
     * payment가 답할 때까지 블록되지만 DB 연결은 고르기와 반영 동안만 쓴다. 사용자 요청 스레드에서 부르지 않는다.
     */
    public boolean sendNext() {
        DrainSettings drain = settings.drain();
        // 메시지로 돌아왔으면 남은 행은 발행기가 보낸다. 라우터는 메모리 값만 읽으므로 작업자가 자주 물어도 비용이 없다
        if (!drain.enabled() || confirmRouter.route() != ConfirmRoute.SYNC_FALLBACK) {
            return false;
        }
        LocalDateTime now = changeStamps.now();
        Pause current = pause.get();
        if (current.covers(now)) {
            return false;
        }
        // 끝난 쉬기는 지운다. 남겨 두면 시계가 뒤로 가 그 구간에 다시 들어올 때 또 쉰다
        pause.compareAndSet(current, Pause.NONE);
        ClaimedConfirm claimed = stranded.claimNext(now, drain.resendAfter()).orElse(null);
        if (claimed == null) {
            return false;
        }
        PaymentConfirmRequest request = claimed.request();
        // 고른 뒤 시한이 지났을 수 있다(GC 멈춤 등). 시한이 지난 요청은 보내지 않는다. 보낸 시각이 남아 시한 전에는 다시 고르지 않고,
        // 시한 뒤에는 결과 확인이 끝낸다
        if (!request.confirmBefore().isAfter(changeStamps.now())) {
            return true;
        }
        settle(request, ask(claimed), drain);
        return true;
    }

    private SubstituteAnswer ask(ClaimedConfirm claimed) {
        try {
            return claimed.sent() ? payment.lookup(claimed.request()) : payment.confirm(claimed.request());
        } catch (RuntimeException e) {
            return new SubstituteAnswer.Unknown(e);
        }
    }

    private void settle(PaymentConfirmRequest request, SubstituteAnswer answer, DrainSettings drain) {
        if (answer instanceof SubstituteAnswer.Settled settled) {
            apply(request, settled);
            metrics.drainResult(DrainResult.SETTLED);
        } else if (answer instanceof SubstituteAnswer.Accepted) {
            // 보낸 시각이 남아 resendAfter 뒤에 조회로 결과를 묻는다
            metrics.drainResult(DrainResult.ACCEPTED);
        } else if (answer instanceof SubstituteAnswer.NotReceived) {
            // payment에 승인이 닿지 않았으므로 다시 보내도 결과 모르는 승인의 재시도가 아니다. 다음 고르기에서 승인을 보낸다
            stranded.requeue(request);
            metrics.drainResult(DrainResult.NOT_RECEIVED);
        } else if (answer instanceof SubstituteAnswer.Busy busy) {
            // payment는 결제를 받지 않은 채 두었다. 쉬는 시간이 끝나면 바로 다시 보낼 수 있게 보낸 시각을 지운다
            stranded.requeue(request);
            pauseFor(busy.retryAfter(), drain.maxBackoff());
            metrics.drainResult(DrainResult.BUSY);
        } else if (answer instanceof SubstituteAnswer.Rejected rejected) {
            stranded.parkUntilDeadline(request);
            log.error(
                    "payment가 대체 승인을 받지 않았다. 다시 보내지 않고 시한 뒤 결과 확인이 끝낸다. reservationId={}, orderId={}, reason={}",
                    request.reservationId(),
                    request.orderId(),
                    rejected.reason());
            metrics.drainResult(DrainResult.REJECTED);
        } else if (answer instanceof SubstituteAnswer.Unknown unknown) {
            // payment가 승인을 받았는지 모른다. 보낸 시각이 남아 resendAfter 뒤 승인 대신 조회로 묻는다
            log.warn(
                    "대체 승인의 답을 받지 못했다. 잠시 뒤 결제 조회로 묻는다. reservationId={}, orderId={}",
                    request.reservationId(),
                    request.orderId(),
                    unknown.cause());
            metrics.drainResult(DrainResult.UNKNOWN);
        }
    }

    /**
     * 결과 메시지를 기다리지 않고 바로 반영한다. 브로커가 멈춰 있어 결과 메시지가 오지 않을 수 있다. 나중에 결과 메시지가 와도
     * 반영기가 같은 결과를 걸러 두 번 바뀌지 않는다. 반영이 실패하면 결제 중으로 남고 resendAfter 뒤 조회로 같은 결과를 받아 반영한다.
     */
    private void apply(PaymentConfirmRequest request, SubstituteAnswer.Settled settled) {
        try {
            resultApplier.apply(request.reservationId(), request.orderId(), settled.outcome());
        } catch (RuntimeException e) {
            log.error(
                    "대체 승인 결과를 반영하지 못했다. 잠시 뒤 결제 조회로 다시 받아 반영한다. reservationId={}, orderId={}",
                    request.reservationId(),
                    request.orderId(),
                    e);
        }
    }

    /** 작업자 여럿이 함께 돌려보내지면 가장 늦게 끝나는 쉬기를 남긴다. */
    private void pauseFor(Duration retryAfter, Duration maxBackoff) {
        Duration length = retryAfter.compareTo(MIN_PAUSE) < 0
                ? MIN_PAUSE
                : retryAfter.compareTo(maxBackoff) > 0 ? maxBackoff : retryAfter;
        LocalDateTime now = changeStamps.now();
        Pause next = new Pause(now, now.plus(length));
        pause.accumulateAndGet(
                next, (current, candidate) -> candidate.until().isAfter(current.until()) ? candidate : current);
    }

    /**
     * since 이상 until 미만이면 보내지 않는다. 시계가 since보다 뒤로 가면 쉬기를 끝낸다. 끝만 보면 시계가 한 시간 뒤로 갈 때
     * 한 시간을 더 쉰다.
     */
    private record Pause(LocalDateTime since, LocalDateTime until) {

        static final Pause NONE = new Pause(LocalDateTime.MIN, LocalDateTime.MIN);

        boolean covers(LocalDateTime now) {
            return !now.isBefore(since) && now.isBefore(until);
        }
    }
}
