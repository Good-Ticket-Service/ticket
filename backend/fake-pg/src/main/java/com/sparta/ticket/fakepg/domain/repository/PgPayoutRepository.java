package com.sparta.ticket.fakepg.domain.repository;

import com.sparta.ticket.fakepg.domain.model.payout.PgPayout;
import java.util.Optional;

public interface PgPayoutRepository {

    PgPayout saveAndFlush(PgPayout payout);

    Optional<PgPayout> findByIdempotencyKey(String idempotencyKey);

    /** 조회하며 상태를 확정하므로 행을 잠근다. 같은 키 동시 조회가 서로 다른 시각으로 확정하지 않게 한다. */
    Optional<PgPayout> lockByIdempotencyKey(String idempotencyKey);
}
