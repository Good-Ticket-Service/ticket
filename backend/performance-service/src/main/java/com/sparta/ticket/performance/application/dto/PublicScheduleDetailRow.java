package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;

/** 공개 공연에 속한 회차 한 행과 부모 공연의 운영 상태. 회차 단건 조회가 판매 상태를 계산할 때 쓴다. */
public record PublicScheduleDetailRow(PublicScheduleRow schedule, OperationStatus performanceStatus) {}
