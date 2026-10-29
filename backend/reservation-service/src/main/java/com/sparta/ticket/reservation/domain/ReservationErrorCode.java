package com.sparta.ticket.reservation.domain;

import com.sparta.ticket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

/** reservation-service 도메인 에러 코드. seat 모듈과 함께 쓴다. CommonErrorCode에 있는 이름은 다시 두지 않는다. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "좌석 선점 시간이 지났습니다."),
    ORDER_ID_MISMATCH(HttpStatus.CONFLICT, "마지막 결제 준비의 주문 번호가 아닙니다. 결제를 다시 준비해 주세요."),
    CHECKOUT_CONFLICT(HttpStatus.CONFLICT, "다른 결제 요청이 먼저 처리되었습니다. 예약 상태를 확인해 주세요."),
    // Note: 쿠폰을 잡을 수 없을 때 coupon 모듈도 같은 이름을 쓴다. 응답 code는 enum 이름이라 클라이언트가 받는 값이 같다.
    // 여기에도 두는 이유는 정가가 최소 결제 금액 이하인 예약에 쿠폰을 고르면 booking이 직접 거절하기 때문이다.
    COUPON_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "사용할 수 없는 쿠폰입니다."),
    NOT_CANCELLABLE(HttpStatus.CONFLICT, "회차가 시작되어 취소할 수 없습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "이미 다른 예약이 선점했거나 판매된 좌석입니다."),
    SEAT_NOT_SELLABLE(HttpStatus.CONFLICT, "판매 대상이 아닌 좌석입니다."),
    SEAT_CONFIG_VERSION_MISMATCH(HttpStatus.CONFLICT, "좌석 정보가 최신 구성과 다릅니다. 좌석을 다시 조회해 주세요."),
    PURCHASE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "이 회차에 이미 진행 중인 예약이 있습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
