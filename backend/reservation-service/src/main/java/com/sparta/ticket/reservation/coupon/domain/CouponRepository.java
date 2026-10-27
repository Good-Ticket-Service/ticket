package com.sparta.ticket.reservation.coupon.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/** domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다. */
public interface CouponRepository {

    /** 새 쿠폰을 저장한다(INSERT). 저장된 쿠폰에는 DB가 만든 ID가 채워진다. */
    Coupon save(Coupon coupon);

    /** 논리 삭제된 쿠폰은 찾지 않는다(Coupon의 @SQLRestriction). */
    Optional<Coupon> findById(Long id);

    /**
     * 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 관리자의 수정·발급 중단처럼 발급과 같은 행을 바꾸기 전에 쓴다.
     * 발급의 조건부 UPDATE는 이 트랜잭션이 끝날 때까지 기다리므로, 잠근 뒤 읽은 발급 수량으로 판단해도 된다.
     */
    Optional<Coupon> lockById(Long id);

    /** 삭제되지 않은 쿠폰이 있는지. 삭제가 0행일 때 404와 409를 가르는 데 쓴다. */
    boolean existsById(Long id);

    /**
     * 아무도 받지 않은 쿠폰만 논리 삭제한다. 바뀐 행 수(0 또는 1)를 돌려준다.
     * 조건 확인과 삭제가 UPDATE 한 번이라, 발급의 수량 UPDATE와 같은 행 잠금을 차례로 잡는다.
     * 삭제와 첫 발급이 동시에 들어와도 둘 중 하나만 성공한다.
     */
    int softDeleteIfNotIssued(Long id, String deletedBy, LocalDateTime deletedAt);
}
