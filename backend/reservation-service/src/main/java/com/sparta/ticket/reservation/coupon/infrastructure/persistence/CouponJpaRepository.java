package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CouponRepository의 구현. Spring Data JPA가 실행 시점에 구현체를 만들어 준다.
 * save·findById는 JpaRepository가 제공하고, 잠금 조회·발급 수량 증가·목록 조회만 쿼리를 직접 적는다.
 */
public interface CouponJpaRepository extends JpaRepository<Coupon, Long>, CouponRepository {

    /** SELECT ... FOR UPDATE로 쿠폰 행을 잠그고 읽는다. */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :id")
    Optional<Coupon> lockById(@Param("id") Long id);

    /**
     * 조건을 모두 만족할 때만 발급 수량을 1 올린다. 엔티티를 거치지 않고 쿼리로 바로 UPDATE하므로(변경 감지가 아님)
     * JPA 감사 리스너가 동작하지 않아 감사 컬럼(updated_at, updated_by)을 직접 쓴다.
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

    /**
     * 발급 상태에 맞는 쿼리를 골라 부른다. 정렬·페이지는 Pageable을 쓰고 끝에 ID 보조 키를 붙인다(허용 필드 검사는 application이 한다).
     * Note: Spring Data는 인터페이스의 default 메서드를 쿼리로 만들지 않고 그대로 실행한다.
     */
    @Override
    default Page<Coupon> searchByIssueStatus(CouponIssueStatus issueStatus, LocalDateTime now, Pageable pageable) {
        // 같은 발급 시작·종료 시각이 많아도 페이지마다 순서가 같도록 ID까지 정렬한다
        return StableSort.query(pageable, stable -> switch (issueStatus) {
            case UPCOMING -> findUpcoming(now, stable);
            case ISSUING -> findIssuing(now, stable);
            case ENDED -> findEnded(now, stable);
        });
    }

    /** 발급 시작 전 쿠폰. */
    @Query("SELECT c FROM Coupon c WHERE c.issueStartAt > :now")
    Page<Coupon> findUpcoming(@Param("now") LocalDateTime now, Pageable pageable);

    /** 발급 중인 쿠폰. 시작 시각 이상, 종료 시각 미만이다. 수량이 다 떨어져도 기간 안이면 발급 중이다. */
    @Query("SELECT c FROM Coupon c WHERE c.issueStartAt <= :now AND c.issueEndAt > :now")
    Page<Coupon> findIssuing(@Param("now") LocalDateTime now, Pageable pageable);

    /** 발급이 끝난 쿠폰. 종료 시각 정각부터 끝난 것으로 본다. 발급 중단한 쿠폰도 여기에 든다. */
    @Query("SELECT c FROM Coupon c WHERE c.issueEndAt <= :now")
    Page<Coupon> findEnded(@Param("now") LocalDateTime now, Pageable pageable);
}
