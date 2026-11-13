package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 승인을 메시지로 요청할지 동기로 부를지 요청마다 정한다. 설정이 메시지 승인이어도 승인 요청이 브로커로 나가지 못하고 있으면
 * 새 승인은 동기로 부른다. 메시지로 남기면 브로커가 돌아올 때까지 결제 중에 묶여, 사용자가 좌석을 놓지도 결제를 다시 준비하지도
 * 못한 채 시한과 여유 시간을 기다린다. 동기 승인은 브로커를 거치지 않는다.
 *
 * <p>근거는 Outbox 맨 앞 메시지가 기다린 시간 하나다. 발행기는 남긴 순서대로 보내고 하나라도 못 보내면 그 차례를 멈추므로,
 * 지금 남기는 요청은 맨 앞 메시지가 나간 뒤에야 나간다. 보내기 결과는 쓰지 않는다. 브로커가 멈추면 발행 한 번이 실패 하나를
 * 남기는 데 보내기 대기 시간이 다 걸리고, 브로커 정보를 기다리는 동안에는 결과가 아예 없다. 맨 앞 메시지의 시각은 그동안에도
 * DB에 있다.
 *
 * <p>동기로 돌리는 동안은 Outbox에 새 행이 생기지 않으므로 맨 앞은 돌리기 전에 접수한 요청이다. 발행기가 그 행을 계속 다시
 * 보내고, 브로커가 받아 오래 기다린 행이 모두 빠지면 다시 메시지로 요청한다. 다시 보내는 행은 이미 결제 중인 사용자의 요청이라
 * 실패해도 그 사용자의 결과는 돌리기 전과 같다. 새 사용자의 승인을 시험 삼아 메시지로 보내지 않는다.
 *
 * <p>Outbox는 인스턴스가 함께 쓰므로 인스턴스마다 같은 맨 앞 행을 보고 집계 주기 안에 같은 판단에 이른다. 판단이 엇갈린
 * 동안에도 예약 하나는 결제 중으로 바꾸는 조건부 UPDATE 하나만 통과한다. 집계가 멈추면 마지막 판단을 그대로 쓴다.
 * 동기로 멈춰도 동기 승인은 브로커 없이 끝나므로 사용자를 결제 중에 묶지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmRouter {

    private final PaymentConfirmSettings settings;
    private final BookingMetrics metrics;

    /** 맨 앞 메시지가 기준 시간 이상 기다렸는지. 결제 중 집계 스레드만 바꾸고 요청 스레드는 읽기만 한다. */
    private volatile boolean stalled;

    /**
     * 이 승인, 또는 이 결제 준비 뒤에 올 승인이 가는 길. 메모리의 값만 읽어 DB, 브로커, 잠금을 기다리지 않는다.
     * 설정은 요청마다 읽어, 설정을 바꾸면 다음 집계를 기다리지 않고 다음 요청부터 따른다.
     */
    public ConfirmRoute route() {
        if (!settings.viaMessage()) {
            return ConfirmRoute.SYNC;
        }
        if (settings.fallbackEnabled() && stalled) {
            return ConfirmRoute.SYNC_FALLBACK;
        }
        return ConfirmRoute.MESSAGE;
    }

    /**
     * 결제 중 집계가 읽은 맨 앞 메시지의 시각으로 판단을 다시 정한다. Outbox가 비었으면 브로커에 넘길 것이 없으니 멈춘 것이 아니다.
     * 시계가 뒤로 가 기다린 시간이 음수여도 동기로 돌릴 근거가 없으므로 멈추지 않은 것으로 본다.
     */
    public void update(Optional<LocalDateTime> oldestWaitingSince, LocalDateTime now) {
        Optional<Duration> waited =
                oldestWaitingSince.map(since -> Duration.between(since, now)).filter(elapsed -> !elapsed.isNegative());
        long waitedSeconds = waited.map(Duration::toSeconds).orElse(0L);
        boolean nowStalled = waited.filter(elapsed -> elapsed.compareTo(settings.unsentAfter()) >= 0)
                .isPresent();
        if (nowStalled != stalled) {
            if (nowStalled) {
                log.warn("Outbox 맨 앞 메시지가 {}초째 나가지 못했다. 메시지 승인이면 새 승인을 동기로 부른다", waitedSeconds);
            } else {
                log.warn("Outbox에서 오래 기다린 메시지가 모두 나갔다. 메시지 승인이면 새 승인을 다시 메시지로 요청한다");
            }
        }
        stalled = nowStalled;
        metrics.confirmFallbackState(waitedSeconds, route() == ConfirmRoute.SYNC_FALLBACK);
    }
}
