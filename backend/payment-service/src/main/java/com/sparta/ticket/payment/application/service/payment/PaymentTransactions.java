package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.port.payment.PaymentResultPublisher;
import com.sparta.ticket.payment.application.port.pg.PgApprovalGate;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentFailureCode;
import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 상태를 바꾸는 트랜잭션 단위. 흐름 클래스(PaymentService)가 PG 호출 앞뒤로 차례로 부른다. 메서드 하나가 상태 전이 하나다.
 * 상태를 바꾸는 메서드는 행을 잠근 뒤 현재 상태를 다시 판단한다(docs/architecture.md).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentTransactions {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentResultPublisher resultPublisher;
    private final PgApprovalGate approvalGate;
    private final Clock clock;
    private final com.sparta.ticket.payment.application.service.observation.CommittedObservation observation;
    private final PaymentReconcileProperties reconcileProperties;

    @Value("${payment.recovery.check-interval-seconds:10}")
    private long checkIntervalSeconds;

    /** 승인 접수는 준비 대체와 같은 행 잠금으로 직렬화한다. */
    @Transactional
    public Payment requestConfirm(String orderId, ConfirmCommand command) {
        Payment payment = paymentRepository
                .lockByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.READY) {
            if (payment.getAmount() != command.amount()) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
            }
            payment.requestConfirm(LocalDateTime.now(clock));
        }
        return payment;
    }

    /** PG를 호출할 단 하나의 실행자만 IN_PROGRESS 전이에 성공한다. */
    @Transactional
    public Optional<Payment> markInProgress(Long paymentId, String authToken) {
        Payment payment = locked(paymentId);
        if (payment.getStatus() != PaymentStatus.READY) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (payment.isExpired(now)) {
            fail(payment, PaymentFailureCode.EXPIRED);
        } else if (!approvalGate.allows(payment.getPgProvider())) {
            fail(payment, PaymentFailureCode.PG_UNAVAILABLE);
        } else if (payment.needsPgReady()
                || authToken == null
                || authToken.isBlank()
                || (payment.getPgProvider() == PgProvider.TOSS && authToken.length() > 200)) {
            fail(payment, PaymentFailureCode.INVALID_AUTH_TOKEN);
        } else {
            if (payment.getPgProvider() == PgProvider.TOSS) {
                // Note: 다른 결제끼리는 행 잠금이 겹치지 않는다. 키별 잠금과 조회도 함께 해야 인증키 재사용 경쟁을 막는다.
                paymentRepository.lockTransactionKey("TOSS:" + authToken);
                // 키 잠금 대기 중 선점이 만료될 수 있으므로 PG 호출 시각 기준을 다시 얻는다.
                now = LocalDateTime.now(clock);
                if (payment.isExpired(now)) {
                    fail(payment, PaymentFailureCode.EXPIRED);
                    return Optional.empty();
                }
                if (paymentRepository.isPgTransactionInUse(PgProvider.TOSS.name(), authToken)) {
                    fail(payment, PaymentFailureCode.INVALID_AUTH_TOKEN);
                    return Optional.empty();
                }
            }
            payment.startApproval(
                    payment.getPgProvider() == PgProvider.TOSS ? authToken : null,
                    now,
                    Duration.ofSeconds(checkIntervalSeconds));
            paymentRepository.saveAndFlush(payment);
            return Optional.of(payment);
        }
        return Optional.empty();
    }

    @Transactional
    public void rejectInvalidToken(Long paymentId) {
        Payment payment = locked(paymentId);
        if (payment.getStatus() == PaymentStatus.READY) {
            fail(payment, PaymentFailureCode.INVALID_AUTH_TOKEN);
        }
    }

    /** 호출자가 결제 행을 먼저 잠근 같은 트랜잭션에서만 사용한다. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void failForRecovery(Payment payment, PaymentFailureCode code) {
        fail(payment, code);
    }

    /** 요청 경로·후속 복구가 공유한다. 잠금 순서는 항상 payment → refund다. */
    @Transactional
    public void complete(Long paymentId, PgResult result) {
        Payment payment = locked(paymentId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (payment.getStatus() != PaymentStatus.IN_PROGRESS && payment.getStatus() != PaymentStatus.FAILED) {
            return;
        }
        if (result instanceof PgResult.Approved approved) {
            // Note: 다른 거래나 필수 정보가 빠진 응답으로 결제를 확정하거나 엉뚱한 거래를 환불하지 않는다.
            if (approved.transactionId() == null
                    || !approved.transactionId().equals(payment.getPgTransactionId())
                    || approved.amount() <= 0
                    || approved.at() == null) {
                if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                    payment.deferRecovery("PG_INVALID_APPROVAL", now.plusSeconds(checkIntervalSeconds));
                }
                log.error("PG 승인 응답 검증 실패. 원거래 조회가 필요하다. paymentId={}", paymentId);
                return;
            }
            if (payment.getStatus() == PaymentStatus.FAILED) {
                ensureRefund(payment, approved.amount(), now, approved.at());
                log.error("종료된 결제의 늦은 승인. 자동 취소가 필요하다. paymentId={}", paymentId);
            } else if (approved.amount() != payment.getAmount()) {
                payment.fail(PaymentFailureCode.AMOUNT_MISMATCH, "PG_APPROVED_AMOUNT_MISMATCH");
                payment.recordFailure(now, reconcileProperties.schedule());
                ensureRefund(payment, approved.amount(), now, approved.at());
                resultPublisher.paymentFinalized(payment);
            } else {
                payment.approve(approved.transactionId(), approved.at());
                observation.counter(
                        "payment.approved", "provider", payment.getPgProvider().name());
                if (payment.getConfirmRequestedAt() != null)
                    observation.duration(
                            "payment.approval.completion.duration",
                            payment.getConfirmRequestedAt(),
                            "provider",
                            payment.getPgProvider().name());
                refundRepository
                        .lockByPaymentId(paymentId)
                        .filter(refund -> refund.getStatus() == RefundStatus.WAITING)
                        .ifPresent(refund -> refund.ensureRequested(approved.amount(), now, now));
                resultPublisher.paymentFinalized(payment);
            }
        } else if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
            if (result instanceof PgResult.Rejected rejected) {
                // PG 본문·토큰이 오류 설명에 섞일 수 있어 영속 오류 설명은 고정값만 쓴다.
                String code = rejected.code();
                if (code == null || !code.matches("[A-Za-z0-9_-]{1,80}")) {
                    payment.deferRecovery("PG_INVALID_REJECTION", now.plusSeconds(checkIntervalSeconds));
                    return;
                }
                payment.fail(code, "PG_APPROVAL_REJECTED");
                payment.recordFailure(now, reconcileProperties.schedule());
                skipWaitingRefund(payment);
                resultPublisher.paymentFinalized(payment);
            } else {
                payment.deferRecovery("PG_APPROVAL_UNKNOWN", now.plusSeconds(checkIntervalSeconds));
            }
        }
    }

    private void ensureRefund(Payment payment, int amount, LocalDateTime now, LocalDateTime approvedAt) {
        Optional<Refund> existing = refundRepository.lockByPaymentId(payment.getId());
        boolean newIntent = existing.isEmpty()
                || existing.get().getStatus() == RefundStatus.WAITING
                || existing.get().getStatus() == RefundStatus.SKIPPED;
        if (existing.isPresent()) {
            existing.get().ensureRequested(amount, now, now);
        } else {
            refundRepository.saveAndFlush(Refund.requested(payment, RefundReason.AUTO_VOID, amount, now, now));
        }
        if (newIntent) {
            observation.duration(
                    "payment.compensation.discovery.duration",
                    approvedAt,
                    "provider",
                    payment.getPgProvider().name());
            observation.counter(
                    "payment.compensation.intent",
                    "provider",
                    payment.getPgProvider().name());
        }
    }

    private void fail(Payment payment, PaymentFailureCode code) {
        payment.fail(code, code.name());
        payment.recordFailure(LocalDateTime.now(clock), reconcileProperties.schedule());
        skipWaitingRefund(payment);
        resultPublisher.paymentFinalized(payment);
    }

    private void skipWaitingRefund(Payment payment) {
        refundRepository
                .lockByPaymentId(payment.getId())
                .filter(refund -> refund.getStatus() == RefundStatus.WAITING)
                .ifPresent(refund -> {
                    refund.skip();
                    resultPublisher.refundFinalized(payment, refund);
                });
    }

    private Payment locked(Long id) {
        return paymentRepository
                .lockById(id)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Optional<Refund> findRefund(Long paymentId) {
        return refundRepository.findByPaymentId(paymentId);
    }

    /**
     * 새 결제를 READY로 만든다. 같은 예매의 살아 있는 결제를 잠가 확인한다: DONE·IN_PROGRESS·승인 요청을 받은 READY면 409,
     * 그 밖의 READY는 SUPERSEDED로 대체한다. 유니크 제약에 걸리면 DataIntegrityViolationException이 호출한 쪽으로 나간다.
     *
     * Note: 대체 UPDATE를 먼저 flush한다. Hibernate는 flush 때 INSERT를 UPDATE보다 먼저 실행하므로, 그대로 두면 새 READY의
     * INSERT가 아직 READY인 이전 결제와 부분 UNIQUE(예매당 살아 있는 결제 하나)에서 충돌한다.
     */
    @Transactional
    public Payment prepare(PaymentOrder order) {
        Payment payment = Payment.ready(order);
        paymentRepository.lockLiveByReservationId(order.reservationId()).ifPresent(live -> {
            rejectIfInProgressOrDone(live);
            live.supersede();
            live.recordFailure(LocalDateTime.now(clock), reconcileProperties.schedule());
            paymentRepository.saveAndFlush(live);
        });
        return paymentRepository.saveAndFlush(payment);
    }

    /** PG 준비 결과를 반영한다. 잠근 뒤 아직 준비를 기다리는 결제일 때만 바꾼다(Payment.applyPgReady). */
    @Transactional
    public Payment applyPgReady(Long paymentId, PgReadyResult result) {
        Payment payment = paymentRepository
                .lockById(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        payment.applyPgReady(result);
        if (payment.getStatus() == PaymentStatus.FAILED) {
            payment.recordFailure(LocalDateTime.now(clock), reconcileProperties.schedule());
        }
        return payment;
    }

    private static void rejectIfInProgressOrDone(Payment live) {
        if (live.getStatus() == PaymentStatus.DONE) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_DONE);
        }
        if (live.getStatus() == PaymentStatus.IN_PROGRESS || live.isConfirmRequested()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_IN_PROGRESS);
        }
    }
}
