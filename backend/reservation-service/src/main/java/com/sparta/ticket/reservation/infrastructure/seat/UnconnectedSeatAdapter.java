package com.sparta.ticket.reservation.infrastructure.seat;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.application.port.SeatPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * seat 모듈의 좌석 연산(S3)이 오기 전의 자리. 좌석 상태를 흉내 내지 않고 503으로 답한다.
 * seat 담당의 SeatFacade가 생기면 그것을 부르는 어댑터로 바꾸고 이 클래스를 지운다.
 */
@Component
public class UnconnectedSeatAdapter implements SeatPort {

    @Override
    public void lockSeats(List<UUID> seatIds) {
        throw notConnected();
    }

    @Override
    public void sell(List<UUID> seatIds) {
        throw notConnected();
    }

    @Override
    public void release(List<UUID> seatIds) {
        throw notConnected();
    }

    @Override
    public long priceSnapshot(List<UUID> seatIds) {
        throw notConnected();
    }

    private static BusinessException notConnected() {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "좌석 연동이 아직 준비되지 않았습니다.");
    }
}
