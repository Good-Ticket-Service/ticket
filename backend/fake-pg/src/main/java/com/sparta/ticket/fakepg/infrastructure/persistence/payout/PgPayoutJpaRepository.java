package com.sparta.ticket.fakepg.infrastructure.persistence.payout;

import com.sparta.ticket.fakepg.domain.model.payout.PgPayout;
import com.sparta.ticket.fakepg.domain.repository.PgPayoutRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgPayoutJpaRepository extends JpaRepository<PgPayout, Long>, PgPayoutRepository {

    @Override
    Optional<PgPayout> findByIdempotencyKey(String idempotencyKey);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PgPayout p WHERE p.idempotencyKey = :key")
    Optional<PgPayout> lockByIdempotencyKey(@Param("key") String idempotencyKey);
}
