package com.sparta.ticket.reservation.seat.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * seat 통합 테스트의 공용 빈. ReservationIntegrationTest가 가져와 컨텍스트 하나에서 쓴다.
 * 판매 검증은 공연 서비스를 부르는 Feign 어댑터 대신 가짜를 우선 쓴다(@MockBean은 컨텍스트를 나눠 쓰지 않는다).
 */
@TestConfiguration
public class SeatTestConfig {

    @Bean
    @Primary
    public FakeSaleValidationPort fakeSaleValidationPort() {
        return new FakeSaleValidationPort();
    }
}
