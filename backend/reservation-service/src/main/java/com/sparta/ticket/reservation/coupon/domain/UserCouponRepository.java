package com.sparta.ticket.reservation.coupon.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** domain이 필요로 하는 회원 쿠폰 저장소 기능만 선언한다. 구현은 infrastructure의 UserCouponRepositoryAdapter가 맡는다. */
public interface UserCouponRepository {

    /** 이 회원이 이 쿠폰을 이미 받았는지. 발급 전에 중복을 빨리 거르는 데 쓴다. 동시 요청은 저장 때 UNIQUE가 막는다. */
    boolean existsByUserIdAndCouponId(Long userId, Long couponId);

    /**
     * 새로 발급한 회원 쿠폰을 저장한다(INSERT). 저장된 쿠폰에는 DB가 만든 ID가 채워진다.
     * 같은 회원이 같은 쿠폰을 이미 받았으면 uk_p_user_coupons_user_coupon 위반을 409 COUPON_ALREADY_ISSUED로 바꿔 던진다.
     * 예외가 트랜잭션 밖으로 나가므로 같은 트랜잭션에서 올린 발급 수량도 함께 롤백된다.
     */
    UserCoupon saveIssued(UserCoupon userCoupon);

    /** 주어진 쿠폰들 가운데 이 회원이 받은 쿠폰의 ID. 쿠폰 목록의 issuedByMe를 한 번의 조회로 채울 때 쓴다. */
    Set<Long> findIssuedCouponIds(Long userId, Collection<Long> couponIds);

    /**
     * 회원의 쿠폰 목록. status가 null이면 전부다.
     * AVAILABLE은 아직 만료되지 않은 것만, EXPIRED는 저장된 EXPIRED와 만료 시각이 지난 AVAILABLE을 함께 돌려준다.
     * 이 경계는 UserCoupon.displayStatus와 같다.
     */
    Page<UserCoupon> findMine(Long userId, UserCouponStatus status, LocalDateTime now, Pageable pageable);

    /**
     * 회원 쿠폰을 잠그고 읽는다(SELECT ... FOR UPDATE). 예매가 이 쿠폰을 잡을 때(CouponUsage.reserve) 쓴다.
     * 같은 쿠폰을 동시에 잡으려는 다른 요청은 이 트랜잭션이 끝날 때까지 기다린 뒤 바뀐 상태를 읽는다.
     */
    Optional<UserCoupon> lockById(Long userCouponId);

    /**
     * 이 예매에 잡힌 회원 쿠폰을 잠그고 읽는다. 예매에 잡힌 쿠폰은 RESERVED·USED이고 예매당 한 장이다(uk_p_user_coupons_reservation).
     * 사용 확정·반환(CouponUsage.use·release)이 쓴다. 잡힌 쿠폰이 없거나 이미 돌려줬으면 비어 있다.
     */
    Optional<UserCoupon> lockByReservationId(Long reservationId);

    /**
     * 이 예매에 다른 회원 쿠폰이 잡혀 있는지. 예매에 쿠폰을 잡기 전에 확인한다(쿠폰을 바꾸려면 예매가 release를 먼저 부른다).
     * Note: 잠그지 않고 읽는다. 같은 예매의 쿠폰 호출은 예매가 예약 행을 먼저 잠가 차례로 들어오므로 그사이 바뀌지 않는다.
     */
    boolean existsOtherOnReservation(Long reservationId, Long userCouponId);

    /**
     * 만료 배치가 바꿀 회원 쿠폰의 ID. AVAILABLE이면서 만료 시각이 now 이하인 것을 만료 시각이 이른 순으로 limit개 고른다.
     * Note: 잠그지 않고 고른다. 고른 뒤 예매가 잡은 쿠폰은 expire의 조건이 다시 걸러 낸다.
     */
    List<Long> findExpirableIds(LocalDateTime now, int limit);

    /**
     * 고른 회원 쿠폰을 EXPIRED로 바꾸고 바뀐 행 수를 돌려준다. 그사이 상태가 바뀐 쿠폰은 건너뛴다.
     * 저장값만 맞추는 정리 작업이다. 쓸 수 있는지는 이 결과와 상관없이 계속 만료 시각으로 판단한다(UserCoupon.displayStatus·reserve).
     */
    int expire(Collection<Long> userCouponIds, LocalDateTime now, String updatedBy);
}
