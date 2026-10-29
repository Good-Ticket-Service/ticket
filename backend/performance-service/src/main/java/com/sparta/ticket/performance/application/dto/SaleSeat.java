package com.sparta.ticket.performance.application.dto;

/**
 * 판매 검증에서 읽은 공연장 좌석 하나. 등급은 공연장 고정 등급, 가격은 공연별 등급 가격이다.
 *
 * @param sellable 공연 공통 판매 제외 행이 없으면 true
 * @param basePrice 공연에 그 등급 가격이 없으면 null
 */
public record SaleSeat(
        Long venueSeatId, Long venueSeatGradeId, String gradeName, boolean sellable, Integer basePrice) {}
