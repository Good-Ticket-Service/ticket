package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import com.sparta.ticket.reservation.booking.application.port.SeatPort;
import java.util.List;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석·쿠폰과 예약을 함께 바꾸는 전이를 한 순서로 실행한다. 좌석을 먼저 잠그고(seat의 선점과 같은 순서),
 * 조건부 UPDATE가 1행일 때만 좌석과 쿠폰에 반영한다. 부르는 쪽의 트랜잭션 안에서만 쓴다.
 * 좌석을 바꾸지 않는 전이(결제 시작, 선점으로 되돌리기)는 이 클래스를 거치지 않고 저장소를 바로 부른다.
 */
@Component
@RequiredArgsConstructor
public class ReservationTransitionExecutor {

    private final SeatPort seatPort;
    private final CouponPort couponPort;

    /**
     * 확정하고 좌석을 팔고 쿠폰을 사용 처리한다. 잠근 좌석이 모두 이 예약의 HOLD일 때만 예약을 바꾼다.
     * 아니면 예약도 좌석도 쿠폰도 바꾸지 않고 false다.
     * 조건부 UPDATE가 0행이어도 좌석을 팔지 않고 false다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmAndSell(Long reservationId, List<Long> seatIds, IntSupplier conditionalUpdate) {
        if (!seatPort.lockSeats(seatIds, reservationId)) {
            // Note: 좌석이 이 예약의 HOLD가 아니면 확정할 수 없으므로 예약도 좌석도 바꾸지 않는다. 같은 성공이 두 번 와서
            // 앞선 반영이 이미 SOLD로 바꾼 경우도 여기로 오므로, 문제인지는 예약을 다시 읽은 호출한 쪽이 가린다.
            return false;
        }
        if (conditionalUpdate.getAsInt() == 0) {
            return false;
        }
        seatPort.sell(seatIds, reservationId);
        couponPort.use(reservationId);
        return true;
    }

    /**
     * 만료하고 좌석과 쿠폰을 돌려준다. 조건부 UPDATE가 0행이면 아무것도 돌려주지 않고 false다.
     * 쿠폰은 잡았는지 따지지 않고 돌려준다. 잡힌 쿠폰이 없어도 성공하므로 예약을 다시 읽을 필요가 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireAndRelease(Long reservationId, List<Long> seatIds, IntSupplier conditionalUpdate) {
        seatPort.lockSeats(seatIds, reservationId);
        if (conditionalUpdate.getAsInt() == 0) {
            return false;
        }
        seatPort.release(seatIds, reservationId);
        couponPort.release(reservationId);
        return true;
    }
}
