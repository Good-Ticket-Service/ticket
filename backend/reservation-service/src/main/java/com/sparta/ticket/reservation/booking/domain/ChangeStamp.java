package com.sparta.ticket.reservation.booking.domain;

import java.time.LocalDateTime;

/**
 * 조건부 UPDATE가 감사 컬럼에 쓰는 시각과 사용자. 조건부 UPDATE는 JPA 감사를 거치지 않아 쿼리가 직접 채운다.
 * 같은 at을 전이 조건(만료 판정)에도 쓴다.
 */
public record ChangeStamp(LocalDateTime at, String by) {}
