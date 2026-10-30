package com.sparta.ticket.performance.presentation.seller;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.presentation.seller.dto.ScheduleCreateRequest;
import com.sparta.ticket.performance.presentation.seller.dto.SellerScheduleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/** 판매자 회차 명령의 Swagger 문서와 파라미터 바인딩. 모두 SELLER 전용이다. */
@Tag(name = "판매자 회차")
public interface SellerScheduleApiSpec {

    @Operation(
            summary = "공연 회차 등록",
            description = "201과 Location: /api/seller/schedules/{scheduleId}를 준다. If-Match는 받지 않는다. "
                    + "회차는 좌석 준비 PENDING으로 만들어지고, 등록이 커밋된 뒤 서버가 좌석 서비스에 좌석 준비를 요청한다. "
                    + "응답의 seatSetupStatus는 등록 시점 값(PENDING)이며 준비 결과는 판매자 회차 조회로 확인한다. "
                    + "예매 오픈 < 예매 마감 ≤ 공연 시작이 아니면 400 INVALID_TIME_RANGE, 같은 시작 시각의 회차가 있으면 "
                    + "409 DUPLICATE_SCHEDULE, 취소된 공연은 409 INVALID_STATUS_TRANSITION.")
    ResponseEntity<ApiResponse<SellerScheduleResponse>> register(
            @CurrentUser UserContext user,
            @Parameter(description = "공연 ID") @PathVariable Long performanceId,
            @Valid @RequestBody ScheduleCreateRequest request);
}
