package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.performance.PublicPerformanceService;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.application.performance.dto.PublicPerformanceSort;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.presentation.dto.PublicPerformanceDetailResponse;
import com.sparta.ticket.performance.presentation.dto.PublicPerformanceListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일반 사용자용 공연 조회 API. 로그인 없이 누구나 호출한다. 명세는 .local/performance/api.md 「공개 조회」다.
 * Note: 페이지 규칙(size 1~100, 기본 20)이 공통 SearchPageableArgumentResolver(10·30·50)와 달라 Pageable 대신 page·size를 직접 받는다.
 */
@Tag(name = "공연 공개 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/performances")
public class PublicPerformanceController {

    private final PublicPerformanceService publicPerformanceService;

    /** 공개 공연 목록. 공개됐고 삭제·취소되지 않은 공연만 돌려준다. */
    @Operation(summary = "공개 공연 목록 조회")
    @GetMapping
    public ApiResponse<PublicPerformanceListResponse> search(
            @RequestParam(required = false) Genre genre,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "LATEST") PublicPerformanceSort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PublicPerformanceSearchCondition condition = new PublicPerformanceSearchCondition(
                genre, blankToNull(region), from, to, blankToNull(keyword), sort, page, size);
        return ApiResponse.ok(PublicPerformanceListResponse.from(publicPerformanceService.search(condition)));
    }

    /** 공개 공연 상세. 취소된 공개 공연도 취소 안내를 위해 돌려준다. 초안·삭제 공연은 404다. */
    @Operation(summary = "공개 공연 상세 조회")
    @GetMapping("/{performanceId}")
    public ApiResponse<PublicPerformanceDetailResponse> getDetail(@PathVariable Long performanceId) {
        return ApiResponse.ok(PublicPerformanceDetailResponse.from(publicPerformanceService.getDetail(performanceId)));
    }

    /** 앞뒤 공백을 지우고, 남는 글자가 없으면 필터를 적용하지 않도록 null로 바꾼다. */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
