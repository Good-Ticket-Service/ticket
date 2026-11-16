package com.sparta.ticket.reservation.seat.application.hold;

import org.springframework.stereotype.Component;

/**
 * 선점 저장 위치 스위치(#449, reservation.seat.hold.mode). 설정값을 그대로 돌려주는 얇은 빈이다.
 * Note: 통합 테스트는 컨텍스트 하나를 함께 써서 클래스마다 설정을 바꿀 수 없다. 테스트가 이 빈을 바꿀 수 있는 것으로 교체해
 * 같은 컨텍스트에서 두 모드를 확인한다(SeatHoldDuration과 같은 방식).
 */
@Component
public class SeatHoldModeSwitch {

    private final SeatHoldMode mode;

    public SeatHoldModeSwitch(SeatHoldProperties properties) {
        this.mode = properties.mode();
    }

    public SeatHoldMode current() {
        return mode;
    }

    /**
     * 결제하기의 좌석 읽기를 예약 트랜잭션 안에서 projection으로 할지(#449 후속 1-C 실험). 운영은 끈다. 측정 서버만 바꾼다.
     */
    public boolean convertInOneTransaction() {
        return false;
    }
}
