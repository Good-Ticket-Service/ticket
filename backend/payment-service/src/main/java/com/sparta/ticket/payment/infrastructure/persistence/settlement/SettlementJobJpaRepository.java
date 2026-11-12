package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SettlementJob;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementJobRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementJobJpaRepository extends JpaRepository<SettlementJob, Long>, SettlementJobRepository {

    @Override
    @Query(
            value =
                    "SELECT * FROM p_settlement_jobs WHERE status = 'PENDING' AND transport = 'DB' AND next_attempt_at <= :now"
                            + " AND deleted_at IS NULL AND id NOT IN (:excludedIds)"
                            + " ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<SettlementJob> lockNextDue(
            @Param("now") LocalDateTime now, @Param("excludedIds") Collection<Long> excludedIds);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM SettlementJob j WHERE j.id = :id")
    Optional<SettlementJob> lockById(@Param("id") Long id);
}
