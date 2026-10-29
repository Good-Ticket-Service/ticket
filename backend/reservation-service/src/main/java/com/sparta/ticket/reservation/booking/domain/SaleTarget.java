package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;

/**
 * 예약이 무엇을 샀는지: 회차와 그 회차의 공연·판매자·시작 시각·공연명. 모두 선점 시점 값이며 예약이 끝날 때까지 바꾸지 않는다.
 * payment는 같은 orderId의 재요청에서 공연·판매자가 바뀌면 거절한다. 시작 시각으로 취소 기한을 판정한다.
 */
public record SaleTarget(
        Long scheduleId, Long performanceId, Long sellerId, LocalDateTime scheduleStartAt, String performanceTitle) {

    boolean isComplete() {
        return scheduleId != null
                && performanceId != null
                && sellerId != null
                && scheduleStartAt != null
                && performanceTitle != null;
    }
}
