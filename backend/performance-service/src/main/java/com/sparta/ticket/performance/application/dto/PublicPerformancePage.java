package com.sparta.ticket.performance.application.dto;

import java.util.List;

/** 공개 공연 목록 한 페이지. 공연 명세의 목록 형식(items, page, size, totalElements)을 따른다. */
public record PublicPerformancePage(List<PublicPerformanceSummary> items, int page, int size, long totalElements) {}
