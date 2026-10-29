package com.sparta.ticket.reservation.seat.application.port;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매 검증 결과. 거절이면 reasonCode가 있고 seats는 비어 있다.
 * scheduleStartAt(KST)·performanceTitle은 선점 때 예약에 스냅샷으로 저장할 회차 시작 시각·공연명이다(#126). 취소 기한과 내 예매 목록에 쓴다.
 */
public record SaleValidation(
        boolean allowed,
        String reasonCode,
        Long scheduleId,
        Long performanceId,
        Long sellerId,
        long configVersion,
        long policyVersion,
        LocalDateTime scheduleStartAt,
        String performanceTitle,
        List<SaleValidationSeat> seats) {}
