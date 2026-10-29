package com.sparta.ticket.fakepg.presentation.controller.kakao;

import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.application.service.fault.ResponseHoldException;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.presentation.controller.ResponseTamper;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoErrorResponse;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 카카오 API 요청 하나를 실제 PG처럼 처리하는 순서. 토스 실행기와 같고 멱등 키 단계만 없다(카카오에는 멱등 키가 없다).
 *
 * <ol>
 *   <li>처리 한도를 넘으면 429 (처리 안 함)</li>
 *   <li>지연 → 확률에 따라 처리하지 않은 500</li>
 *   <li>업무 처리</li>
 *   <li>확률에 따라 응답 유실: 처리는 끝났는데 응답을 붙잡았다가 500</li>
 *   <li>확률에 따라 성공 응답 변조: 처리는 정상이고 내보내는 본문만 망가뜨린다</li>
 * </ol>
 *
 * Note: 멱등 키가 없으므로 응답 유실 뒤 같은 요청을 다시 보내면 재현이 아니라 새 처리로 간다. 승인은 "이미 처리된 결제",
 * 취소는 "이미 취소된 결제" 오류가 난다. 그래서 카카오 어댑터는 다시 보내기 전에 조회로 결과를 확인해야 한다.
 */
@Component
@RequiredArgsConstructor
public class KakaoApiExecutor {

    private static final PgProvider PROVIDER = PgProvider.KAKAO;

    private final FaultInjector faults;
    private final ResponseTamper tamper;

    public ResponseEntity<Object> execute(PgOperation operation, Supplier<Object> action) {
        faults.checkRateLimit(PROVIDER, operation);

        ResponseEntity<Object> response;
        try {
            faults.beforeProcessing(PROVIDER, operation);
            response = ResponseEntity.ok(action.get());
        } catch (PgException e) {
            if (!e.getErrorCode().isStored()) {
                throw e;
            }
            response = KakaoErrorResponse.of(e.getErrorCode(), e.getMessage());
        } catch (ResponseHoldException e) {
            return hold(operation);
        }

        if (faults.shouldLoseResponse(PROVIDER, operation)) {
            return hold(operation);
        }
        return tampered(operation, response);
    }

    private ResponseEntity<Object> tampered(PgOperation operation, ResponseEntity<Object> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        return faults.tamper(PROVIDER, operation)
                .<ResponseEntity<Object>>map(
                        mode -> ResponseEntity.ok(tamper.apply(PROVIDER, mode, response.getBody())))
                .orElse(response);
    }

    private ResponseEntity<Object> hold(PgOperation operation) {
        faults.hold(PROVIDER, operation);
        PgErrorCode code = PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING;
        return KakaoErrorResponse.of(code, code.getMessage());
    }
}
