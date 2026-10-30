package com.sparta.ticket.performance.application.dto;

/** 등급별 가격 설정의 한 건. 가격 범위 검증은 요청 DTO에서 끝난 값이다. */
public record NewGradePrice(Long venueSeatGradeId, int price) {}
