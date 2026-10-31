package com.sparta.ticket.reservation.seat.domain;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;

/**
 * 좌석 준비가 만들 회차 좌석 한 개의 값. 공연 서비스가 보낸 공연장 좌석의 위치·등급·가격·판매 여부다.
 * 회차·구성 버전·상태는 회차 단위로 정해지므로 여기 두지 않는다.
 * 값 규칙은 {@link ScheduleSeat#create}와 같다(가격은 0 초과, 판매 대상이면 가격 필수, 좌석 번호 1 이상).
 */
public record ScheduleSeatSpec(
        Long venueSeatId,
        Long venueSeatGradeId,
        String gradeName,
        Integer basePrice,
        String section,
        String rowLabel,
        int seatNumber,
        boolean sellable) {

    public ScheduleSeatSpec {
        if (basePrice != null && basePrice <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "좌석 가격은 0원보다 커야 합니다.");
        }
        if (sellable && basePrice == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "판매 대상 좌석에는 가격이 있어야 합니다.");
        }
        if (seatNumber <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "좌석 번호는 1 이상이어야 합니다.");
        }
    }
}
