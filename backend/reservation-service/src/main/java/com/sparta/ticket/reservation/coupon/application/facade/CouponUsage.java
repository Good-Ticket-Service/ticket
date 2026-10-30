package com.sparta.ticket.reservation.coupon.application.facade;

/**
 * booking이 부르는 coupon의 진입점(#125). booking은 회원 쿠폰 테이블을 직접 읽거나 바꾸지 않고 이 인터페이스로만 쿠폰을 잡고·쓰고·돌려준다.
 * HTTP API가 아니라 같은 서비스 안의 메서드 호출이라, 컨트롤러를 거치지 않고 application 계층으로 바로 들어온다.
 * 모든 메서드는 전파 REQUIRED로 호출자(예매)의 트랜잭션에 합류한다. 예매가 롤백되면 쿠폰 상태도 함께 롤백된다.
 * 잠금 순서는 좌석 → 예약 → 쿠폰이다. booking은 예약 행을 먼저 잠근 뒤 이 메서드를 부른다.
 * 예매 스케줄러가 같은 예약으로 다시 부를 수 있어 모든 메서드는 멱등이다.
 */
public interface CouponUsage {

    /**
     * 회원 쿠폰을 이 예매에 잡고(AVAILABLE → RESERVED) 할인 정보를 돌려준다. 결제 준비에서 회원이 쿠폰을 골랐을 때 부른다.
     * 할인액은 발급 시점 값 그대로다. 최소 결제 금액에 맞춰 자르는 일은 booking이 한다(쿠폰은 정가를 모른다).
     * 없는 쿠폰이거나, 본인 쿠폰이 아니거나, 쓸 수 있는 상태가 아니거나, 유효 기간이 지났으면 COUPON_NOT_AVAILABLE(400)이다.
     * 같은 예매에 같은 쿠폰이 이미 잡혀 있으면 그 값을 돌려준다. 이 예매에 다른 쿠폰이 잡혀 있으면 IllegalStateException이다.
     * 쿠폰을 바꾸려면 booking이 같은 트랜잭션에서 release를 먼저 부른다.
     */
    CouponDiscount reserve(Long userCouponId, Long userId, Long reservationId);

    /**
     * 이 예매에 잡힌 쿠폰을 사용 확정한다(RESERVED → USED). 결제가 성공해 예매가 확정될 때 부른다.
     * 잡힌 쿠폰이 없거나 이미 USED면 아무것도 하지 않는다.
     */
    void use(Long reservationId);

    /**
     * 이 예매에 잡힌 쿠폰을 돌려준다(RESERVED·USED → AVAILABLE, 유효 기간이 지났으면 EXPIRED).
     * 선점 만료·해제와 예매 취소 확정 때 부른다. 잡힌 쿠폰이 없거나 이미 돌려줬으면 아무것도 하지 않는다.
     */
    void release(Long reservationId);
}
