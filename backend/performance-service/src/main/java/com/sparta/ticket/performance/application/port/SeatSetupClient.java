package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import com.sparta.ticket.performance.application.dto.SeatSetupOutcome;

/**
 * 좌석 서비스에 회차 좌석 준비를 요청하는 포트. MVP 구현은 Feign이고, 좌석 쪽 엔드포인트가 없는 동안은 설정으로 임시 구현체를 쓴다.
 * Note: 구현체는 예외를 던지지 않고 결과를 SeatSetupOutcome으로 돌려준다. 트랜잭션 밖에서 불린다.
 */
public interface SeatSetupClient {

    SeatSetupOutcome request(SeatSetupCommand command);
}
