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
     * 구분·발급 상태별 쿠폰 목록. 발급 상태는 컬럼이 아니라 now와 발급 기간으로 계산한다. 삭제된 쿠폰은 빠진다.
     * 경계는 Coupon.issueStatus와 같다(UPCOMING: 시작 전, ISSUING: 시작 이상·종료 미만, ENDED: 종료 이상).
     */
    Page<Coupon> searchByIssueStatus(
            CouponCategory category, CouponIssueStatus issueStatus, LocalDateTime now, Pageable pageable);

    /** 여러 쿠폰을 한 번에 읽는다. 내 쿠폰 목록에 쿠폰 이름을 붙일 때 쓴다. 삭제된 쿠폰은 빠진다. */
    List<Coupon> findAllById(Iterable<Long> ids);

    /**
     * 맞추는 작업의 대상(#323). 발급 종료 시각이 endAfter보다 뒤인 쿠폰, 즉 Redis 발급 판단 키가 아직 남아 있을 쿠폰이다.
     * 발급 전·발급 중 쿠폰과 끝난 지 하루가 안 된 쿠폰이 든다. 삭제된 쿠폰은 빠진다(발급은 DB 조회에서 404로 끝난다).
     */
    List<Coupon> findIssueStateTargets(LocalDateTime endAfter);

    /**
     * issued_quantity를 회원 쿠폰 수로 맞추고 바뀐 행 수(0 또는 1)를 돌려준다(#300에서 발급 때 올리지 않게 된 참고값).
     * 이미 같거나, 회원 쿠폰 수가 총 수량을 넘으면(초과 발급, ck_p_coupons_quantity 위반) 바꾸지 않는다.
     * Note: 감사 컬럼(updated_at, updated_by)은 바꾸지 않는다. 사람이 바꾼 것이 아니라 주기마다 맞추는 값이기 때문이다.
     */
    int syncIssuedQuantity(Long id, int issuedCount);
}
