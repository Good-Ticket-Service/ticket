package com.sparta.ticket.reservation.booking.application.query;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.port.PaymentPollingSettings;
import com.sparta.ticket.reservation.booking.domain.ReservationStatus;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 중 예약을 조회한 클라이언트에게 다음 조회까지 기다릴 시간을 정한다. 결제 중이 아니면 비어 있어 클라이언트가 조회를 멈춘다.
 *
 * 결제 중 예약 수는 결과 확인 스케줄러가 주기마다 갱신하는 게이지를 쓴다. 조회마다 세면 폴링이 많을수록 그 비용이 커진다.
 * 게이지는 그 주기만큼 늦지만, 간격은 정확한 값이 아니라 부하 신호라 충분하다.
 */
@Component
@RequiredArgsConstructor
public class PaymentPollingInterval {

    private final PaymentPollingSettings settings;
    private final BookingMetrics metrics;

    public Optional<Duration> forStatus(ReservationStatus status) {
        if (status != ReservationStatus.PAYMENT_IN_PROGRESS) {
            return Optional.empty();
        }
        long doublings = metrics.paymentsInProgressCount() / settings.busyAbove();
        Duration interval = settings.base();
        for (long i = 0; i < doublings && interval.compareTo(settings.max()) < 0; i++) {
            interval = interval.multipliedBy(2);
        }
        return Optional.of(interval.compareTo(settings.max()) > 0 ? settings.max() : interval);
    }
}
