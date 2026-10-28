package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.application.dto.IssuedCouponResult;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원의 선착순 쿠폰 발급. 발급 수량 증가와 회원 쿠폰 저장을 한 트랜잭션에서 한다.
 * 수량은 조건부 UPDATE 한 번으로 올려 동시 요청이 몰려도 총 수량을 넘지 않고, 1인 1장은 사전 확인과 DB UNIQUE로 지킨다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final Clock clock;

    /**
     * 쿠폰을 한 장 발급하고 회원 쿠폰을 돌려준다.
     * 실패하면 예외로 트랜잭션 전체가 롤백되어, 올린 발급 수량도 함께 취소된다.
     * Note: 수량을 먼저 올리고 회원 쿠폰을 나중에 저장한다. 수량이 없으면 INSERT 없이 끝나고,
     * INSERT가 중복으로 실패해도 롤백으로 수량이 되돌아가므로 두 값이 어긋나지 않는다.
     */
    @Transactional
    public IssuedCouponResult issue(Long couponId, Long userId, String username) {
        LocalDateTime now = now();

        // 1) 이미 받은 회원은 잠금을 잡기 전에 빨리 거른다. 동시에 들어온 같은 회원의 요청은 4)의 UNIQUE가 막는다
        if (userCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
            throw new BusinessException(CouponErrorCode.COUPON_ALREADY_ISSUED);
        }

        // 2) 발급 기간·수량·삭제 여부를 확인하며 발급 수량을 1 올린다.
        //    Note: UPDATE는 바꾸는 행에 자동으로 쓰기 락을 걸고 커밋까지 유지한다(SELECT ... FOR UPDATE 불필요).
        //    같은 쿠폰의 다른 발급·수정은 이 락이 풀릴 때까지 기다린 뒤 최신 값으로 조건을 다시 확인한다
        int increased = couponRepository.increaseIssuedQuantity(couponId, now, username);
        if (increased == 0) {
            throw new BusinessException(rejectReason(couponId, now));
        }

        // 3) 할인액·유효 일수를 복사하려고 쿠폰을 읽는다. 행을 잠그고 있어 관리자 수정이 끼어들지 않는다
        Coupon coupon = couponRepository
                .findById(couponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_FOUND));

        // 4) 회원 쿠폰을 저장한다. 같은 회원이 동시에 받으면 여기서 COUPON_ALREADY_ISSUED로 실패한다
        UserCoupon userCoupon = userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, now));
        return IssuedCouponResult.of(userCoupon, coupon);
    }

    /**
     * 발급 수량 UPDATE가 0행일 때 이유를 가른다. 확인 순서는 없음(404) → 기간 밖(400) → 소진(409)이다.
     * Note: UPDATE와 이 조회 사이에 관리자가 기간이나 총 수량을 바꾸면 세 조건이 모두 통과할 수 있다.
     * UPDATE 시점에는 발급할 수 없었으므로 소진(409)으로 응답하고, 회원이 다시 요청하면 새 조건으로 판단된다.
     */
    private CouponErrorCode rejectReason(Long couponId, LocalDateTime now) {
        // 삭제된 쿠폰은 @SQLRestriction으로 조회되지 않아 없는 쿠폰과 같게 404다
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            return CouponErrorCode.COUPON_NOT_FOUND;
        }
        // 시작 전이거나 끝났으면 기간 밖이다
        if (coupon.issueStatus(now) != CouponIssueStatus.ISSUING) {
            return CouponErrorCode.COUPON_ISSUE_PERIOD_CLOSED;
        }
        return CouponErrorCode.COUPON_SOLD_OUT;
    }

    /** CouponService와 같이 공용 Clock(Asia/Seoul)으로 구하고 초 단위로 자른다. 응답 시각 형식과 DB에 저장한 값을 맞춘다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
