package com.sparta.ticket.fakepg.application.service.idempotency;

import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.idempotency.IdempotencyRecord;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 토스 Idempotency-Key 동작을 흉내 낸다. 같은 키의 요청은 처음 업무 응답(성공·거절)을 그대로 재현한다.
 *
 * Note: 처리하기 전에 키를 PROCESSING으로 먼저 넣는다. 같은 키가 동시에 두 번 오면 유니크 제약 때문에 하나만 처리되고,
 * 나머지는 409를 받는다. 처리 전에 막힌 응답(5xx, 429)은 키를 지워 다음 요청이 다시 처리되게 한다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final Clock clock;

    /** 처음 보는 키면 빈 값, 이미 처리된 키면 저장된 응답을 돌려준다. */
    public Optional<StoredResponse> begin(
            PgProvider provider, String idempotencyKey, PgOperation operation, String fingerprint) {
        Optional<IdempotencyRecord> existing = repository.findByProviderAndIdempotencyKey(provider, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, fingerprint);
        }
        try {
            repository.saveAndFlush(
                    IdempotencyRecord.processing(provider, idempotencyKey, operation, fingerprint, now()));
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            IdempotencyRecord winner = repository
                    .findByProviderAndIdempotencyKey(provider, idempotencyKey)
                    .orElseThrow(() -> e);
            return replay(winner, operation, fingerprint);
        }
    }

    public void complete(PgProvider provider, String idempotencyKey, int statusCode, String responseBody) {
        repository.findByProviderAndIdempotencyKey(provider, idempotencyKey).ifPresent(record -> {
            record.complete(statusCode, responseBody, now());
            repository.saveAndFlush(record);
        });
    }

    public void abandon(PgProvider provider, String idempotencyKey) {
        repository.findByProviderAndIdempotencyKey(provider, idempotencyKey).ifPresent(repository::delete);
    }

    private Optional<StoredResponse> replay(IdempotencyRecord record, PgOperation operation, String fingerprint) {
        if (!record.isSameRequest(operation, fingerprint)) {
            throw new PgException(PgErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        if (!record.isCompleted()) {
            throw new PgException(PgErrorCode.IDEMPOTENT_REQUEST_PROCESSING);
        }
        return Optional.of(new StoredResponse(record.getStatusCode(), record.getResponseBody()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record StoredResponse(int statusCode, String body) {}
}
