package com.sparta.ticket.reservation.coupon.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.reservation.coupon.application.CouponIssueService;
import com.sparta.ticket.reservation.coupon.application.CouponService;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.IssuedCouponResult;
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

/**
 * 쿠폰 API. 관리자의 생성·수정·발급 중단·삭제와 회원의 발급을 받는다.
 * 요청 값 검증(@Valid)과 역할 확인(@RequireRole)만 하고, 처리는 관리자 기능은 CouponService, 발급은 CouponIssueService에 넘긴다.
 * Gateway가 /api/coupons/**를 reservation-service로 보낸다. 명세는 docs/api.md 쿠폰 절이다.
 */
@Tag(name = "쿠폰")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService couponIssueService;

    /** 쿠폰 생성. 201과 만든 쿠폰을 돌려준다. */
    @Operation(summary = "쿠폰 생성")
    @RequireRole(Role.MASTER)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CouponResult> create(@Valid @RequestBody CreateCouponRequest request) {
        return ApiResponse.created("쿠폰이 생성되었습니다.", couponService.create(request.toCommand()));
    }

    /** 쿠폰 부분 수정. 보낸 항목만 바꾸고 200과 바뀐 쿠폰을 돌려준다. */
    @Operation(summary = "쿠폰 수정")
    @RequireRole(Role.MASTER)
    @PatchMapping("/{couponId}")
    public ApiResponse<CouponResult> update(
            @PathVariable Long couponId, @Valid @RequestBody UpdateCouponRequest request) {
        return ApiResponse.ok(couponService.update(couponId, request.toCommand()));
    }

    /** 쿠폰 발급 중단. 발급 종료 시각을 현재로 당기고 200과 ENDED 상태의 쿠폰을 돌려준다. */
    @Operation(summary = "쿠폰 발급 중단")
    @RequireRole(Role.MASTER)
    @PostMapping("/{couponId}/close")
    public ApiResponse<CouponResult> close(@PathVariable Long couponId) {
        return ApiResponse.ok(couponService.close(couponId));
    }

    /** 쿠폰 논리 삭제. 삭제자는 요청한 관리자의 로그인 ID(email)로 기록하고, 204(본문 없음)를 돌려준다. */
    @Operation(summary = "쿠폰 삭제", description = "아무도 받지 않은 쿠폰만 지울 수 있다.")
    @RequireRole(Role.MASTER)
    @DeleteMapping("/{couponId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long couponId, @CurrentUser UserContext user) {
        couponService.delete(couponId, user.username());
    }

    /**
     * 선착순 쿠폰 발급. 201과 발급한 회원 쿠폰을 돌려준다.
     * 받는 회원은 요청 본문이 아니라 Gateway가 넘긴 사용자 헤더(@CurrentUser)로 정한다. 다른 회원 이름으로 받을 수 없다.
     * Note: @RequireRole은 역할이 정확히 같은지만 보므로 MASTER·SELLER도 403이다(docs/api.md 쿠폰 절).
     */
    @Operation(summary = "쿠폰 발급", description = "회원 1인당 1장, 발급 기간 안에서 수량이 남았을 때만 받을 수 있다.")
    @RequireRole(Role.USER)
    @PostMapping("/{couponId}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssuedCouponResult> issue(@PathVariable Long couponId, @CurrentUser UserContext user) {
        return ApiResponse.created("쿠폰이 발급되었습니다.", couponIssueService.issue(couponId, user.userId(), user.username()));
    }
}
