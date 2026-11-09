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
    private final Counter unreceivedConfirms;
    private final Counter outboxPublished;
    private final Counter outboxPublishFailures;
    private final Counter lateSuccesses;
    private final Counter canceled;
    private final Counter resentRefunds;
    private final Counter unknownRefundOrders;
    private final Counter unconfirmedRefunds;
    private final Counter paymentResultsApplied;
    private final Counter paymentResultDeadLetters;
    private final Counter paymentResultDeliveryFailures;
    private final Counter scheduleCancelDeadLetters;
    private final Counter scheduleCancelDeliveryFailures;
    private final AtomicLong confirmedAfterCompleted = new AtomicLong();
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
        this.unreceivedConfirms = Counter.builder("reservation.payment.confirm.unreceived")
                .description("메시지로 요청한 승인이 시한까지 payment에 닿지 않아 실패로 끝낸 수. 늘면 발행기나 브로커를 확인한다")
                .register(registry);
        this.outboxPublished = Counter.builder("reservation.outbox.published")
                .description("Outbox에서 보내고 지운 메시지 수")
                .register(registry);
        this.outboxPublishFailures = Counter.builder("reservation.outbox.publish_failed")
                .description("Outbox 메시지를 보내지 못한 수. 행은 남아 다음 차례에 다시 보낸다")
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
        this.unconfirmedRefunds = Counter.builder("reservation.payment.unconfirmed_refunded")
                .description("결제는 성공했는데 확정하지 못해, payment가 환불 요청을 받고 CANCELED로 끝낸 예약 수")
                .register(registry);
        this.paymentResultsApplied = Counter.builder("reservation.payment_result.applied")
                .description("payment의 결과 메시지로 예약 상태를 바꾼 수. 승인 응답이나 결과 확인이 먼저 반영한 결과는 세지 않는다")
                .register(registry);
        this.paymentResultDeadLetters = Counter.builder("reservation.payment_result.dead_letter")
                .description("해석하지 못해 DLT로 옮긴 결제 결과 메시지 수. 그 결제는 결과 확인 스케줄러가 끝내므로 빠른 전달만 잃는다")
                .register(registry);
        this.paymentResultDeliveryFailures = Counter.builder("reservation.payment_result.delivery_failed")
                .description("결제 결과 메시지 처리 실패 수. 계속 늘면 재시도로 파티션이 멈춘 것이다")
                .register(registry);
        this.scheduleCancelDeadLetters = Counter.builder("reservation.schedule_cancel.dead_letter")
                .description("해석하지 못해 DLT로 옮긴 회차 취소 메시지 수. 그 회차는 예약 정리가 시작되지 않으므로 관리자가 확인한다")
                .register(registry);
        this.scheduleCancelDeliveryFailures = Counter.builder("reservation.schedule_cancel.delivery_failed")
                .description("회차 취소 메시지 처리 실패 수. 계속 늘면 재시도로 파티션이 멈춘 것이다")
                .register(registry);
        Gauge.builder("reservation.schedule_cancel.confirmed_after_completed", confirmedAfterCompleted, AtomicLong::get)
                .description("정리를 완료한 회차에 남은 확정 예약 수. 0보다 크면 환불이 빠진 것이라 관리자가 확인한다")
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

    public void unreceivedConfirm() {
        unreceivedConfirms.increment();
    }

    public void outboxPublished(int count) {
        outboxPublished.increment(count);
    }

    public void outboxPublishFailed() {
        outboxPublishFailures.increment();
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

    public void unconfirmedRefunded() {
        unconfirmedRefunds.increment();
    }

    public void paymentResultApplied() {
        paymentResultsApplied.increment();
    }

    public void paymentResultDeadLetter() {
        paymentResultDeadLetters.increment();
    }

    public void paymentResultDeliveryFailed() {
        paymentResultDeliveryFailures.increment();
    }

    public void scheduleCancelDeadLetter() {
        scheduleCancelDeadLetters.increment();
    }

    public void scheduleCancelDeliveryFailed() {
        scheduleCancelDeliveryFailures.increment();
    }

    public void confirmedAfterCompleted(long count) {
        confirmedAfterCompleted.set(count);
    }

    /** 결과 확인 스케줄러가 마지막으로 센 결제 중 예약 수. 폴링 간격을 정하는 부하 신호로 쓴다. */
    public long paymentsInProgressCount() {
        return paymentsInProgress.get();
    }

    public void paymentBacklog(long inProgress, long stuck) {
        paymentsInProgress.set(inProgress);
        stuckPayments.set(stuck);
    }
}
