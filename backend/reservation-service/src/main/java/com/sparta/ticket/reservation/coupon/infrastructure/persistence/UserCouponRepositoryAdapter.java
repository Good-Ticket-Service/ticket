package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * UserCouponRepository의 구현. 저장은 Spring Data에 맡기고, 1인 1장 UNIQUE 위반만 업무 오류로 바꾼다.
 * Note: CouponJpaRepository처럼 Spring Data 인터페이스가 domain 저장소를 직접 상속하면 save를 가로챌 수 없어 어댑터로 나눴다.
 */
@Repository
@RequiredArgsConstructor
public class UserCouponRepositoryAdapter implements UserCouponRepository {

    static final String ONE_PER_USER_CONSTRAINT = "uk_p_user_coupons_user_coupon";

    private final UserCouponJpaRepository jpaRepository;

    @Override
    public boolean existsByUserIdAndCouponId(Long userId, Long couponId) {
        return jpaRepository.existsByUserIdAndCouponId(userId, couponId);
    }

    /**
     * 바로 INSERT해 UNIQUE 위반을 이 자리에서 확인한다. 제약 이름이 1인 1장 UNIQUE일 때만 409로 바꾸고, 다른 위반은 그대로 던진다.
     * Note: 위반이 나면 PostgreSQL 트랜잭션은 더 이상 쓸 수 없다. 여기서 잡아 계속 진행하지 않고 예외를 던져 트랜잭션 전체를 롤백한다.
     */
    @Override
    public UserCoupon saveIssued(UserCoupon userCoupon) {
        try {
            // flush까지 해서 INSERT를 바로 실행한다. 제약 위반이 커밋 때가 아니라 여기서 드러난다
            return jpaRepository.saveAndFlush(userCoupon);
        } catch (DataIntegrityViolationException e) {
            // 1인 1장 위반이면 업무 오류(409)로 바꾼다
            if (violates(e, ONE_PER_USER_CONSTRAINT)) {
                throw new BusinessException(CouponErrorCode.COUPON_ALREADY_ISSUED);
            }
            // 다른 제약 위반은 예상하지 못한 오류라 그대로 던진다
            throw e;
        }
    }

    /** 원인 중 Hibernate 제약 위반 예외를 찾아 제약 이름을 비교한다. 메시지 문자열은 DB·버전마다 달라 쓰지 않는다. */
    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        // Spring 예외 안에 감싸인 원인을 따라가며 Hibernate 제약 위반 예외를 찾는다
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
