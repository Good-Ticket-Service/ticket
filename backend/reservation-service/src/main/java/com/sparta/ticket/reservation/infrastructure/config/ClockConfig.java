package com.sparta.ticket.reservation.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * reservation-service의 모든 모듈(seat, booking)이 함께 쓰는 Clock. 선점 만료 시각 계산과 만료·결제 시작·결과 확인의
 * 시간 판정은 모두 이 Clock으로 구한 값을 조건부 UPDATE의 :now 파라미터로 넘긴다. DB의 now()는 쓰지 않는다.
 * 테스트는 고정할 수 있는 Clock으로 바꾼다.
 */
@Configuration
public class ClockConfig {

    /** 저장하는 시각은 KST TIMESTAMP다. 컨테이너 TZ와 상관없이 같은 기준으로 계산하려고 존을 고정한다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
