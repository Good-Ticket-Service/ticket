package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceSort;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.presentation.dto.PublicPerformanceDetailResponse;
import com.sparta.ticket.performance.presentation.dto.PublicPerformanceListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 공개 공연 조회 API의 계약. Swagger 문서와 파라미터 바인딩·검증을 둔다. 경로와 HTTP 메서드는 구현 클래스 {@link PublicPerformanceApi}에 둔다.
 * 명세는 .local/performance/api.md 「공개 조회」다.
 * Note: 파라미터 어노테이션은 이 인터페이스에만 둔다. Spring은 인터페이스의 @RequestParam·@PathVariable을 읽고,
 * Bean Validation은 구현 메서드가 파라미터 제약을 새로 선언하는 것을 막는다(HV000151).
 */
@Tag(name = "공연 공개 조회")
public interface PublicPerformanceApiSpec {

    @Operation(
            summary = "공개 공연 목록 조회",
            description = "로그인 없이 호출한다. 공개됐고 삭제·취소되지 않은 공연만 준다. "
                    + "from이 to보다 늦으면 400 INVALID_TIME_RANGE, 값 형식·범위 오류는 400 VALIDATION_ERROR다. "
                    + "알 수 없는 쿼리 파라미터는 무시한다.")
    ApiResponse<PublicPerformanceListResponse> search(
            @Parameter(description = "장르") @RequestParam(required = false) Genre genre,
            @Parameter(description = "공연장 지역. 일치 검색", example = "서울") @RequestParam(required = false) String region,
            @Parameter(description = "이 날짜(KST)부터 시작하는 활성 회차가 있는 공연", example = "2026-12-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "이 날짜(KST)까지 시작하는 활성 회차가 있는 공연", example = "2026-12-31")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "공연명 부분 일치. 대소문자 무시") @RequestParam(required = false) String keyword,
            @Parameter(description = "LATEST: 최근 공개 순, BOOKING_OPEN_ASC: 다음 예매 오픈이 이른 순")
                    @RequestParam(defaultValue = "LATEST")
                    PublicPerformanceSort sort,
            @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 1~100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size);

    @Operation(
            summary = "공개 공연 상세 조회",
            description = "로그인 없이 호출한다. 취소된 공개 공연도 취소 안내를 위해 준다. 초안·삭제 공연과 없는 공연은 404 PERFORMANCE_NOT_FOUND다.")
    ApiResponse<PublicPerformanceDetailResponse> getDetail(
            @Parameter(description = "공연 ID") @PathVariable Long performanceId);
}
