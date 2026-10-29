package com.sparta.ticket.reservation.coupon.presentation;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.reservation.coupon.application.CouponIssueService;
import com.sparta.ticket.reservation.coupon.application.CouponQueryService;
import com.sparta.ticket.reservation.coupon.application.CouponService;
import com.sparta.ticket.reservation.coupon.application.dto.CouponDetailResult;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.IssuedCouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.UserCouponResult;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import com.sparta.ticket.reservation.coupon.presentation.dto.CreateCouponRequest;
import com.sparta.ticket.reservation.coupon.presentation.dto.UpdateCouponRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 API. 관리자의 생성·수정·발급 중단·삭제, 회원의 발급, 로그인 사용자의 조회를 받는다.
 * 요청 값 검증(@Valid)과 역할 확인(@RequireRole)만 하고, 처리는 관리자 기능은 CouponService, 발급은 CouponIssueService,
 * 조회는 CouponQueryService에 넘긴다.
 * Gateway가 /api/coupons/**를 reservation-service로 보낸다. 명세는 docs/api.md 쿠폰 절이다.
 */
@Tag(name = "쿠폰")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService couponIssueService;
    private final CouponQueryService couponQueryService;

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

    /**
     * 발급 상태별 쿠폰 목록. 200과 페이지(PageResponse)를 돌려준다.
     * 관리자가 만든 쿠폰 종류(p_coupons)를 보여 주는 "쿠폰 받기" 화면용이다. 내가 받은 쿠폰 한 장 한 장은 /me가 보여 준다.
     * issueStatus는 쿠폰의 발급 기간이 지금 어디인지(시작 전·발급 중·종료)로 거르는 필터라 누가 보든 같다(화면 탭 구분용).
     * 회원마다 다른 값은 issuedByMe(이미 받았는지)이고, "받기"·"받음" 버튼 표시에 쓴다.
     * 역할 제한이 없어 로그인한 모든 사용자가 볼 수 있고, 사용자 헤더가 없으면 @CurrentUser가 401을 낸다.
     * page·size·sort 보정은 공통 SearchPageableArgumentResolver가 하고, 정렬 허용 필드는 서비스가 검사한다.
     */
    @Operation(summary = "쿠폰 목록", description = "issueStatus를 보내지 않으면 지금 받을 수 있는(ISSUING) 쿠폰만 보여 준다.")
    @GetMapping
    public ApiResponse<PageResponse<CouponDetailResult>> search(
            @RequestParam(defaultValue = "ISSUING") CouponIssueStatus issueStatus,
            Pageable pageable,
            @CurrentUser UserContext user) {
        // issueStatus가 정의에 없는 값이면 Spring이 타입 변환에 실패해 400 VALIDATION_ERROR가 된다
        return ApiResponse.ok(
                PageResponse.from(couponQueryService.searchCoupons(issueStatus, user.userId(), pageable)));
    }

    /**
     * 쿠폰 상세. 200과 쿠폰 정보에 issuedByMe(요청한 사용자가 이미 받았는지)를 붙여 돌려준다.
     * 목록과 같이 로그인한 모든 사용자가 볼 수 있다.
     */
    @Operation(summary = "쿠폰 상세")
    @GetMapping("/{couponId}")
    public ApiResponse<CouponDetailResult> get(@PathVariable Long couponId, @CurrentUser UserContext user) {
        return ApiResponse.ok(couponQueryService.getCoupon(couponId, user.userId()));
    }

    /**
     * 내 쿠폰 목록. 200과 요청한 사용자가 받은 회원 쿠폰 페이지를 돌려준다. status를 보내지 않으면 전부다.
     * 쿠폰 종류 목록(GET /api/coupons)과 달리 회원이 받은 쿠폰 한 장 한 장(p_user_coupons)을 보여 주는 "내 쿠폰함"이다.
     * status는 받을 수 있는 기간이 아니라 이 한 장을 쓸 수 있는지(AVAILABLE·RESERVED·USED·EXPIRED)이고,
     * 예매에 쓸 userCouponId를 여기서 얻는다.
     * 응답 상태는 저장값이 아니라 만료 시각을 반영한 표시 상태라, 기간이 지난 AVAILABLE은 EXPIRED로 보인다.
     * Note: 명세 권한이 로그인이라 역할을 막지 않는다. SELLER·MASTER는 받은 쿠폰이 없어 빈 목록이다.
     * Note: 요청 /api/coupons/me는 이 /me 패턴과 상세의 /{couponId} 패턴(couponId="me")에 둘 다 맞는다.
     * 한 요청에 맞는 패턴이 여러 개면 Spring은 변수 없는 /me를 고르므로 상세로 가지 않는다.
     * 이 우선순위는 둘 다 맞을 때만 쓰인다. /api/coupons/3처럼 숫자 ID는 /me에 맞지 않아 그대로 상세로 간다.
     */
    @Operation(summary = "내 쿠폰 목록", description = "예매에 쓸 userCouponId를 여기서 얻는다.")
    @GetMapping("/me")
    public ApiResponse<PageResponse<UserCouponResult>> findMine(
            @RequestParam(required = false) UserCouponStatus status, Pageable pageable, @CurrentUser UserContext user) {
        // 받는 회원과 같이 조회 대상도 요청 값이 아니라 사용자 헤더로 정해, 다른 회원의 쿠폰을 볼 수 없다
        return ApiResponse.ok(PageResponse.from(couponQueryService.findMyCoupons(user.userId(), status, pageable)));
    }
}
