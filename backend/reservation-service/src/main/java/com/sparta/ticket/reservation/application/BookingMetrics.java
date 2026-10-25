package com.sparta.ticket.reservation.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 예약과 결제가 어긋난 건수를 센다. 측정의 불일치 건수와 결제 중 체류 알림의 근거가 된다. */
@Component
public class BookingMetrics {

    private final Counter expiredHolds;
    private final Counter resentConfirms;
    private final Counter lateSuccesses;
    private final Counter stuckPayments;

    public BookingMetrics(MeterRegistry registry) {
        this.expiredHolds = Counter.builder("reservation.expired")
                .description("만료 스케줄러가 EXPIRED로 바꾼 예약 수")
                .register(registry);
        this.resentConfirms = Counter.builder("reservation.payment.confirm.resent")
                .description("payment가 받지 못한 승인 요청을 다시 보낸 수")
                .register(registry);
        this.lateSuccesses = Counter.builder("reservation.payment.late_success")
                .description("이미 끝났거나 지난 시도인 예약에 도착한 결제 성공 수. 자동 환불 대상(F6)")
                .register(registry);
        this.stuckPayments = Counter.builder("reservation.payment.stuck")
                .description("상한 시간을 넘겨 결제 중으로 머문 예약을 발견한 수")
                .register(registry);
    }

    public void expiredHold() {
        expiredHolds.increment();
    }

    public void resentConfirm() {
        resentConfirms.increment();
    }

    public void lateSuccess() {
        lateSuccesses.increment();
    }

    public void stuckPayment() {
        stuckPayments.increment();
    }
}
