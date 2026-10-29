package com.sparta.ticket.performance.presentation;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.service.PublicSeatLayoutService;
import com.sparta.ticket.performance.presentation.dto.PublicSeatLayoutResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 좌석 배치 API. 바인딩과 Swagger 문서는 ApiSpec에 둔다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/performances")
public class PublicSeatLayoutApi implements PublicSeatLayoutApiSpec {

    private final PublicSeatLayoutService service;

    /** 공개 공연의 정적 좌석 배치와 공연 공통 가격·판매 제외를 준다. */
    @Override
    @GetMapping("/{performanceId}/seat-layout")
    public ApiResponse<PublicSeatLayoutResponse> getLayout(Long performanceId) {
        return ApiResponse.ok(PublicSeatLayoutResponse.from(service.getLayout(performanceId)));
    }
}
