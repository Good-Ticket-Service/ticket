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
    CHECKOUT_CONFLICT(HttpStatus.CONFLICT, "다른 결제 요청이 먼저 처리되었습니다. 예약 상태를 확인해 주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }
}
