package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CouponRepository의 구현. Spring Data JPA가 실행 시점에 구현체를 만들어 준다.
 * save·findById·existsById는 JpaRepository가 제공하고, 잠금 조회와 조건부 삭제만 쿼리를 직접 적는다.
 */
public interface CouponJpaRepository extends JpaRepository<Coupon, Long>, CouponRepository {

    /** SELECT ... FOR UPDATE로 쿠폰 행을 잠그고 읽는다. */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :id")
    Optional<Coupon> lockById(@Param("id") Long id);

    /** 발급 수량이 0이고 아직 삭제되지 않은 쿠폰에만 삭제 시각·삭제자를 기록한다. 바뀐 행 수를 돌려준다. */
    @Override
    @Modifying
    @Query("""
            UPDATE Coupon c SET c.deletedAt = :deletedAt, c.deletedBy = :deletedBy
            WHERE c.id = :id AND c.issuedQuantity = 0 AND c.deletedAt IS NULL
            """)
    int softDeleteIfNotIssued(
            @Param("id") Long id, @Param("deletedBy") String deletedBy, @Param("deletedAt") LocalDateTime deletedAt);
}
