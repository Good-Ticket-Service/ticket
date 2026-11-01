package com.sparta.ticket.reservation.queue.domain;

/**
 * 공연 하나의 입장 처리 결과.
 *
 * @param admitted 이번에 입장시킨 인원
 * @param expired 입장 유효 시간이 지나 입장 목록에서 빠진 인원
 */
public record AdmissionResult(long admitted, long expired) {}
