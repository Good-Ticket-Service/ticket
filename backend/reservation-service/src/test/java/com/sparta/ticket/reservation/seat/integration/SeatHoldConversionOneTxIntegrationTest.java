package com.sparta.ticket.reservation.seat.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * 1-C 실험 경로(#449 후속, 좌석 읽기를 예약 트랜잭션 안에서 projection으로)로 결제하기 통합 테스트를 모두 다시 돌린다.
 * 잠금 순서·만료 선점 정리·같은 회원 재요청·동시 결제하기 판정이 기존 경로와 같아야 한다.
 */
@DisplayName("결제하기 1-C 경로(좌석 읽기를 트랜잭션 안에서)")
class SeatHoldConversionOneTxIntegrationTest extends SeatHoldConversionIntegrationTest {

    @BeforeEach
    void useOneTransaction() {
        modeSwitch.setConvertInOneTransaction(true);
    }
}
