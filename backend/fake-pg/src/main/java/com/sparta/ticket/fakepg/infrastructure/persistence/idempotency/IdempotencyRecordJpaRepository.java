package com.sparta.ticket.fakepg.infrastructure.persistence.idempotency;

import com.sparta.ticket.fakepg.domain.model.idempotency.IdempotencyRecord;
import com.sparta.ticket.fakepg.domain.repository.IdempotencyRecordRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyRecordJpaRepository
        extends JpaRepository<IdempotencyRecord, Long>, IdempotencyRecordRepository {

    @Override
    @Transactional
    @Modifying
    @Query("DELETE FROM IdempotencyRecord")
    void deleteAllRecords();
}
