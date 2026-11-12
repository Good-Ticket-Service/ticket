package com.sparta.ticket.reservation.booking.application.reconcile;

import com.sparta.ticket.reservation.booking.application.BookingMetrics;
import com.sparta.ticket.reservation.booking.application.payment.AdmissionGate;
import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 중 예약 수를 세어 게이지를 갱신한다. 폴링 간격(Retry-After)이 이 게이지로 정해지므로 결과 확인 주기(30초)보다 자주 돈다.
 * 측정에서 결제 중 예약이 분당 수천 건 늘 때 30초 주기로는 간격이 늦게 늘어 폴링이 세 배로 치솟았다.
 * COUNT는 결제 중 상태의 부분 인덱스를 읽어 2만 건에 수 ms다.
 * 같은 자리에서 늦은 결제(접수 후 정한 시간이 지나도 결과가 없는 결제)도 세어 결제 준비 접수 제한의 신호로 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class PaymentBacklogService {

    private final ReservationRepository reservationRepository;
    private final BookingMetrics metrics;
    private final AdmissionGate admissionGate;
    private final Clock clock;

    @Transactional(readOnly = true)
    public long refresh() {
        LocalDateTime now = LocalDateTime.now(clock);
        long inProgress = reservationRepository.countPaymentsInProgress();
        metrics.paymentsInProgress(inProgress);
        admissionGate.update(reservationRepository.countLatePayments(admissionGate.lateCutoff(now), now), now);
        return inProgress;
    }
}
