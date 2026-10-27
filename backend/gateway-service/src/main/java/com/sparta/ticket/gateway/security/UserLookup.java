package com.sparta.ticket.gateway.security;

import java.util.Optional;
import reactor.core.publisher.Mono;

/** 저장된 사용자 정보 조회. 탈퇴했거나 없으면 빈 Optional. */
public interface UserLookup {

    Mono<Optional<UserSnapshot>> find(Long userId);
}
