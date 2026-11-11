package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties.LockMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 선점 잠금 방식을 알려 준다. 설정값을 그대로 돌려주는 얇은 빈이다.
 * Note: 통합 테스트는 컨텍스트 하나를 함께 써서 클래스마다 설정을 바꿀 수 없다. 테스트가 이 빈을 바꿀 수 있는 것으로 교체해
 * 같은 컨텍스트에서 두 방식을 모두 확인한다.
 */
@Component
@EnableConfigurationProperties(SeatHoldProperties.class)
public class SeatHoldLockMode {

    private final LockMode lockMode;

    public SeatHoldLockMode(SeatHoldProperties properties) {
        this.lockMode = properties.lockMode();
    }

    public LockMode current() {
        return lockMode;
    }
}
