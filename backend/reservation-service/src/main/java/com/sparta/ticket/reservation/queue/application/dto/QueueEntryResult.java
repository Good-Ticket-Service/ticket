package com.sparta.ticket.reservation.queue.application.dto;

/**
 * 대기열 진입 결과.
 *
 * @param position 1부터 세는 내 순번
 * @param aheadCount 내 앞에 선 인원
 * @param estimatedWaitSeconds 예상 대기 시간(초). 앞 인원 ÷ 초당 입장 인원을 올림한 값이다
 */
public record QueueEntryResult(long position, long aheadCount, long estimatedWaitSeconds) {}
