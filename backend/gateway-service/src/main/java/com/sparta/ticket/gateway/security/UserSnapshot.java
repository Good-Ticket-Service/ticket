package com.sparta.ticket.gateway.security;

import java.util.UUID;

/** 내부 사용자 조회 응답 중 Gateway가 쓰는 값. */
public record UserSnapshot(UUID userId, String username, String role) {}
