package com.sparta.ticket.reservation.seat.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * seat 통합 테스트의 공용 빈. ReservationIntegrationTest가 가져와 컨텍스트 하나에서 쓴다.
 * 판매 검증은 공연 서비스를 부르는 Feign 어댑터 대신 가짜를 우선 쓴다(@MockBean은 컨텍스트를 나눠 쓰지 않는다).
 * 잔여석·좌석 배치도 캐시의 만료 시계도 가짜로 바꿔, 기다리지 않고 TTL이 지난 상황을 만든다.
 */
@TestConfiguration
public class SeatTestConfig {

    @Bean
    @Primary
    public FakeSaleValidationPort fakeSaleValidationPort() {
        return new FakeSaleValidationPort();
    }

    /** 잔여석·좌석 배치도 캐시가 함께 쓰는 만료 시계. 테스트가 시간을 흘려 TTL이 지난 상황을 만든다. */
    @Bean
    @Primary
    public MutableTicker mutableTicker() {
        return new MutableTicker();
    }
}
