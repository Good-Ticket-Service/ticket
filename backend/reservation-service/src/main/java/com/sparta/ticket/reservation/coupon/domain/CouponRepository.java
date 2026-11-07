package com.sparta.ticket.reservation.coupon.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface CouponRepository {

    /** 새 쿠폰을 저장한다(INSERT). 저장된 쿠폰에는 DB가 만든 ID가 채워진다. */
    Coupon save(Coupon coupon);

    /** 논리 삭제된 쿠폰은 찾지 않는다(Coupon의 @SQLRestriction). */
    Optional<Coupon> findById(Long id);

    /**
     * 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 관리자의 수정·발급 중단·삭제끼리 차례로 처리할 때 쓴다.
     * 발급은 이 잠금을 쓰지 않는다. 발급과의 경합은 Redis 발급 판단(CouponIssueGate)이 맞춘다(#300).
     */
    Optional<Coupon> lockById(Long id);

    /**
     * 발급 상태별 쿠폰 목록. 발급 상태는 컬럼이 아니라 now와 발급 기간으로 계산한다. 삭제된 쿠폰은 빠진다.
     * 경계는 Coupon.issueStatus와 같다(UPCOMING: 시작 전, ISSUING: 시작 이상·종료 미만, ENDED: 종료 이상).
     */
    Page<Coupon> searchByIssueStatus(CouponIssueStatus issueStatus, LocalDateTime now, Pageable pageable);

    /** 여러 쿠폰을 한 번에 읽는다. 내 쿠폰 목록에 쿠폰 이름을 붙일 때 쓴다. 삭제된 쿠폰은 빠진다. */
    List<Coupon> findAllById(Iterable<Long> ids);
}
