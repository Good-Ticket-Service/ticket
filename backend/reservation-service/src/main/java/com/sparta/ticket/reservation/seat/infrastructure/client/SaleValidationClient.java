package com.sparta.ticket.reservation.seat.infrastructure.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * performance 내부 API의 회차 판매 검증. 재시도를 붙이지 않는다.
 * Note: 선점 요청 안에서 동기로 부르는 경로다. 재시도(IdempotentRetryConfig)를 붙이면 공연 서비스가 느릴 때 한 번의 선점이
 * read timeout × 3까지 요청 스레드를 붙잡는다. 오픈 직후 몰리는 경로라 빨리 503으로 끝내고 사용자가 다시 시도하게 한다.
 */
@FeignClient(name = "performance-service", contextId = "saleValidationClient", path = "/internal/schedules")
public interface SaleValidationClient {

    @PostMapping("/{scheduleId}/sale-validations")
    SaleValidationResponse validate(
            @PathVariable("scheduleId") Long scheduleId, @RequestBody SaleValidationRequest request);
}
