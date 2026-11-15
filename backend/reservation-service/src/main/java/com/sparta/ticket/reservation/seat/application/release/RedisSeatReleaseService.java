package com.sparta.ticket.reservation.seat.application.release;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.seat.application.hold.RedisSeatHoldMetrics;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore;
import com.sparta.ticket.reservation.seat.application.port.SeatHoldStore.ReleaseAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Redis 선점 해제(DELETE .../holds/{holdId}, #449). 예약으로 바꾸기 전이면 Redis만 지우고 DB는 건드리지 않는다.
 * 이미 예약으로 바꿨으면 그 예약을 DB 해제 경로(SeatReleaseService)로 푼다.
 *
 * 결과: 없음(이미 해제·만료) 204, 남의 선점 403 FORBIDDEN(예약 해제와 같다), 바꾸는 중 409 INVALID_STATUS_TRANSITION.
 * 바꾸는 중에 해제하면 커밋될 예약과 엇갈리므로 막는다. 바꾸기가 끝난 뒤 다시 부르면 예약 해제로 간다.
 */
@Service
@RequiredArgsConstructor
public class RedisSeatReleaseService {

    private final SeatHoldStore store;
    private final SeatReleaseService seatReleaseService;
    private final RedisSeatHoldMetrics metrics;

    public void release(Long userId, Long scheduleId, String holdId) {
        ReleaseAttempt attempt = store.release(scheduleId, holdId, userId);
        metrics.release(RedisSeatHoldMetrics.tag(attempt.outcome()));
        switch (attempt.outcome()) {
            case GONE, RELEASED:
                return;
            case FORBIDDEN:
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            case BUSY:
                throw new BusinessException(
                        CommonErrorCode.INVALID_STATUS_TRANSITION, "결제하기를 처리하고 있습니다. 잠시 뒤 다시 시도해 주세요.");
            case CONVERTED:
                seatReleaseService.release(userId, scheduleId, attempt.reservationId());
                return;
            default:
                throw new IllegalStateException("알 수 없는 해제 결과: " + attempt.outcome());
        }
    }
}
