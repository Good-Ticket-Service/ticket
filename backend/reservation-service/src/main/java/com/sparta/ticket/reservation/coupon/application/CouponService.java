package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import java.time.Clock;
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
    private final Clock clock;

    /** 쿠폰을 만들어 저장하고, 계산 값(남은 수량, 발급 상태)을 붙여 돌려준다. 발급 수량은 0으로 시작한다. */
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

    /**
     * 보낸 항목만 바꾼다. 바꿀 수 있는지는 Coupon.update가 판단하고, 바뀐 값은 트랜잭션이 끝날 때 변경 감지로 저장된다.
     * 발급과 같은 행을 바꾸므로 잠그고 읽는다. 총 수량을 발급 수량과 비교할 때 발급이 끼어들지 않는다.
     */
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

    /**
     * 아무도 받지 않은 쿠폰만 논리 삭제한다. 조건 확인과 삭제를 조건부 UPDATE 한 번으로 해서 첫 발급과 경합하지 않는다.
     * 0행이면 그때 이유를 가른다. 남아 있는 쿠폰이면 이미 발급된 것(409), 없으면 없거나 이미 삭제된 것(404)이다.
     * Note: 조회를 UPDATE보다 먼저 하면, 그사이 다른 요청이 삭제했을 때 404여야 할 응답이 409가 된다.
     */
    @Transactional
    public void delete(Long couponId, String username) {
        int deleted = couponRepository.softDeleteIfNotIssued(couponId, username, now());
        if (deleted == 0) {
            throw new BusinessException(
                    couponRepository.existsById(couponId)
                            ? CouponErrorCode.COUPON_NOT_DELETABLE
                            : CouponErrorCode.COUPON_NOT_FOUND);
        }
    }

    /** 쿠폰 행을 잠그고 읽는다. 없거나 삭제된 쿠폰이면 404 COUPON_NOT_FOUND. */
    private Coupon lockCoupon(Long couponId) {
        return couponRepository
                .lockById(couponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    /**
     * 현재 시각은 reservation-service 공용 Clock(ClockConfig, Asia/Seoul)으로 구한다. 서버 시간대와 상관없이 KST 기준이고, 테스트는 Clock을 바꿔 시각을 정한다.
     * Note: 초 단위로 자른다. 응답 시각 형식이 yyyy-MM-ddTHH:mm:ss이고, 발급 중단은 이 값을 종료 시각으로 저장한다.
     * 자르지 않으면 나노초가 응답에 붙고, PostgreSQL TIMESTAMP(마이크로초)에 저장한 값과 메모리의 값이 달라진다.
     */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
