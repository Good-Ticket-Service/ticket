package com.sparta.ticket.fakepg.presentation.controller.toss;

import com.sparta.ticket.fakepg.application.service.toss.TossPaymentService;
import com.sparta.ticket.fakepg.presentation.dto.toss.CheckoutSimulationRequest;
import com.sparta.ticket.fakepg.presentation.dto.toss.CheckoutSimulationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 인증 시뮬레이션. 실제 토스에서는 브라우저가 결제창을 띄우고 사용자가 인증하면 paymentKey를 받는다.
 * 이 프로젝트는 프론트가 없으므로, 테스트·k6가 이 API로 그 단계를 대신한다. 실제 토스에는 없는 fake PG 전용 API다.
 */
@RestController
@RequestMapping("/fake/toss")
@RequiredArgsConstructor
public class TossCheckoutSimulationController {

    private static final String DEFAULT_ORDER_NAME = "공연 티켓";

    private final TossPaymentService paymentService;

    @PostMapping("/checkout")
    public CheckoutSimulationResponse checkout(@Valid @RequestBody CheckoutSimulationRequest request) {
        String orderName =
                request.orderName() == null || request.orderName().isBlank() ? DEFAULT_ORDER_NAME : request.orderName();
        return CheckoutSimulationResponse.from(
                paymentService.authorize(request.orderId(), orderName, request.amount()));
    }
}
