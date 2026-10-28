package com.sparta.ticket.performance.application.performance.dto;

/** 공개 응답에 넣는 공연장 정보. */
public record VenueSummary(Long id, String name, String address, String region) {}
