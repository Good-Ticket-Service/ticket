package com.sparta.ticket.performance.infrastructure.persistence;

/** 공개 공연 상세의 등급 가격 한 행. JPQL 생성자 표현식이 만들므로 public이다. */
public record PublicPerformanceGradePriceRow(Long venueSeatGradeId, String gradeName, Integer price) {}
