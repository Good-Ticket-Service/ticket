package com.sparta.ticket.reservation.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 예약과 결제가 어긋난 정도를 센다. 측정의 불일치 건수와 결제 중 체류 알림의 근거가 된다. */
@Component
public class BookingMetrics {

    private final Counter expiredHolds;
    private final Counter releasedHolds;
    private final Counter resentConfirms;
    private final Counter lateSuccesses;
    private final Counter canceled;
    private final Counter resentRefunds;
    private final Counter unknownRefundOrders;
    private final AtomicLong paymentsInProgress = new AtomicLong();
    private final AtomicLong stuckPayments = new AtomicLong();

    public BookingMetrics(MeterRegistry registry) {
        this.expiredHolds = Counter.builder("reservation.expired")
                .description("선점 시간이 지나 EXPIRED로 바꾼 예약 수. 만료 스케줄러와 선점 해제 때의 정리를 함께 센다")
                .register(registry);
        this.releasedHolds = Counter.builder("reservation.released")
                .description("사용자가 선점을 직접 해제해 RELEASED로 바꾼 예약 수")
                .register(registry);
        this.resentConfirms = Counter.builder("reservation.payment.confirm.resent")
                .description("payment가 받지 못한 승인 요청을 다시 보낸 수")
                .register(registry);
        this.lateSuccesses = Counter.builder("reservation.payment.late_success")
                .description("이미 끝났거나 지난 시도인 예약에 도착한 결제 성공 수. 자동 환불 대상")
                .register(registry);
        this.canceled = Counter.builder("reservation.canceled")
                .description("payment가 환불 요청을 받아 CANCELED로 바꾼 예약 수")
                .register(registry);
        this.resentRefunds = Counter.builder("reservation.cancel.refund.resent")
                .description("취소 중으로 오래 머문 예약의 환불을 다시 요청한 수")
                .register(registry);
        this.unknownRefundOrders = Counter.builder("reservation.cancel.unknown_order")
                .description("payment가 모르는 orderId라 환불을 요청하지 못한 수. 0보다 크면 관리자가 확인한다")
                .register(registry);
        Gauge.builder("reservation.payment.in_progress", paymentsInProgress, AtomicLong::get)
                .description("결제 중 예약 수. 결과 확인 스케줄러가 실행마다 갱신한다")
                .register(registry);
        Gauge.builder("reservation.payment.stuck", stuckPayments, AtomicLong::get)
                .description("상한 시간을 넘겨 결제 중인 예약 수. 0보다 크면 관리자가 확인한다")
                .register(registry);
    }

    public void expiredHold() {
        expiredHolds.increment();
    }

    public void releasedHold() {
        releasedHolds.increment();
    }

    public void resentConfirm() {
        resentConfirms.increment();
    }

    public void lateSuccess() {
        lateSuccesses.increment();
    }

    public void canceled() {
        canceled.increment();
    }

    public void resentRefund() {
        resentRefunds.increment();
    }

    public void unknownRefundOrder() {
        unknownRefundOrders.increment();
    }

    public void paymentBacklog(long inProgress, long stuck) {
        paymentsInProgress.set(inProgress);
        stuckPayments.set(stuck);
    }
}
