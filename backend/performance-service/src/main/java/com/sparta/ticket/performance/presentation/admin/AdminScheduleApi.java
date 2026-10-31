package com.sparta.ticket.performance.presentation.admin;

import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.service.AdminScheduleService;
import com.sparta.ticket.performance.presentation.admin.dto.SalesSuspensionRequest;
import com.sparta.ticket.performance.presentation.admin.dto.SalesSuspensionResponse;
import com.sparta.ticket.performance.presentation.support.IfMatch;
import com.sparta.ticket.performance.presentation.support.RequestText;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 회차 명령 API. 바인딩과 Swagger 문서는 ApiSpec에 둔다. */
@RestController
@RequiredArgsConstructor
@RequireRole(Role.MASTER)
@RequestMapping("/api/admin/schedules")
public class AdminScheduleApi implements AdminScheduleApiSpec {

    private static final String SUCCESS = "SUCCESS";

    private final AdminScheduleService service;

    @Override
    @PutMapping("/{scheduleId}/sales-suspension")
    public ApiResponse<SalesSuspensionResponse> changeSalesSuspension(
            Long scheduleId, String ifMatch, SalesSuspensionRequest request) {
        long expectedVersion = IfMatch.parse(ifMatch);
        return ApiResponse.ok(
                SUCCESS,
                SalesSuspensionResponse.from(service.changeSalesSuspension(
                        scheduleId, expectedVersion, request.suspended(), RequestText.stripToNull(request.reason()))));
    }
}
