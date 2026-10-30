package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.application.facade.CouponDiscount;
import com.sparta.ticket.reservation.coupon.application.facade.CouponUsage;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
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
 * booking의 결제 준비·확정·만료·취소가 회원 쿠폰을 잡고·쓰고·돌려주는 구현(#125).
 * 회원 쿠폰 행을 잠그고(FOR UPDATE) 읽은 뒤, 바꿀 수 있는지와 상태 변경은 UserCoupon 메서드가 한다. 저장은 커밋 때 변경 감지가 한다.
 *
 * Note: 전파는 REQUIRED다(@Transactional 기본값). booking의 트랜잭션에 합류해 예약과 쿠폰이 함께 커밋·롤백된다.
 * 회원 쿠폰은 회원이 받은 한 장마다 행이 따로라 대부분 잠금을 기다리지 않는다. 같은 쿠폰을 두 예매가 동시에 잡을 때만 차례로 처리해
 * 이중 사용을 막는다. 발급처럼 한 행에 요청이 몰리지 않으므로, 규칙을 엔티티에 모을 수 있는 FOR UPDATE 방식을 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CouponUsageService implements CouponUsage {

    private final UserCouponRepository userCouponRepository;
    private final CouponRepository couponRepository;
    private final Clock clock;

    @Override
    public CouponDiscount reserve(Long userCouponId, Long userId, Long reservationId) {
        // 1) 회원이 고른 쿠폰을 잠그고 읽는다. 같은 쿠폰을 동시에 잡으려는 다른 예매는 이 트랜잭션이 끝날 때까지 기다린다.
        //    없는 쿠폰도 남의 쿠폰과 같은 오류로 답해 다른 회원 쿠폰의 존재를 드러내지 않는다
        UserCoupon userCoupon = userCouponRepository
                .lockById(userCouponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_AVAILABLE));

        // 2) 이 예매에 다른 쿠폰이 이미 잡혀 있으면 호출 순서가 잘못됐다(바꿀 때는 release를 먼저 부른다).
        //    예약당 한 장은 DB의 uk_p_user_coupons_reservation도 막지만, 커밋 때가 아니라 여기서 바로 알린다
        if (userCouponRepository.existsOtherOnReservation(reservationId, userCouponId)) {
            throw new IllegalStateException("이 예매에 다른 쿠폰이 잡혀 있습니다. 쿠폰을 바꾸려면 먼저 돌려주세요. reservationId=" + reservationId);
        }

        // 3) 본인 쿠폰·상태·유효 기간 확인과 RESERVED 전이는 엔티티가 한다. 이미 이 예매에 잡혀 있으면 그대로 둔다(멱등)
        userCoupon.reserve(userId, reservationId, now());

        // 4) 예매 화면에 보여 줄 쿠폰 이름을 붙여 돌려준다
        return new CouponDiscount(
                userCoupon.getId(), userCoupon.getCouponId(), couponName(userCoupon), userCoupon.getDiscountAmount());
    }

    @Override
    public void use(Long reservationId) {
        // 이 예매에 잡힌 쿠폰이 있으면 사용 확정한다. 쿠폰을 쓰지 않은 예매면 할 일이 없다. 이미 USED면 엔티티가 그대로 둔다
        userCouponRepository.lockByReservationId(reservationId).ifPresent(userCoupon -> userCoupon.use(now()));
    }

    @Override
    public void release(Long reservationId) {
        // 이 예매에 잡힌 쿠폰이 있으면 돌려준다. 돌려주면 예매 ID가 지워지므로, 다시 불러도 찾지 못해 아무것도 하지 않는다
        userCouponRepository.lockByReservationId(reservationId).ifPresent(userCoupon -> userCoupon.release(now()));
    }

    /**
     * 쿠폰 정의의 이름. 회원 쿠폰에는 이름을 복사하지 않는다.
     * Note: 받은 사람이 있는 쿠폰은 삭제할 수 없어(softDeleteIfNotIssued) 정의가 항상 있다. 받은 쿠폰도 삭제할 수 있게 바꾸면 다시 정해야 한다.
     */
    private String couponName(UserCoupon userCoupon) {
        return couponRepository
                .findById(userCoupon.getCouponId())
                .map(Coupon::getName)
                .orElse(null);
    }

    /** 다른 쿠폰 서비스와 같이 공용 Clock(Asia/Seoul)으로 구하고 초 단위로 자른다. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
