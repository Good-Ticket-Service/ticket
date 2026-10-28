package com.sparta.ticket.reservation.seat.application.dto;

/** 좌석 배치도에 보여 줄 좌석 하나. 다른 사용자의 예약 id·만료 시각과 판매 제외 사유는 담지 않는다. */
public record SelectableSeatResult(
        Long scheduleSeatId,
        Long venueSeatId,
        String section,
        String rowLabel,
        int seatNumber,
        Long venueSeatGradeId,
        String gradeName,
        Integer basePrice,
        boolean selectable) {}
