package com.sparta.ticket.payment.application.service.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.payment.CheckoutCommand;
import com.sparta.ticket.payment.application.dto.payment.CheckoutResult;
import com.sparta.ticket.payment.application.dto.payment.ConfirmCommand;
import com.sparta.ticket.payment.application.dto.payment.ConfirmResult;
import com.sparta.ticket.payment.application.dto.payment.PaymentDetail;
import com.sparta.ticket.payment.application.port.payment.PaymentDispatcher;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgReadyRequest;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.exception.payment.PaymentErrorCode;
import com.sparta.ticket.payment.domain.model.payment.Payment;
import com.sparta.ticket.payment.domain.model.payment.PaymentOrder;
import com.sparta.ticket.payment.domain.model.payment.PaymentStatus;
import com.sparta.ticket.payment.domain.model.pg.PgReadyResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 결제 준비·조회 흐름. 트랜잭션은 PaymentTransactions에만 있고, PG 호출은 두 트랜잭션 사이(트랜잭션 밖)에서 한다
 * (docs/payment/plan.md 4절 ①). 같은 예매의 준비 순서는 예매가 한 번에 하나씩 보내 보장한다(Task 1-1 합의).
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** 카카오 결제창에 보이는 상품명. 준비 요청에 공연 이름이 없어 고정값을 쓴다. */
    static final String ITEM_NAME = "공연 티켓";

    private final PaymentTransactions transactions;
    private final PgClientRegistry pgClients;
    private final PaymentDispatcher dispatcher;

    public ConfirmResult confirm(String orderId, ConfirmCommand command) {
        Payment payment = transactions.requestConfirm(orderId, command);
        if (payment.getStatus() == PaymentStatus.READY) {
            dispatcher.dispatch(payment.getId(), command.pgAuthToken());
        }
        return ConfirmResult.from(transactions
                .findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND)));
    }

    /**
     * 1) 같은 orderId가 있으면 재현한다(내용이 다르면 409). 2) 없으면 READY를 만든다. 3) 카카오면 트랜잭션 밖에서 PG 준비를
     * 부르고 결과를 저장한다.
     *
     * Note: 유니크 제약 충돌은 제약 이름 대신 "내 orderId가 저장돼 있는가"로 구분한다. 있으면 같은 orderId의 동시 요청이
     * 이긴 것이라 재현하고, 없으면 같은 예매의 다른 준비가 동시에 이긴 것이라 409 PAYMENT_CHECKOUT_CONFLICT다.
     */
    public CheckoutResult checkout(CheckoutCommand command) {
        PaymentOrder order = command.toOrder();
        Optional<Payment> existing = transactions.findByOrderId(order.orderId());
        if (existing.isPresent()) {
            return replay(existing.get(), order);
        }
        Payment payment;
        try {
            payment = transactions.prepare(order);
        } catch (DataIntegrityViolationException e) {
            return transactions
                    .findByOrderId(order.orderId())
                    .map(winner -> replay(winner, order))
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_CHECKOUT_CONFLICT));
        }
        return CheckoutResult.from(readyAtPg(payment));
    }

    public PaymentDetail find(String orderId) {
        Payment payment = transactions
                .findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return PaymentDetail.of(
                payment, transactions.findRefund(payment.getId()).orElse(null));
    }

    /** 같은 orderId의 재요청. 처음과 같은 내용이면 저장된 결제로 응답하고, 카카오 준비가 중간에 끊긴 결제는 준비를 다시 부른다. */
    private CheckoutResult replay(Payment payment, PaymentOrder order) {
        if (!payment.matches(order)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_CONFLICT);
        }
        return CheckoutResult.from(readyAtPg(payment));
    }

    private Payment readyAtPg(Payment payment) {
        if (!payment.needsPgReady()) {
            return payment;
        }
        PgReadyResult result = pgClients
                .get(payment.getPgProvider())
                .ready(new PgReadyRequest(payment.getOrderId(), payment.getUserId(), payment.getAmount(), ITEM_NAME));
        return transactions.applyPgReady(payment.getId(), result);
    }
}
