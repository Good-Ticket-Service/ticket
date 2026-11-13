package com.sparta.ticket.reservation.seat.application.hold;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 선점 유지 시간을 알려 준다(#248). 설정값을 그대로 돌려주는 얇은 빈이다.
 * Note: 통합 테스트는 컨텍스트 하나를 함께 써서 클래스마다 설정을 바꿀 수 없다. 테스트가 이 빈을 바꿀 수 있는 것으로 교체해
 * 같은 컨텍스트에서 바꾼 유지 시간을 확인한다.
 */
@Component
@EnableConfigurationProperties(SeatHoldProperties.class)
public class SeatHoldDuration {

    private final Duration duration;

    public SeatHoldDuration(SeatHoldProperties properties) {
        this.duration = properties.duration();
    }

    public Duration current() {
        return duration;
    }
}
