package com.sparta.ticket.reservation.booking.application.facade;

import java.time.LocalDateTime;

/**
 * seat가 선점할 때 BookingFacade에 넘기는 회차와 그 회차의 공연·판매자·시작 시각·공연명. 모두 판매 검증 응답에서 꺼낸 값이다.
 * booking은 이 값을 예약에 저장해 두고 다시 조회하지 않는다. payment는 같은 orderId의 재요청에서 공연·판매자가 바뀌면
 * 거절하므로 checkout 때 선점 시점 값을 쓴다(#86). 시작 시각은 취소 기한, 공연명은 목록 표시에 쓴다. 취소가 공연 서비스
 * 가용성에 묶이지 않게 하기 위해서다(#126).
 */
public record HoldTarget(
        Long scheduleId, Long performanceId, Long sellerId, LocalDateTime scheduleStartAt, String performanceTitle) {}
