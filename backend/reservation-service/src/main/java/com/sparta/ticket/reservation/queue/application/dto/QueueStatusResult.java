package com.sparta.ticket.reservation.queue.application.dto;

import com.sparta.ticket.reservation.queue.domain.QueueStatus;

/**
 * 순번 조회 결과. 진입 결과에 내 대기 상태를 더한 모양이다.
 *
 * @param status 내 대기 상태
 * @param position 1부터 세는 내 순번
 * @param aheadCount 내 앞에 선 인원
 * @param estimatedWaitSeconds 예상 대기 시간(초). 앞 인원 ÷ 초당 입장 인원을 올림한 값이다
 */
public record QueueStatusResult(QueueStatus status, long position, long aheadCount, long estimatedWaitSeconds) {}
