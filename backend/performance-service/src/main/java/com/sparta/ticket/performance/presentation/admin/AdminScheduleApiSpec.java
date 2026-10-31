package com.sparta.ticket.performance.presentation.admin;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.presentation.admin.dto.SalesSuspensionRequest;
import com.sparta.ticket.performance.presentation.admin.dto.SalesSuspensionResponse;
import com.sparta.ticket.performance.presentation.support.IfMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** 관리자 회차 명령의 Swagger 문서와 파라미터 바인딩. 모두 MASTER 전용이다. */
@Tag(name = "관리자 회차")
public interface AdminScheduleApiSpec {

    @Operation(
            summary = "회차 판매 중지·재개",
            description = "If-Match에 회차 version을 넣는다(예: \"0\"). 중지(suspended=true)는 reason 1~500자 필수, "
                    + "재개(false)는 reason을 보내지 않는다. 신규 선점만 막고 이미 잡힌 HOLD와 진행 중인 결제는 그대로다. "
                    + "재개해도 오픈 전·마감 후·좌석 미준비·취소 조건은 그대로 적용된다. 결과가 지금과 같으면 바꾸지 않고 version도 "
                    + "그대로다. 취소된 회차(또는 공연)는 409 INVALID_STATUS_TRANSITION. 바뀌면 회차 version이 1 오른다.")
    ApiResponse<SalesSuspensionResponse> changeSalesSuspension(
            @Parameter(description = "회차 ID") @PathVariable @Positive Long scheduleId,
            @Parameter(description = "회차 version. 예: \"0\"", required = true)
                    @RequestHeader(value = IfMatch.HEADER, required = false)
                    String ifMatch,
            @Valid @RequestBody SalesSuspensionRequest request);
}
