package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponCategory;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueStatus;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
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
 * save·findById는 JpaRepository가 제공하고, 잠금 조회·목록 조회·맞추는 작업 쿼리만 직접 적는다.
 */
public interface CouponJpaRepository extends JpaRepository<Coupon, Long>, CouponRepository {

    /** SELECT ... FOR UPDATE로 쿠폰 행을 잠그고 읽는다. */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :id")
    Optional<Coupon> lockById(@Param("id") Long id);

    /**
     * 발급 상태에 맞는 쿼리를 골라 부른다. 정렬·페이지는 Pageable을 쓰고 끝에 ID 보조 키를 붙인다(허용 필드 검사는 application이 한다).
     * Note: Spring Data는 인터페이스의 default 메서드를 쿼리로 만들지 않고 그대로 실행한다.
     * Note: 기간 조건(issue_start_at, issue_end_at)에 인덱스가 없어 테이블 전체를 읽는다. 쿠폰 정의는 관리자가 만드는 적은 데이터라
     * 충분하다. 많아지면 EXPLAIN ANALYZE로 실행 계획을 보고 인덱스를 정한다(#197).
     */
    @Override
    default Page<Coupon> searchByIssueStatus(
            CouponCategory category, CouponIssueStatus issueStatus, LocalDateTime now, Pageable pageable) {
        // 같은 발급 시작·종료 시각이 많아도 페이지마다 순서가 같도록 ID까지 정렬한다
        return StableSort.query(pageable, stable -> switch (issueStatus) {
            case UPCOMING -> findUpcoming(category, now, stable);
            case ISSUING -> findIssuing(category, now, stable);
            case ENDED -> findEnded(category, now, stable);
        });
    }

    /** 맞추는 작업 대상. 쿠폰 정의는 관리자가 만드는 적은 데이터라 인덱스 없이 읽는다(searchByIssueStatus와 같은 이유). */
    @Override
    @Query("SELECT c FROM Coupon c WHERE c.issueEndAt > :endAfter ORDER BY c.id")
    List<Coupon> findIssueStateTargets(@Param("endAfter") LocalDateTime endAfter);

    /**
     * 회원 쿠폰 수로 issued_quantity를 맞춘다. 컬럼이 Entity에서 updatable = false라 SQL로 쓴다.
     * 총 수량을 넘는 값은 CHECK 제약에 걸리므로 조건으로 걸러 바꾸지 않는다(호출자가 초과 발급 경보를 남긴다).
     */
    @Override
    @Modifying
    @Query(value = """
            UPDATE p_coupons
            SET issued_quantity = :issuedCount
            WHERE coupon_id = :id
              AND issued_quantity <> :issuedCount
              AND :issuedCount <= total_quantity
            """, nativeQuery = true)
    int syncIssuedQuantity(@Param("id") Long id, @Param("issuedCount") int issuedCount);

    /** 발급 시작 전 쿠폰. */
    @Query("SELECT c FROM Coupon c WHERE c.category = :category AND c.issueStartAt > :now")
    Page<Coupon> findUpcoming(
            @Param("category") CouponCategory category, @Param("now") LocalDateTime now, Pageable pageable);

    /** 발급 중인 쿠폰. 시작 시각 이상, 종료 시각 미만이다. 수량이 다 떨어져도 기간 안이면 발급 중이다. */
    @Query("SELECT c FROM Coupon c WHERE c.category = :category AND c.issueStartAt <= :now AND c.issueEndAt > :now")
    Page<Coupon> findIssuing(
            @Param("category") CouponCategory category, @Param("now") LocalDateTime now, Pageable pageable);

    /** 발급이 끝난 쿠폰. 종료 시각 정각부터 끝난 것으로 본다. 발급 중단한 쿠폰도 여기에 든다. */
    @Query("SELECT c FROM Coupon c WHERE c.category = :category AND c.issueEndAt <= :now")
    Page<Coupon> findEnded(
            @Param("category") CouponCategory category, @Param("now") LocalDateTime now, Pageable pageable);
}
