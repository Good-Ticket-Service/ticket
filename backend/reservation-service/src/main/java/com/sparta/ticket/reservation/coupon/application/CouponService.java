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
        // 검증과 생성은 Coupon이 한다. 규칙에 어긋나면 여기서 예외가 난다
        Coupon coupon = Coupon.create(
                command.name(),
                command.discountAmount(),
                command.totalQuantity(),
                command.issueStartAt(),
                command.issueEndAt(),
                command.validDays(),
                now);
        // 저장하면 DB가 만든 ID가 채워진다. 응답의 발급 상태는 같은 now로 계산한다
        return CouponResult.of(couponRepository.save(coupon), now);
    }

    /**
     * 보낸 항목만 바꾼다. 바꿀 수 있는지는 Coupon.update가 판단하고, 바뀐 값은 트랜잭션이 끝날 때 변경 감지로 저장된다.
     * 발급과 같은 행을 바꾸므로 잠그고 읽는다. 총 수량을 발급 수량과 비교할 때 발급이 끼어들지 않는다.
     */
    @Transactional
    public CouponResult update(Long couponId, UpdateCouponCommand command) {
        LocalDateTime now = now();
        // 행을 잠그고 읽어, 수정이 끝날 때까지 발급의 수량 UPDATE가 기다리게 한다
        Coupon coupon = lockCoupon(couponId);
        // 바꿀 수 있는지 판단과 값 변경은 Coupon이 한다. 저장은 커밋 때 변경 감지로 된다
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
        // 발급과 겹치지 않도록 행을 잠그고 읽은 뒤 종료 시각을 당긴다
        Coupon coupon = lockCoupon(couponId);
        coupon.close(now);
        return CouponResult.of(coupon, now);
    }

    /**
     * 아무도 받지 않은 쿠폰만 논리 삭제한다. 지울 수 있는지는 Coupon.delete가 판단하고, 커밋 때 변경 감지로 저장된다.
     * 수정·발급 중단과 같이 행을 잠그고 읽어 첫 발급과 경합하지 않는다.
     * - 삭제가 먼저 잠그면 발급의 수량 UPDATE는 기다린 뒤 deleted_at 조건으로 0행이 되고, 발급은 404다
     * - 발급이 먼저면 삭제는 기다린 뒤 발급 수량 1을 읽어 409 COUPON_NOT_DELETABLE이다
     * 없거나 이미 삭제된 쿠폰은 잠금 조회가 비어 404다. 동시에 두 번 지우면 뒤 요청은 앞 삭제가 커밋된 뒤 행을 다시 확인해 404다.
     */
    @Transactional
    public void delete(Long couponId, String username) {
        // 행을 잠그고 읽어, 삭제가 끝날 때까지 발급의 수량 UPDATE가 기다리게 한다
        Coupon coupon = lockCoupon(couponId);
        // 지울 수 있는지 판단과 삭제 기록은 Coupon이 한다. 저장은 커밋 때 변경 감지로 되고 updated_at·updated_by도 함께 남는다
        coupon.delete(username);
    }

    /**
     * 쿠폰 행을 잠그고 읽는다. 없거나 삭제된 쿠폰이면 404 COUPON_NOT_FOUND.
     * Note: 비관적 락(PESSIMISTIC_WRITE, SELECT ... FOR UPDATE)이다. 커밋할 때까지 같은 행을 바꾸려는
     * 발급 수량 UPDATE·삭제·다른 수정은 기다리므로, 읽은 값으로 판단하는 동안 값이 바뀌지 않는다.
     */
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
