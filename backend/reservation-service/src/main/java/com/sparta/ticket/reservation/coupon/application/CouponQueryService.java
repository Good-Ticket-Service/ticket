package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.page.SortAllowList;
import com.sparta.ticket.reservation.coupon.application.dto.CouponDetailResult;
import com.sparta.ticket.reservation.coupon.application.dto.UserCouponResult;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 목록·상세와 내 쿠폰 조회. 로그인한 모든 회원이 쓰고, 데이터를 바꾸지 않는다.
 * 페이지·크기 보정과 기본 정렬은 공통 SearchPageableArgumentResolver가 하고, 정렬 허용 필드는 여기서 API마다 검사한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponQueryService {

    /** 쿠폰 목록에서 정렬할 수 있는 필드(docs/api.md GET /api/coupons). */
    static final Set<String> COUPON_SORTS = Set.of("createdAt", "issueStartAt", "issueEndAt");

    /** 내 쿠폰에서 정렬할 수 있는 필드(docs/api.md GET /api/coupons/me). */
    static final Set<String> MY_COUPON_SORTS = Set.of("createdAt", "expiresAt");

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final Clock clock;

    /**
     * 발급 상태별 쿠폰 목록. 각 쿠폰에 이 회원이 받았는지(issuedByMe)를 붙인다.
     * Note: issuedByMe를 쿠폰마다 조회하면 페이지 크기만큼 쿼리가 나간다(N+1). 한 페이지의 쿠폰 ID로 한 번에 조회한다.
     */
    public Page<CouponDetailResult> searchCoupons(CouponIssueStatus issueStatus, Long userId, Pageable pageable) {
        // 허용하지 않은 정렬 필드면 400 VALIDATION_ERROR(InvalidSortException)
        SortAllowList.check(pageable, COUPON_SORTS);
        LocalDateTime now = now();

        // 1) 현재 시각으로 발급 상태를 계산해 이 페이지의 쿠폰을 읽는다
        Page<Coupon> coupons = couponRepository.searchByIssueStatus(issueStatus, now, pageable);

        // 2) 이 페이지 쿠폰 가운데 회원이 받은 것을 한 번에 찾는다
        List<Long> couponIds = coupons.map(Coupon::getId).getContent();
        Set<Long> issuedIds = userCouponRepository.findIssuedCouponIds(userId, couponIds);

        // 3) 받은 쿠폰이면 issuedByMe = true. SELLER·MASTER는 받을 수 없어 항상 false가 된다
        return coupons.map(coupon -> CouponDetailResult.of(coupon, now, issuedIds.contains(coupon.getId())));
    }

    /** 쿠폰 상세. 없거나 삭제된 쿠폰이면 404 COUPON_NOT_FOUND. */
    public CouponDetailResult getCoupon(Long couponId, Long userId) {
        Coupon coupon = couponRepository
                .findById(couponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_FOUND));
        boolean issuedByMe = userCouponRepository.existsByUserIdAndCouponId(userId, couponId);
        return CouponDetailResult.of(coupon, now(), issuedByMe);
    }

    /**
     * 내 쿠폰 목록. status가 null이면 전부다. 응답 상태는 만료 시각을 반영한 표시 상태다.
     * Note: 회원 쿠폰에는 쿠폰 이름이 없다. 이 페이지 쿠폰들의 정의를 한 번에 읽어 이름을 붙인다(N+1 방지).
     */
    public Page<UserCouponResult> findMyCoupons(Long userId, UserCouponStatus status, Pageable pageable) {
        // 허용하지 않은 정렬 필드면 400 VALIDATION_ERROR
        SortAllowList.check(pageable, MY_COUPON_SORTS);
        LocalDateTime now = now();

        // 1) 상태 필터에 맞는 이 회원의 쿠폰을 읽는다. AVAILABLE·EXPIRED는 만료 시각으로 나눈다
        Page<UserCoupon> userCoupons = userCouponRepository.findMine(userId, status, now, pageable);

        // 2) 쿠폰 이름을 붙이려고 이 페이지의 쿠폰 정의를 한 번에 읽어 ID → 이름으로 둔다. ID로 읽으므로 키가 겹치지 않는다
        // Note: 삭제된 쿠폰은 읽히지 않아 이름이 null이 된다. 지금은 발급 수량이 0일 때만 삭제할 수 있어(Coupon.delete)
        // 회원이 받은 쿠폰의 정의는 삭제되지 않는다. 받은 쿠폰도 삭제할 수 있게 바꾸면 여기서 이름을 다시 정해야 한다
        Set<Long> couponIds = userCoupons.map(UserCoupon::getCouponId).toSet();
        Map<Long, String> names = couponRepository.findAllById(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, Coupon::getName));

        // 3) 응답으로 바꾼다. 상태는 같은 now로 계산해 필터와 어긋나지 않는다
        return userCoupons.map(userCoupon -> UserCouponResult.of(userCoupon, names.get(userCoupon.getCouponId()), now));
    }

    /** 다른 쿠폰 서비스와 같이 공용 Clock(Asia/Seoul)으로 구하고 초 단위로 자른다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
