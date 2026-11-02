package com.sparta.ticket.gateway.queue;

import java.util.Optional;
import reactor.core.publisher.Mono;

/** 회차가 속한 공연 조회. 회차가 없으면 빈 Optional, 조회 자체가 실패하면 에러다. */
public interface ScheduleLookup {

    Mono<Optional<Long>> performanceIdOf(Long scheduleId);
}
