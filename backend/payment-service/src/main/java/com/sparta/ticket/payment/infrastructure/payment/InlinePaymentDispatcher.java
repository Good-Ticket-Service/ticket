package com.sparta.ticket.payment.infrastructure.payment;

import com.sparta.ticket.payment.application.dto.payment.ApprovalClaim;
import com.sparta.ticket.payment.application.port.payment.PaymentDispatcher;
import com.sparta.ticket.payment.application.service.payment.PaymentProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InlinePaymentDispatcher implements PaymentDispatcher {
    private final PaymentProcessor processor;

    @Override
    public void dispatch(ApprovalClaim claim, String authToken) {
        processor.process(claim, authToken);
    }
}
