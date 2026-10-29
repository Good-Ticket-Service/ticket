package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.PublicPerformanceService;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSearchCondition;
import com.sparta.ticket.performance.application.dto.PublicPerformanceSort;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.presentation.dto.PublicPerformanceDetailResponse;
import com.sparta.ticket.performance.presentation.dto.PublicPerformanceListResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일반 사용자용 공연 조회 API. 로그인 없이 누구나 호출한다. 경로·HTTP 메서드만 여기 두고, Swagger 문서와 파라미터 바인딩·검증은 {@link PublicPerformanceApiSpec}에 있다.
 * Note: 페이지 규칙(size 1~100, 기본 20)이 공통 SearchPageableArgumentResolver(10·30·50)와 달라 Pageable 대신 page·size를 직접 받는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/performances")
public class PublicPerformanceApi implements PublicPerformanceApiSpec {

    private final PublicPerformanceService publicPerformanceService;

    /** 공개 공연 목록. 공개됐고 삭제·취소되지 않은 공연만 돌려준다. */
    @Override
    @GetMapping
    public ApiResponse<PublicPerformanceListResponse> search(
            Genre genre,
            String region,
            LocalDate from,
            LocalDate to,
            String keyword,
            PublicPerformanceSort sort,
            int page,
            int size) {
        PublicPerformanceSearchCondition condition = new PublicPerformanceSearchCondition(
                genre, blankToNull(region), from, to, blankToNull(keyword), sort, page, size);
        return ApiResponse.ok(PublicPerformanceListResponse.from(publicPerformanceService.search(condition)));
    }

    /** 공개 공연 상세. 취소된 공개 공연도 취소 안내를 위해 돌려준다. 초안·삭제 공연은 404다. */
    @Override
    @GetMapping("/{performanceId}")
    public ApiResponse<PublicPerformanceDetailResponse> getDetail(Long performanceId) {
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
