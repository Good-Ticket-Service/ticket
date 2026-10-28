package com.sparta.ticket.payment.presentation.internal;

import com.sparta.ticket.payment.application.service.payment.PaymentService;
import com.sparta.ticket.payment.presentation.dto.internal.CheckoutRequest;
import com.sparta.ticket.payment.presentation.dto.internal.CheckoutResponse;
import com.sparta.ticket.payment.presentation.dto.internal.ConfirmRequest;
import com.sparta.ticket.payment.presentation.dto.internal.ConfirmResponse;
import com.sparta.ticket.payment.presentation.dto.internal.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매 서비스가 부르는 결제 내부 API(docs/api.md 결제 내부). Gateway가 /internal/**을 막아 외부에서 호출할 수 없다.
 * 서비스 간 계약이라 응답을 ApiResponse로 감싸지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/payments")
public class InternalPaymentController {

    private final PaymentService paymentService;

    /** 결제 준비. 같은 orderId·같은 내용이면 처음 응답을 그대로 돌려준다. */
    @PostMapping
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        return CheckoutResponse.from(paymentService.checkout(request.toCommand()));
    }

    @GetMapping("/{orderId}")
    public PaymentResponse find(@PathVariable String orderId) {
        return PaymentResponse.from(paymentService.find(orderId));
    }

    @PostMapping("/{orderId}/confirm")
    public ConfirmResponse confirm(@PathVariable String orderId, @Valid @RequestBody ConfirmRequest request) {
        return ConfirmResponse.from(paymentService.confirm(orderId, request.toCommand()));
    }
}
