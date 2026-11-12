package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;

/**
 * 예약이 무엇을 샀는지: 회차와 그 회차의 공연·판매자·시작 시각·공연명. 모두 선점 시점 값이며 예약이 끝날 때까지 바꾸지 않는다.
 * payment는 같은 orderId의 재요청에서 공연·판매자가 바뀌면 거절한다. 시작 시각으로 취소 기한을 판정한다.
 * 공연 장르는 장르별 쿠폰 판단에만 쓰므로 없어도 예약을 만든다(null, #383).
 */
public record SaleTarget(
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        LocalDateTime scheduleStartAt,
        String performanceTitle,
        String genre) {

    /** 장르를 모르는 대상. 장르를 쓰지 않던 기존 호출(테스트 등)을 위해 둔다. */
    public SaleTarget(
            Long scheduleId,
            Long performanceId,
            Long sellerId,
            LocalDateTime scheduleStartAt,
            String performanceTitle) {
        this(scheduleId, performanceId, sellerId, scheduleStartAt, performanceTitle, null);
    }

    /** 장르는 확인하지 않는다. 장르를 모르는 예약은 장르 쿠폰만 쓸 수 없다. */
    boolean isComplete() {
        return scheduleId != null
                && performanceId != null
                && sellerId != null
                && scheduleStartAt != null
                && performanceTitle != null;
    }
}
