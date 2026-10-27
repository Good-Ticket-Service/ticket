package com.sparta.ticket.reservation.coupon.domain;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CouponErrorCode implements ErrorCode {
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    COUPON_NOT_MODIFIABLE(HttpStatus.CONFLICT, "발급이 시작된 쿠폰은 이 항목을 바꿀 수 없습니다."),
    COUPON_QUANTITY_BELOW_ISSUED(HttpStatus.CONFLICT, "총 수량을 이미 발급한 수량보다 적게 바꿀 수 없습니다."),
    COUPON_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 발급이 끝난 쿠폰입니다."),
    COUPON_NOT_DELETABLE(HttpStatus.CONFLICT, "이미 발급된 쿠폰은 삭제할 수 없습니다."),
    COUPON_ISSUE_PERIOD_CLOSED(HttpStatus.BAD_REQUEST, "쿠폰 발급 기간이 아닙니다."),
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "쿠폰이 모두 소진됐습니다."),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
