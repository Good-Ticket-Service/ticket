package com.sparta.ticket.gateway.queue;

/**
 * 서명과 만료를 확인한 입장 토큰의 내용.
 *
 * @param userId 입장한 회원(sub)
 * @param performanceId 입장한 공연
 */
public record EntryToken(long userId, long performanceId) {}
