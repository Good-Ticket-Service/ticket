package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import com.sparta.ticket.payment.domain.model.refund.Refund;
import com.sparta.ticket.payment.domain.repository.payment.PaymentRepository;
import com.sparta.ticket.payment.domain.repository.refund.RefundRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 상태를 바꾸는 트랜잭션 단위. 흐름 클래스(PaymentService)가 PG 호출 앞뒤로 차례로 부른다. 메서드 하나가 상태 전이 하나다.
 * 상태를 바꾸는 메서드는 행을 잠근 뒤 현재 상태를 다시 판단한다(docs/payment/plan.md 6절).
 */
@Component
@RequiredArgsConstructor
public class PaymentTransactions {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

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
