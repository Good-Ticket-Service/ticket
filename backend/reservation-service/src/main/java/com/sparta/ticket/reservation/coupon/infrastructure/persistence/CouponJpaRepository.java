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

    /**
     * 조건을 모두 만족할 때만 발급 수량을 1 올린다. bulk UPDATE라 감사 컬럼(updated_at, updated_by)을 직접 쓴다.
     * Note: issued_quantity는 Entity에서 updatable = false라 JPQL이 아니라 SQL로 쓴다. 같은 행을 잠그는 관리자 수정·삭제와는 차례로 실행되고,
     * PostgreSQL은 잠금을 기다린 뒤 WHERE를 다시 평가하므로 그사이 삭제·중단·수량 변경이 반영된다.
     * 영속성 컨텍스트에 남은 옛 쿠폰 값을 읽지 않도록 실행 뒤 비운다.
     */
    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE p_coupons
            SET issued_quantity = issued_quantity + 1, updated_at = :now, updated_by = :updatedBy
            WHERE coupon_id = :id
              AND deleted_at IS NULL
              AND issue_start_at <= :now AND issue_end_at > :now
              AND issued_quantity < total_quantity
            """, nativeQuery = true)
    int increaseIssuedQuantity(
            @Param("id") Long id, @Param("now") LocalDateTime now, @Param("updatedBy") String updatedBy);
}
