package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.coupon.application.dto.IssuedCouponResult;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.IssueDecision;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원의 선착순 쿠폰 발급. 받았는지·남았는지는 발급 판단(CouponIssueGate, Redis)이 정하고, 통과한 요청만 회원 쿠폰을 DB에 저장한다(#300).
 * 쿠폰 행 잠금과 발급 수량 UPDATE가 없어, 인기 쿠폰에 요청이 몰려도 DB에서 한 줄로 기다리지 않는다(#238).
 * 1인 1장은 Redis 회원 키가 먼저 막고, DB UNIQUE(user_id, coupon_id)가 마지막으로 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponIssueGate issueGate;
    private final CouponIssueState issueState;
    private final Clock clock;

    /**
     * 쿠폰을 한 장 발급하고 회원 쿠폰을 돌려준다.
     * Note: 메서드 전체를 한 트랜잭션으로 묶지 않는다. 쿠폰 조회와 회원 쿠폰 저장이 각자 짧게 DB 커넥션을 쓰고,
     * Redis 판단 동안에는 커넥션을 쥐지 않는다. 저장이 실패하면 트랜잭션이 끝난 뒤 Redis 기록을 정리한다(undoIssue).
     * Note: 기간 비교의 now는 메서드 시작에 정한 요청 시각이다. 관리자가 그사이 발급을 중단해도, 중단 전에 읽은 기간으로
     * 판단한 요청은 발급된다. 중단 직전에 도착한 요청으로 보고 허용한다(이전 행 잠금 방식에서도 같았다).
     */
    public IssuedCouponResult issue(Long couponId, Long userId, String username) {
        LocalDateTime now = now();

        // 1) 쿠폰을 잠금 없이 읽는다. 삭제된 쿠폰은 @SQLRestriction으로 조회되지 않아 없는 쿠폰과 같게 404다
        //    발급 기간은 여기서 계산만 하고, 거절은 Redis 판단 안에서 한다(이미 받은 회원에게 "이미 받음"을 먼저 주려고)
        Coupon coupon = couponRepository
                .findById(couponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_FOUND));
        boolean periodOpen = coupon.issueStatus(now) == CouponIssueStatus.ISSUING;

        // 2) Redis에서 판단하고, 통과하면 받은 회원으로 기록한다. 정보가 없으면 DB에서 채운 뒤 한 번 더 판단한다
        IssueDecision decision = issueGate.tryIssue(couponId, userId, periodOpen);
        if (decision == IssueDecision.NOT_LOADED) {
            issueState.ensureLoaded(couponId, coupon.getTotalQuantity());
            decision = issueGate.tryIssue(couponId, userId, periodOpen);
        }
        rejectIfNotIssued(decision);

        // 3) 회원 쿠폰을 저장한다(저장소 메서드가 자기 트랜잭션으로 INSERT·커밋). 실패하면 Redis 기록을 정리하고 그대로 던진다
        try {
            UserCoupon userCoupon = userCouponRepository.saveIssued(UserCoupon.issue(userId, coupon, now));
            return IssuedCouponResult.of(userCoupon, coupon);
        } catch (RuntimeException e) {
            undoIssue(couponId, userId, e);
            throw e;
        }
    }

    /**
     * 저장이 실패한 발급의 Redis 기록을 정리한다. 원칙은 "모르면 지우지 않는다"다.
     * 잘못 지우면 그 자리가 다시 비어 초과 발급이 생기고(되돌릴 수 없음), 잘못 남기면 한 장 덜 발급될 뿐이라
     * 맞추는 작업(후속 이슈)이 DB 기준으로 바로잡을 수 있다.
     * - UNIQUE 위반(이미 받음): DB에 이미 있어 Redis 기록이 맞다. 남긴다
     * - 그 밖의 실패: 커밋 중 연결이 끊긴 경우처럼 실제로는 저장됐을 수 있어 DB를 다시 본다. 없으면 지우고, 있거나 확인도 못 하면 남긴다
     */
    private void undoIssue(Long couponId, Long userId, RuntimeException cause) {
        if (cause instanceof BusinessException be && be.getErrorCode() == CouponErrorCode.COUPON_ALREADY_ISSUED) {
            return;
        }
        try {
            if (!userCouponRepository.existsByUserIdAndCouponId(userId, couponId)) {
                issueGate.cancel(couponId, userId);
            }
        } catch (RuntimeException e) {
            // 확인이나 되돌리기를 못 하면 남긴다. 원래 실패를 응답하고, 남은 기록은 맞추는 작업이 정리한다
            log.warn("발급 저장 실패 뒤 Redis 기록을 정리하지 못했습니다. couponId={}, userId={}", couponId, userId, e);
        }
    }

    /** Redis 판단 결과를 오류 응답으로 바꾼다. 응답 코드와 우선순위는 행 잠금 방식 때와 같다. */
    private static void rejectIfNotIssued(IssueDecision decision) {
        switch (decision) {
            case ISSUED -> {
                // 통과
            }
            case ALREADY_ISSUED -> throw new BusinessException(CouponErrorCode.COUPON_ALREADY_ISSUED);
            case DELETED -> throw new BusinessException(CouponErrorCode.COUPON_NOT_FOUND);
            case PERIOD_CLOSED -> throw new BusinessException(CouponErrorCode.COUPON_ISSUE_PERIOD_CLOSED);
            case SOLD_OUT -> throw new BusinessException(CouponErrorCode.COUPON_SOLD_OUT);
            // 방금 채웠는데도 없으면 Redis가 그사이 비워진 것이다. 판단할 수 없어 잠시 뒤 다시 시도하게 한다
            case NOT_LOADED -> throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** CouponService와 같이 공용 Clock(Asia/Seoul)으로 구하고 초 단위로 자른다. 응답 시각 형식과 DB에 저장한 값을 맞춘다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
