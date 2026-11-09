package com.sparta.ticket.waitingroom.domain;

import java.time.Instant;

/**
 * 입장해 있는 상태. 입장 스케줄러가 줄 앞에서 꺼내 입장 목록에 넣은 회원이다.
 *
 * @param expiresAt 입장 만료 시각. 입장 시각 + 입장 유효 시간이며 순번을 조회해도 늘어나지 않는다
 */
public record QueueAdmission(Instant expiresAt) implements QueueState {}
