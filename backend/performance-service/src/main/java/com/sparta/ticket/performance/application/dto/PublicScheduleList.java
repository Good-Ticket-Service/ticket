package com.sparta.ticket.performance.application.dto;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import java.util.List;

/** 판매 상태를 계산하기 전의 공연 운영 상태와 정렬된 회차 목록이다(이슈 #431). */
public record PublicScheduleList(OperationStatus performanceStatus, List<PublicScheduleRow> schedules) {}
