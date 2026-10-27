package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자의 쿠폰 생성·수정·발급 중단·삭제. 업무 규칙은 Coupon이 갖고, 이 클래스는 조회·저장과 현재 시각을 맡는다. */
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    @Transactional
    public CouponResult create(CreateCouponCommand command) {
        LocalDateTime now = now();
        Coupon coupon = Coupon.create(
                command.name(),
                command.discountAmount(),
                command.totalQuantity(),
                command.issueStartAt(),
                command.issueEndAt(),
                command.validDays(),
                now);
        return CouponResult.of(couponRepository.save(coupon), now);
    }

    /** 발급과 같은 행을 바꾸므로 잠그고 읽는다. 총 수량을 발급 수량과 비교할 때 발급이 끼어들지 않는다. */
    @Transactional
    public CouponResult update(Long couponId, UpdateCouponCommand command) {
        LocalDateTime now = now();
        Coupon coupon = lockCoupon(couponId);
        coupon.update(
                command.name(),
                command.discountAmount(),
                command.totalQuantity(),
                command.issueStartAt(),
                command.issueEndAt(),
                command.validDays(),
                now);
        return CouponResult.of(coupon, now);
    }

    /** 발급 종료 시각을 현재로 당긴다. 발급과 같은 행을 바꾸므로 잠그고 읽는다. */
    @Transactional
    public CouponResult close(Long couponId) {
        LocalDateTime now = now();
        Coupon coupon = lockCoupon(couponId);
        coupon.close(now);
        return CouponResult.of(coupon, now);
    }

    private Coupon lockCoupon(Long couponId) {
        return couponRepository
                .lockById(couponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    /**
     * Note: 초 단위로 자른다. 응답 시각 형식이 yyyy-MM-ddTHH:mm:ss이고, 발급 중단은 이 값을 종료 시각으로 저장한다.
     * 자르지 않으면 나노초가 응답에 붙고, PostgreSQL TIMESTAMP(마이크로초)에 저장한 값과 메모리의 값이 달라진다.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
