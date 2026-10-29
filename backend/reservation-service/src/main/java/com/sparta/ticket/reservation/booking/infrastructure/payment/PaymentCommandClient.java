package com.sparta.ticket.reservation.booking.infrastructure.payment;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * payment 내부 API 중 상태를 바꾸는 호출. 재시도를 붙이지 않는다.
 * Note: 재시도(IdempotentRetryConfig)는 FeignClient 단위로 붙으므로 조회와 명령을 두 클라이언트로 나눈다. 승인에 재시도를 붙이면
 * 한 번의 승인이 read timeout × 3까지 요청 스레드를 붙잡는다. 승인을 다시 보내는 일은 결과 확인 스케줄러만 한다.
 * 환불도 같다. 다시 요청하는 일은 취소 확인 스케줄러만 한다.
 */
@FeignClient(name = "payment-service", contextId = "paymentCommandClient", path = "/internal/payments")
public interface PaymentCommandClient {

    @PostMapping
    PaymentCheckoutResponse checkout(@RequestBody PaymentCheckoutRequest request);

    @PostMapping("/{orderId}/confirm")
    PaymentConfirmResponse confirm(@PathVariable("orderId") String orderId, @RequestBody PaymentConfirmRequest request);

    @PostMapping("/{orderId}/refund")
    PaymentRefundResponse refund(@PathVariable("orderId") String orderId, @RequestBody PaymentRefundRequest request);
}
