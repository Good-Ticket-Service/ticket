package com.sparta.ticket.reservation.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 예약과 결제가 어긋난 정도를 센다. 측정의 불일치 건수와 결제 중 체류 알림의 근거가 된다. */
@Component
public class BookingMetrics {

    /** Outbox에 묶인 승인 요청을 HTTP로 대신 보낸 결과. 지표의 result 태그 값이다. */
    public enum DrainResult {
        /** 결과가 정해져 반영했다. 비운 요청 수다. */
        SETTLED,
        /** payment가 받았고 결과는 정해지지 않았다. 조회로 결과를 묻는다. */
        ACCEPTED,
        /** 조회해 보니 payment가 승인을 받은 적이 없다. 요청은 다시 보낼 차례로 돌린다. */
        NOT_RECEIVED,
        /** payment가 받을 자리가 없다며 돌려보냈다. 요청은 다시 보낼 차례로 돌린다. */
        BUSY,
        /** payment가 받지 않았다. 시한 뒤 결과 확인이 끝낸다. */
        REJECTED,
        /** 답을 받지 못했다. resendAfter 뒤 조회로 결과를 묻는다. */
        UNKNOWN
    }

    private final Counter expiredHolds;
    private final Counter releasedHolds;
    private final Counter resentConfirms;
    private final Counter unreceivedConfirms;
    private final Counter outboxPublished;
    private final Counter outboxPublishFailures;
    private final AtomicLong resultWaiters = new AtomicLong();
    private final Counter resultWaitTimeouts;
    private final Counter settledBroadcastFailures;
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
    private final Counter admissionRejections;
    private final Counter staleAdmissionSignals;
    private final AtomicLong latePayments = new AtomicLong();
    private final AtomicLong admissionShedding = new AtomicLong();
    private final AtomicLong admissionReopenRate = new AtomicLong();
    private final AtomicLong admissionRetryAfter = new AtomicLong();
    private final Counter fallbackConfirms;
    private final AtomicLong outboxOldestWaiting = new AtomicLong();
    private final AtomicLong confirmFallbackActive = new AtomicLong();
    private final Map<DrainResult, Counter> drainResults = new EnumMap<>(DrainResult.class);

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
        this.admissionRejections = Counter.builder("reservation.admission.rejected")
                .description("결제 결과가 계속 늦어져 거절한 결제 준비 수")
                .register(registry);
        this.staleAdmissionSignals = Counter.builder("reservation.admission.signal.stale")
                .description("거절 상태였지만 늦은 결제 신호가 멈춰 막지 않고 받은 결제 준비 수. 결제 중 집계가 돌지 않는다는 뜻이다")
                .register(registry);
        Gauge.builder("reservation.payment.late", latePayments, AtomicLong::get)
                .description("접수 후 정한 시간이 지나도 결과가 없는 결제 중 예약 수")
                .register(registry);
        Gauge.builder("reservation.admission.shedding", admissionShedding, AtomicLong::get)
                .description("결제 준비를 거절하는 중이면 1")
                .register(registry);
        Gauge.builder("reservation.admission.reopen.rate", admissionReopenRate, AtomicLong::get)
                .description("거절을 풀며 조금씩 여는 동안 초마다 통과시키는 결제 준비 수. 활짝 열었거나 거절 중이면 0")
                .register(registry);
        Gauge.builder("reservation.admission.retry_after", admissionRetryAfter, AtomicLong::get)
                .baseUnit("seconds")
                .description("결제 준비 거절 응답에 싣는 Retry-After. 기다리는 사용자가 많으면 늘어난다")
                .register(registry);
        Gauge.builder("reservation.payment.result_waiters", resultWaiters, AtomicLong::get)
                .description("결제 결과를 롱폴링으로 기다리는 상태 조회 수(이 인스턴스)")
                .register(registry);
        this.resultWaitTimeouts = Counter.builder("reservation.payment.result_wait_timeouts")
                .description("롱폴링 대기 시간 안에 결과가 정해지지 않아 결제 중으로 답한 수")
                .register(registry);
        this.settledBroadcastFailures = Counter.builder("reservation.payment.settled_broadcast_failed")
                .description("결제 결과를 다른 인스턴스에 알리지 못한 수. 그 인스턴스의 기다리던 조회는 대기 시간 끝에 결과를 본다")
                .register(registry);
        Gauge.builder("reservation.payment.stuck", stuckPayments, AtomicLong::get)
                .description("상한 시간을 넘겨 결제 중인 예약 수. 0보다 크면 관리자가 확인한다")
                .register(registry);
        this.fallbackConfirms = Counter.builder("reservation.payment.confirm.fallback")
                .description("메시지 승인으로 설정했지만 승인 요청이 브로커로 나가지 못해 동기로 부른 승인 수. 결제 중으로 바꾼 승인만 세고 같은 승인의 재시도는 세지 않는다")
                .register(registry);
        Gauge.builder("reservation.outbox.oldest_waiting", outboxOldestWaiting, AtomicLong::get)
                .baseUnit("seconds")
                .description("Outbox에서 발행기가 다음에 보낼 메시지가 기다린 시간. 비었으면 0. 결제 중 집계가 갱신한다")
                .register(registry);
        Gauge.builder("reservation.payment.confirm.fallback.active", confirmFallbackActive, AtomicLong::get)
                .description("새 승인을 메시지 대신 동기로 부르는 중이면 1(이 인스턴스)")
                .register(registry);
        for (DrainResult result : DrainResult.values()) {
            drainResults.put(
                    result,
                    Counter.builder("reservation.payment.confirm.drain")
                            .tag("result", result.name().toLowerCase(Locale.ROOT))
                            .description("승인 요청이 브로커로 나가지 못하는 동안 Outbox에 남은 요청을 HTTP로 대신 보낸 수. result는 payment의 답이다")
                            .register(registry));
        }
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

