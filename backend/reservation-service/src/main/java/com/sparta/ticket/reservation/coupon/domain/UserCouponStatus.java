package com.sparta.ticket.reservation.coupon.domain;

/**
 * 회원이 받은 쿠폰 한 장의 상태. 이 한 장을 쓸 수 있는지를 뜻한다. 쿠폰을 받을 수 있는 기간은 CouponIssueStatus다.
 * 발급하면 AVAILABLE이고, 예매에 잡고·쓰고·돌려주는 전이는 예매 연동(CouponUsage, #125)이 맡는다.
 * 저장된 AVAILABLE을 EXPIRED로 바꾸는 만료 배치는 아직 구현 전이다.
 * <pre>
 * AVAILABLE ──reserve(예매에 잡힘)──▶ RESERVED ──use(결제 성공)──▶ USED
 *     ▲                                 │                           │
 *     └──release(예매 종료·취소, 기간이 지났으면 EXPIRED)───────────┘
 * AVAILABLE ──유효 기간 지남(만료 배치)──▶ EXPIRED
 * </pre>
 * Note: 만료 배치 전이라도 화면에는 유효 기간이 지난 AVAILABLE을 EXPIRED로 보여 준다(UserCoupon.displayStatus).
 */
public enum UserCouponStatus {
    /** 사용할 수 있다. 유효 기간(issuedAt 이상 expiresAt 미만) 안에서만 쓸 수 있다. */
    AVAILABLE,
    /** 예매에 잡혀 있다(reservationId 있음). 결제가 끝나면 USED가 된다. 예매가 잡고 있어 기간이 지나도 만료로 보지 않는다. */
    RESERVED,
    /** 결제가 확정돼 사용했다(reservationId·usedAt 있음). */
    USED,
    /** 유효 기간이 지나 쓸 수 없다. 만료 배치가 AVAILABLE을 이 상태로 바꾼다. */
    EXPIRED
}
