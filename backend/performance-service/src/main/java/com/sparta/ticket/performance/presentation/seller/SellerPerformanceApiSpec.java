package com.sparta.ticket.performance.presentation.seller;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.presentation.seller.dto.GradePricesResponse;
import com.sparta.ticket.performance.presentation.seller.dto.GradePricesUpdateRequest;
import com.sparta.ticket.performance.presentation.seller.dto.PerformanceCreateRequest;
import com.sparta.ticket.performance.presentation.seller.dto.SellerPerformanceResponse;
import com.sparta.ticket.performance.presentation.support.IfMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 판매자 공연 명령의 Swagger 문서와 파라미터 바인딩. 모두 SELLER 전용이다.
 * Note: If-Match는 required=false로 받는다. 그래야 헤더가 없을 때 Spring 대신 IfMatch.parse가 428을 낸다.
 */
@Tag(name = "판매자 공연")
public interface SellerPerformanceApiSpec {

    @Operation(
            summary = "공연 초안 등록",
            description = "201과 Location: /api/seller/performances/{performanceId}를 준다. 판매자는 로그인한 SELLER이며 "
                    + "요청 본문으로 받지 않는다. 없거나 삭제된 공연장은 404 VENUE_NOT_FOUND.")
    ResponseEntity<ApiResponse<SellerPerformanceResponse>> registerDraft(
            @CurrentUser UserContext user, @Valid @RequestBody PerformanceCreateRequest request);

    @Operation(
            summary = "공연별 등급 가격 설정",
            description = "공연의 가격 집합 전체를 요청한 값으로 바꾼다. If-Match에 공연 version을 넣는다(예: \"0\"). "
                    + "판매 대상 좌석이 쓰는 등급의 가격이 빠지면 400 GRADE_PRICE_MISSING, 다른 공연장 등급은 400 "
                    + "VENUE_GRADE_MISMATCH, 공개된 공연은 409 PERFORMANCE_CONFIGURATION_LOCKED. 성공하면 공연 version이 1 오르고 "
                    + "등록된 회차는 좌석을 다시 준비한다(PENDING).")
    ApiResponse<GradePricesResponse> replaceGradePrices(
            @CurrentUser UserContext user,
            @Parameter(description = "공연 ID") @PathVariable @Positive Long performanceId,
            @Parameter(description = "공연 version. 예: \"0\"", required = true)
                    @RequestHeader(value = IfMatch.HEADER, required = false)
                    String ifMatch,
            @Valid @RequestBody GradePricesUpdateRequest request);
}