    public void resultWaiterAdded() {
        resultWaiters.incrementAndGet();
    }

    public void resultWaiterRemoved() {
        resultWaiters.decrementAndGet();
    }

    public long resultWaitersCount() {
        return resultWaiters.get();
    }

    public void resultWaitTimedOut() {
        resultWaitTimeouts.increment();
    }

    public void settledBroadcastFailed() {
        settledBroadcastFailures.increment();
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

    /** 이 인스턴스가 지금까지 반영한 결제 결과 수. 접수 제한이 결제가 끝나는 속도를 잴 때 쓴다. */
    public long paymentResultsAppliedTotal() {
        return (long) paymentResultsApplied.count();
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

    /** 결제 중 예약 수만 갱신한다. 폴링 간격이 이 값을 읽으므로 결과 확인보다 자주 센다. */
    public void paymentsInProgress(long count) {
        paymentsInProgress.set(count);
    }

    public void admissionRejected() {
        admissionRejections.increment();
    }

    public void staleAdmissionSignal() {
        staleAdmissionSignals.increment();
    }

    public void admissionState(long late, boolean shedding, long reopenRate, long retryAfterSeconds) {
        latePayments.set(late);
        admissionShedding.set(shedding ? 1 : 0);
        admissionReopenRate.set(reopenRate);
        admissionRetryAfter.set(retryAfterSeconds);
    }

    public void fallbackConfirm() {
        fallbackConfirms.increment();
    }

    public void drainResult(DrainResult result) {
        drainResults.get(result).increment();
    }

    public void confirmFallbackState(long oldestWaitingSeconds, boolean fallbackActive) {
        outboxOldestWaiting.set(oldestWaitingSeconds);
        confirmFallbackActive.set(fallbackActive ? 1 : 0);
    }

    public void paymentBacklog(long inProgress, long stuck) {
        paymentsInProgress.set(inProgress);
        stuckPayments.set(stuck);
    }
}
