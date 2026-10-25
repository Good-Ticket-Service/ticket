package com.sparta.ticket.fakepg.presentation.controller.toss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.application.service.fault.ResponseHoldException;
import com.sparta.ticket.fakepg.application.service.idempotency.IdempotencyService;
import com.sparta.ticket.fakepg.application.service.idempotency.IdempotencyService.StoredResponse;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossErrorResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 토스 API 요청 하나를 실제 PG처럼 처리하는 순서를 한 곳에 모았다.
 *
 * <ol>
 *   <li>처리 한도를 넘으면 429 (처리 안 함, 저장 안 함)</li>
 *   <li>멱등 키가 이미 처리됐으면 저장한 응답을 그대로 돌려준다</li>
 *   <li>지연 → 확률에 따라 처리하지 않은 500</li>
 *   <li>업무 처리. 성공과 업무 거절은 멱등 키에 저장한다</li>
 *   <li>확률에 따라 응답 유실: 처리·저장은 끝났는데 응답을 붙잡았다가 500</li>
 * </ol>
 *
 * Note: 응답 유실은 "PG는 처리했는데 우리 서버는 결과를 모르는" 상황이다. 응답을 클라이언트 읽기 타임아웃보다 오래 붙잡아,
 * 클라이언트가 결과 불명(Unknown)으로 처리하게 만든다. 같은 멱등 키로 다시 보내면 저장된 성공 응답이 나온다.
 */
@Component
@RequiredArgsConstructor
public class TossApiExecutor {

    private static final PgProvider PROVIDER = PgProvider.TOSS;

    private final FaultInjector faults;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public ResponseEntity<Object> execute(
            PgOperation operation, String idempotencyKey, Object requestForFingerprint, Supplier<Object> action) {
        faults.checkRateLimit(PROVIDER, operation);
        boolean useKey = idempotencyKey != null && !idempotencyKey.isBlank();
        if (useKey) {
            Optional<StoredResponse> stored = idempotency.begin(
                    PROVIDER, idempotencyKey, operation, fingerprint(operation, requestForFingerprint));
            if (stored.isPresent()) {
                return ResponseEntity.status(stored.get().statusCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(stored.get().body());
            }
        }

        ResponseEntity<Object> response;
        try {
            faults.beforeProcessing(PROVIDER, operation);
            response = ResponseEntity.ok(action.get());
        } catch (PgException e) {
            if (!e.getErrorCode().isStored()) {
                abandon(useKey, idempotencyKey);
                throw e;
            }
            response = error(e);
        } catch (ResponseHoldException e) {
            abandon(useKey, idempotencyKey);
            return hold(operation);
        } catch (RuntimeException e) {
            abandon(useKey, idempotencyKey);
            throw e;
        }

        if (useKey) {
            idempotency.complete(
                    PROVIDER, idempotencyKey, response.getStatusCode().value(), toJson(response.getBody()));
        }
        if (faults.shouldLoseResponse(PROVIDER, operation)) {
            return hold(operation);
        }
        return response;
    }

    private ResponseEntity<Object> hold(PgOperation operation) {
        faults.hold(PROVIDER, operation);
        return error(new PgException(PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING));
    }

    private void abandon(boolean useKey, String idempotencyKey) {
        if (useKey) {
            idempotency.abandon(PROVIDER, idempotencyKey);
        }
    }

    private static ResponseEntity<Object> error(PgException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(new TossErrorResponse(e.getErrorCode().name(), e.getMessage()));
    }

    private String fingerprint(PgOperation operation, Object request) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((operation + ":" + toJson(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답을 JSON으로 바꾸지 못했다", e);
        }
    }
}
