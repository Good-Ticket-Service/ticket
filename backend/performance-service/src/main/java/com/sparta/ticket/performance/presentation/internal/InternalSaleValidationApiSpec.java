package com.sparta.ticket.performance.presentation.internal;

import com.sparta.ticket.performance.presentation.internal.dto.SaleValidationRequest;
import com.sparta.ticket.performance.presentation.internal.dto.SaleValidationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 판매 검증 내부 API의 계약. Swagger 문서와 파라미터 바인딩·검증을 둔다. 경로와 HTTP 메서드는 구현 클래스 {@link InternalSaleValidationApi}에 둔다.
 * 명세는 .local/performance/api.md 「POST /internal/schedules/{scheduleId}/sale-validations」다.
 */
@Tag(name = "내부 판매 검증")
public interface InternalSaleValidationApiSpec {

    @Operation(
            summary = "회차 판매 검증",
            description = "예매 서비스가 좌석을 선점하기 전에 부른다. 판매 정책과 가격만 확인하고 좌석 점유 여부는 보지 않는다. "
                    + "업무 거절은 HTTP 200, allowed=false, reasonCode이며 seats는 빈 배열이다. "
                    + "회차·공연·공연장이 없거나 삭제됐으면 404 SCHEDULE_NOT_FOUND, "
                    + "configVersion 1 미만이나 venueSeatIds가 비었거나 중복이면 400 VALIDATION_ERROR다. "
                    + "ApiResponse로 감싸지 않는다.")
    SaleValidationResponse validate(
            @Parameter(description = "회차 ID") @PathVariable Long scheduleId,
            @Valid @RequestBody SaleValidationRequest request);
}
