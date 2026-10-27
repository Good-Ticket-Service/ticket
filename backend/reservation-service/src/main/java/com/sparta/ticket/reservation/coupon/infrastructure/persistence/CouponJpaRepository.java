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

public interface CouponJpaRepository extends JpaRepository<Coupon, Long>, CouponRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :id")
    Optional<Coupon> lockById(@Param("id") Long id);

    @Override
    @Modifying
    @Query("""
            UPDATE Coupon c SET c.deletedAt = :deletedAt, c.deletedBy = :deletedBy
            WHERE c.id = :id AND c.issuedQuantity = 0 AND c.deletedAt IS NULL
            """)
    int softDeleteIfNotIssued(
            @Param("id") Long id, @Param("deletedBy") String deletedBy, @Param("deletedAt") LocalDateTime deletedAt);
}
