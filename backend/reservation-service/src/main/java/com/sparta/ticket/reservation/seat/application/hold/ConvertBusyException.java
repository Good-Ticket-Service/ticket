package com.sparta.ticket.reservation.seat.application.hold;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import java.time.Duration;

/** 좌석 선택 완료가 몰려 지금은 받지 않는다(#449 후속). 응답에 다시 시도할 때까지의 시간을 Retry-After로 싣는다. 선점은 그대로다. */
public class ConvertBusyException extends BusinessException {

    private final Duration retryAfter;

    public ConvertBusyException(Duration retryAfter) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE, "좌석 선택 완료 요청이 몰려 잠시 뒤 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
