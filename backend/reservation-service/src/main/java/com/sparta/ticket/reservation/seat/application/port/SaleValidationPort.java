package com.sparta.ticket.reservation.seat.application.port;

import java.util.List;

/**
 * 공연 서비스의 회차 판매 검증(P21). 공개·예매 기간·판매 중지·좌석 소속·구성 버전을 확인하고, 허용이면 좌석 등급·가격과
 * 결제에 넘길 공연·판매자 ID를 준다. 좌석 점유 여부는 판단하지 않는다.
 * 판매 거절은 예외가 아니라 {@link SaleValidation#allowed()} false와 reasonCode로 돌아온다. 외부 오류로 바꾸는 것은 선점이 한다.
 * 회차가 없으면 404 SCHEDULE_NOT_FOUND, 공연 서비스가 응답하지 않으면 503 SERVICE_UNAVAILABLE 예외다.
 */
public interface SaleValidationPort {

    SaleValidation validate(Long scheduleId, long configVersion, List<Long> venueSeatIds);
}
