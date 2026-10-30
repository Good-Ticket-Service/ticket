package com.sparta.ticket.performance.infrastructure.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 예매 서비스(좌석 모듈)의 회차 좌석 준비 내부 API. 계약은 docs/api.md 「좌석 내부 API」(이슈 #175)다.
 * Note: Feign 재시도를 붙이지 않는다. 다시 보내기는 다음 시도 시각을 기록하는 재시도 스케줄러가 맡는다. 타임아웃은
 * config-repo의 contextId(seatSetupClient) 설정이다(connect 1초, read 5초).
 */
@FeignClient(name = "reservation-service", contextId = "seatSetupClient", path = "/internal/schedules")
public interface SeatSetupFeignClient {

    @PostMapping("/{scheduleId}/seat-setups")
    SeatSetupResponse setUp(@PathVariable("scheduleId") Long scheduleId, @RequestBody SeatSetupRequest request);
}
