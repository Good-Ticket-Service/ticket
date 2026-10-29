package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.presentation.dto.PublicSeatLayoutResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;

/** 공개 좌석 배치의 Swagger 문서와 파라미터 바인딩. */
@Tag(name = "좌석 배치 공개 조회")
public interface PublicSeatLayoutApiSpec {

    @Operation(
            summary = "공개 좌석 배치 조회",
            description = "정적 배치·공연별 가격·공통 판매 제외를 준다. 실시간 점유 상태는 포함하지 않는다. "
                    + "취소된 공개 공연도 조회하며 초안·삭제·미존재 공연은 404 PERFORMANCE_NOT_FOUND다.")
    ApiResponse<PublicSeatLayoutResponse> getLayout(@Parameter(description = "공연 ID") @PathVariable Long performanceId);
}
