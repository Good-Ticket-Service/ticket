package com.sparta.ticket.reservation.coupon.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.coupon.application.CouponService;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.presentation.dto.CreateCouponRequest;
import com.sparta.ticket.reservation.coupon.presentation.dto.UpdateCouponRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "쿠폰")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "쿠폰 생성")
    @RequireRole(Role.MASTER)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CouponResult> create(@Valid @RequestBody CreateCouponRequest request) {
        return ApiResponse.created("쿠폰이 생성되었습니다.", couponService.create(request.toCommand()));
    }

    @Operation(summary = "쿠폰 수정")
    @RequireRole(Role.MASTER)
    @PatchMapping("/{couponId}")
    public ApiResponse<CouponResult> update(
            @PathVariable Long couponId, @Valid @RequestBody UpdateCouponRequest request) {
        return ApiResponse.ok(couponService.update(couponId, request.toCommand()));
    }

    @Operation(summary = "쿠폰 발급 중단")
    @RequireRole(Role.MASTER)
    @PostMapping("/{couponId}/close")
    public ApiResponse<CouponResult> close(@PathVariable Long couponId) {
        return ApiResponse.ok(couponService.close(couponId));
    }

    @Operation(summary = "쿠폰 삭제", description = "아무도 받지 않은 쿠폰만 지울 수 있다.")
    @RequireRole(Role.MASTER)
    @DeleteMapping("/{couponId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long couponId, @CurrentUser UserContext user) {
        couponService.delete(couponId, user.username());
    }
}
