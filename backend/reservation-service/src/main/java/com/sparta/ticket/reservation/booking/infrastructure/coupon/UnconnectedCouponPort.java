package com.sparta.ticket.reservation.booking.infrastructure.coupon;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.booking.application.port.CouponPort;
import org.springframework.stereotype.Component;

/**
 * coupon 모듈의 CouponUsage와 연결하지 않은 CouponPort. 쿠폰을 잡지 못하므로 쿠폰을 고른 결제 준비는 503으로 거절한다.
 * 쿠폰을 조용히 무시하면 사용자는 할인을 받은 줄 알고 정가를 결제한다.
 * 잡은 쿠폰이 없으니 사용·반환은 할 일이 없다.
 */
@Component
public class UnconnectedCouponPort implements CouponPort {

    @Override
    public long reserve(Long userCouponId, Long userId, Long reservationId) {
        throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "쿠폰 적용은 아직 지원하지 않습니다.");
    }

    @Override
    public void use(Long reservationId) {}

    @Override
    public void release(Long reservationId) {}
}
