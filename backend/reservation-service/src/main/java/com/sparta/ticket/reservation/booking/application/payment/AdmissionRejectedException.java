package com.sparta.ticket.reservation.booking.application.payment;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import java.time.Duration;

/** 결제 결과가 계속 늦어져 결제 준비를 받지 않는다. 응답에 다시 시도할 때까지의 시간을 Retry-After로 싣는다. */
public class AdmissionRejectedException extends BusinessException {

    private final Duration retryAfter;

    public AdmissionRejectedException(Duration retryAfter) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE, "결제가 몰려 잠시 뒤 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
