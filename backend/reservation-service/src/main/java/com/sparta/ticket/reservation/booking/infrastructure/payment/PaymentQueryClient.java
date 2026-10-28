package com.sparta.ticket.reservation.booking.infrastructure.payment;

import com.sparta.ticket.common.feign.IdempotentRetryConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** payment 내부 API 중 조회. 상태를 바꾸지 않으므로 연결 실패·타임아웃이면 다시 보낸다. */
@FeignClient(
        name = "payment-service",
        contextId = "paymentQueryClient",
        path = "/internal/payments",
        configuration = IdempotentRetryConfig.class)
public interface PaymentQueryClient {

    @GetMapping("/{orderId}")
    PaymentDetailResponse find(@PathVariable("orderId") String orderId);
}
