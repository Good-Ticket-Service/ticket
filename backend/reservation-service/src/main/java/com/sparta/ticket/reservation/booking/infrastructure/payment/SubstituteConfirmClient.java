package com.sparta.ticket.reservation.booking.infrastructure.payment;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Outbox에 남은 승인 요청을 대신 보내는 작업자만 쓰는 payment 호출(#422). 재시도를 붙이지 않는다. 결과를 모르는 승인은 다시 보내지
 * 않는 계약이고, 조회도 한 번의 대기가 작업자의 다시 볼 간격보다 짧아야 하기 때문이다. 타임아웃은 이 contextId로 따로 둔다.
 * 응답을 feign.Response로 받아 503의 Retry-After와 4xx 코드를 어댑터가 직접 읽는다. 공통 오류 변환을 거치면 Retry-After가 사라진다.
 */
@FeignClient(name = "payment-service", contextId = "substituteConfirmClient", path = "/internal/payments")
public interface SubstituteConfirmClient {

    @PostMapping("/{orderId}/confirm")
    Response confirm(@PathVariable("orderId") String orderId, @RequestBody SubstituteConfirmRequest request);

    @GetMapping("/{orderId}")
    Response find(@PathVariable("orderId") String orderId);
}
