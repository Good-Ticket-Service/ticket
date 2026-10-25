package com.sparta.ticket.fakepg.domain.repository;

import com.sparta.ticket.fakepg.domain.model.idempotency.IdempotencyRecord;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import java.util.Optional;

public interface IdempotencyRecordRepository {

    IdempotencyRecord saveAndFlush(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByProviderAndIdempotencyKey(PgProvider provider, String idempotencyKey);

    void delete(IdempotencyRecord record);

    void deleteAllRecords();
}
