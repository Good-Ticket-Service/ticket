package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.UserCoupon;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Override
    public Optional<UserCoupon> lockById(Long userCouponId) {
        return jpaRepository.lockById(userCouponId);
    }

    @Override
    public Optional<UserCoupon> lockByReservationId(Long reservationId) {
        return jpaRepository.lockByReservationId(reservationId);
    }

    @Override
    public boolean existsOtherOnReservation(Long reservationId, Long userCouponId) {
        return jpaRepository.existsByReservationIdAndIdNot(reservationId, userCouponId);
    }

    @Override
    public Set<Long> findIssuedCouponIds(Long userId, Collection<Long> couponIds) {
        // 빈 목록으로 IN 쿼리를 만들지 않는다. 빈 페이지면 조회할 것이 없다
        if (couponIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jpaRepository.findIssuedCouponIds(userId, couponIds));
    }

    /**
     * 상태 필터에 맞는 쿼리를 고른다. AVAILABLE·EXPIRED는 만료 시각까지 보아야 해서 저장된 상태만으로 찾지 않는다.
     * 같은 만료 시각이 많아도 페이지마다 순서가 같도록 정렬 끝에 ID 보조 키를 붙인다(StableSort).
     */
    @Override
    public Page<UserCoupon> findMine(Long userId, UserCouponStatus status, LocalDateTime now, Pageable pageable) {
        return StableSort.query(pageable, stable -> {
            if (status == null) {
                return jpaRepository.findByUserId(userId, stable);
            }
            return switch (status) {
                // 쓸 수 있는 쿠폰: 만료 시각이 지난 AVAILABLE은 뺀다
                case AVAILABLE -> jpaRepository.findAvailable(userId, now, stable);
                // 만료된 쿠폰: 배치가 아직 처리하지 않은 AVAILABLE도 넣는다
                case EXPIRED -> jpaRepository.findExpired(userId, now, stable);
                // 예매에 잡혔거나 사용한 쿠폰은 저장된 상태 그대로다
                case RESERVED, USED -> jpaRepository.findByUserIdAndStatus(userId, status, stable);
            };
        });
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

    /**
     * 원인 중 PostgreSQL 드라이버 예외를 찾아, 서버가 오류 정보에 따로 담아 보낸 제약 이름을 비교한다(SeatHoldService.violates와 같은 방식).
     * Note: Hibernate의 ConstraintViolationException.getConstraintName()은 이 이름을 영문 오류 메시지 문장
     * ("violates unique constraint \"...\"")에서 잘라 낸다(Hibernate 6.6 PostgreSQLDialect). DB 메시지 언어(lc_messages)가
     * 영어가 아니면 null이 되어, 같은 회원의 동시 발급이 409가 아니라 500으로 나간다. 서버 오류 정보의 제약 이름은 언어와 관계없다.
     */
    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        // Spring 예외 안에 감싸인 원인을 따라가며 드라이버 예외를 찾는다
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return constraintName.equalsIgnoreCase(
                        psql.getServerErrorMessage().getConstraint());
            }
        }
        return false;
    }
}
