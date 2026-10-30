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
     * 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 관리자의 수정·발급 중단·삭제처럼 발급과 같은 행을 바꾸기 전에 쓴다.
     * 발급의 조건부 UPDATE는 이 트랜잭션이 끝날 때까지 기다리므로, 잠근 뒤 읽은 발급 수량으로 판단해도 된다.
     */
    Optional<Coupon> lockById(Long id);

    /**
     * 선착순 발급. 발급 기간 안이고 수량이 남은 삭제되지 않은 쿠폰만 발급 수량을 1 올린다. 바뀐 행 수(0 또는 1)를 돌려준다.
     * 조건 확인과 증가가 UPDATE 한 번이라 동시 요청이 몰려도 총 수량을 넘지 않는다. 0행이면 호출자가 쿠폰을 다시 읽어 이유를 가른다.
     * 발급 기간은 Coupon.issueStatus와 같은 경계다(시작 시각 이상, 종료 시각 미만).
     */
    int increaseIssuedQuantity(Long id, LocalDateTime now, String updatedBy);

    /**
     * 발급 상태별 쿠폰 목록. 발급 상태는 컬럼이 아니라 now와 발급 기간으로 계산한다. 삭제된 쿠폰은 빠진다.
     * 경계는 Coupon.issueStatus와 같다(UPCOMING: 시작 전, ISSUING: 시작 이상·종료 미만, ENDED: 종료 이상).
     */
    Page<Coupon> searchByIssueStatus(CouponIssueStatus issueStatus, LocalDateTime now, Pageable pageable);

    /** 여러 쿠폰을 한 번에 읽는다. 내 쿠폰 목록에 쿠폰 이름을 붙일 때 쓴다. 삭제된 쿠폰은 빠진다. */
    List<Coupon> findAllById(Iterable<Long> ids);
}
