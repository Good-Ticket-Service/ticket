package com.sparta.ticket.gateway.security;

/** 내부 사용자 조회 응답 중 Gateway가 쓰는 값. email은 X-Username 헤더로 전달한다. */
public record UserSnapshot(Long userId, String email, String role) {}
