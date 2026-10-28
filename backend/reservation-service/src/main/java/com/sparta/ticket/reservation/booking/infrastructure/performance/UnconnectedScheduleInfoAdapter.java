package com.sparta.ticket.reservation.booking.infrastructure.performance;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.port.ScheduleInfoPort;
import org.springframework.stereotype.Component;

/**
 * 회차의 공연·판매자를 얻는 연동 자리. 값을 지어내지 않고 503으로 답한다.
 * payment는 두 값이 없으면 결제 준비를 받지 않으므로, 결제 준비가 여기서 멈춘다.
 */
// TODO(#75): sellerId·performanceId를 얻을 곳이 정해지면 이 클래스를 그 어댑터로 바꾼다.
@Component
public class UnconnectedScheduleInfoAdapter implements ScheduleInfoPort {

    @Override
    public ScheduleInfo find(Long scheduleId) {
        throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "회차의 판매 정보를 아직 조회할 수 없습니다.");
    }
}
