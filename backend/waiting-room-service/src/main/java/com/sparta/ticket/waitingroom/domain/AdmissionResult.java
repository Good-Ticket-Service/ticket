package com.sparta.ticket.waitingroom.domain;

/**
 * room 하나의 입장 처리 결과.
 *
 * @param admitted 이번에 입장시킨 인원
 * @param expired 입장 유효 시간이 지나 입장 목록에서 빠진 인원
 * @param left 조회가 끊겨 줄에서 빠진 인원
 * @param waiting 처리 뒤 줄에 남은 인원
 * @param active 처리 뒤 입장해 있는 인원
 */
public record AdmissionResult(long admitted, long expired, long left, long waiting, long active) {}
