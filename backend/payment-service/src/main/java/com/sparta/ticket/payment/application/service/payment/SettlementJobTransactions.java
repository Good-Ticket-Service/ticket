package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.application.service.settlement.SettlementBackfillTransactions;
import com.sparta.ticket.payment.application.service.settlement.SettlementBackfillTransactions.Outcome;
import com.sparta.ticket.payment.application.service.settlement.SettlementRefundTransactions;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.model.settlement.RefundSnapshot;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJob;
import com.sparta.ticket.payment.domain.model.settlement.SettlementJobStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementJobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 반영 작업의 트랜잭션(#304). 결제가 자기 행을 잠그고 현재 결제·환불 상태를 스냅샷으로 정산에 넘긴다.
 * 반영은 상태 기반이다. 같은 결제의 승인 작업과 환불 완료 작업이 어떤 순서로 처리돼도, 이미 반영된 부분은 건너뛰고 같은 결과가 된다.
 *
 * Note: 작업 하나를 트랜잭션 하나로 처리한다. 작업 행을 SKIP LOCKED로 잠근 채 반영하고 완료로 바꿔 커밋한다. 결제 Recovery와 달리
 * 트랜잭션 밖 외부 호출이 없어 점유를 따로 커밋할 필요가 없다. 실패하거나 프로세스가 죽으면 롤백과 함께 잠금이 풀린다.
 * 잠금 순서는 작업 → payment → refund → SALE이다. 결제·환불 경로는 작업 행을 잠그지 않고 INSERT ... ON CONFLICT DO NOTHING만
 * 하므로 순환 대기가 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SettlementJobTransactions {

    /** 처리한 작업. 반영이 실패하면 예외로 끝나므로, 실패 기록에 쓸 작업 ID는 호출자가 넘긴 시도 목록의 마지막 값이다. */
    public record Processed(Long jobId, Long paymentId) {}

    private final SettlementJobRepository jobs;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final SettlementBackfillTransactions settlement;
    private final SettlementRefundTransactions settlementRefunds;
    private final SettlementJobProperties properties;
    private final Clock clock;
    private final CommittedObservation observation;

    /**
     * 실행 시각이 된 작업 하나를 잠그고, 결제의 정산을 현재 결제·환불 상태에 맞춘 뒤 완료한다.
     * 고른 작업 ID는 반영 전에 attemptedIds에 넣는다. 반영이 실패하면 예외를 그대로 던져 전체를 롤백하고, 호출자가 그 ID로 실패를 기록한다.
     *
     * @return 대기 작업이 없으면 empty
     */
    @Transactional
    public Optional<Processed> processNext(List<Long> attemptedIds) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<SettlementJob> next =
                jobs.lockNextDue(now, attemptedIds).stream().findFirst();
        if (next.isEmpty()) return Optional.empty();
        SettlementJob job = next.get();
        attemptedIds.add(job.getId());
        observation.duration("payment.execution.claim.delay", job.getNextAttemptAt(), "task", "settlement-job");
        Payment payment = payments.lockById(job.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("정산 작업의 결제가 없다: " + job.getPaymentId()));
        RefundSnapshot refund = refunds.lockByPaymentId(payment.getId())
                .filter(r -> r.getStatus() != RefundStatus.SKIPPED && r.getStatus() != RefundStatus.WAITING)
                .map(SettlementSnapshots::refund)
                .orElse(null);
        sync(payment, refund);
        job.complete(LocalDateTime.now(clock));
        jobs.saveAndFlush(job);
        observation.counter(
                "settlement.job.completed", "reason", job.getReason().name());
        if (job.getCreatedAt() != null)
            observation.duration(
                    "settlement.job.lag",
                    job.getCreatedAt(),
                    "reason",
                    job.getReason().name());
        return Optional.of(new Processed(job.getId(), job.getPaymentId()));
    }

    /**
     * 반영 실패를 기록한다. 그사이 다른 실행이 처리해 대기 상태가 아니면 아무것도 하지 않는다.
     *
     * @param error 고정 형식의 짧은 오류 이름(예외 클래스 이름). 금액·계좌·SQL 원문을 넣지 않는다
     * @return 이번 실패로 격리(BLOCKED)됐으면 true
     */
    @Transactional
    public boolean fail(Long jobId, String error) {
        SettlementJob job = jobs.lockById(jobId).orElse(null);
        if (job == null || job.getStatus() != SettlementJobStatus.PENDING) return false;
        LocalDateTime now = LocalDateTime.now(clock);
        boolean blocked = job.fail(now, error, properties.maxAttempts(), properties.delayAfter(job.getAttempts() + 1));
        jobs.saveAndFlush(job);
        observation.counter(
                "settlement.job.failed", "reason", job.getReason().name(), "blocked", String.valueOf(blocked));
        return blocked;
    }

    /**
     * 승인된 결제만 정산 대상이다. 실패 결제(늦은 승인의 자동 취소 등)는 SALE이 없어 할 일이 없다.
     * SALE이 없거나 계산 실패면 판매 분개와 현재 환불 상태를 함께 반영하고, 이미 있으면 환불 상태만 맞춘다.
     * 환불 접수의 보류(HELD)는 접수 트랜잭션이 즉시 하지만, 그때 SALE이 없었으면 여기서 맞춘다.
     */
    private void sync(Payment payment, RefundSnapshot refund) {
        if (payment.getApprovedAt() == null
                || (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.CANCELED)) return;
        Outcome outcome = settlement.backfill(SettlementSnapshots.sale(payment), refund, "worker");
        if (outcome != Outcome.SKIPPED || refund == null) return;
        if (refund.completed())
            settlementRefunds.refundCompleted(payment.getId(), refund.refundId(), refund.completedAt());
        else settlementRefunds.refundAccepted(payment.getId(), refund.refundId());
    }
}
