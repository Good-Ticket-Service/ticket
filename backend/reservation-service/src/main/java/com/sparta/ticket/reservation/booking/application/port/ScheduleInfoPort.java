package com.sparta.ticket.reservation.booking.application.port;

/** 결제 준비에 필요한 회차의 판매 정보. 예약에는 회차 ID만 있어서 공연과 판매자는 따로 얻는다. */
public interface ScheduleInfoPort {

    /** 회차가 속한 공연과 그 판매자를 돌려준다. 얻지 못하면 예외를 던진다. */
    ScheduleInfo find(Long scheduleId);

    record ScheduleInfo(Long performanceId, Long sellerId) {}
}
