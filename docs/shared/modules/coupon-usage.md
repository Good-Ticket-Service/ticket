# CouponUsage (결제 → 쿠폰)

- 상태: 초안
- 제공: 쿠폰 (`payment-service` coupon 모듈)
- 소비: 결제 (`payment-service` payment 모듈)
- 설계 근거: [payment/plan.md](../../payment/plan.md) 9절, [payment/decisions.md](../../payment/decisions.md)

같은 서비스 안의 모듈 간 계약이다. 결제 코드는 `user_coupon`을 직접 조회·수정하지 않고 이 인터페이스로만 쿠폰을 다룬다. 쿠폰을 별도 서비스로 빼게 되면 이 인터페이스의 구현만 Feign 어댑터로 바꾼다.

## 트랜잭션

`CouponUsage`는 호출한 쪽 트랜잭션에 합류한다(`REQUIRED`). 결제 상태와 쿠폰 상태가 한 트랜잭션에 커밋된다.

## 메서드

| 메서드 | 동작 | 실패 |
|---|---|---|
| `reserve(couponId, userId, paymentId, totalPrice)` | AVAILABLE이고 본인 쿠폰이면 RESERVED + payment_id, 할인액 반환 | 사용 불가면 `COUPON_NOT_AVAILABLE` (결제는 PG를 부르지 않는다) |
| `use(paymentId)` | RESERVED → USED | RESERVED이고 payment_id가 같으면 실패하지 않는다. 그사이 유효 기간이 지났어도 USED로 바꾼다. 잡힌 쿠폰이 없으면 아무것도 하지 않는다 |
| `releaseReservation(paymentId)` | RESERVED → AVAILABLE. 승인 전에 끝난 결제(FAILED 전부)의 점유 해제 | RESERVED만 받는다. USED가 들어오면 예외(승인된 결제의 쿠폰을 되살리는 버그를 드러낸다). 잡힌 쿠폰이 없으면 아무것도 하지 않는다 |
| `restoreAfterRefund(paymentId, reason)` | USED → 환불 정책대로 (AVAILABLE / EXPIRED / USED 유지) | USED만 받는다. 정책은 이 메서드 한 곳에만 둔다. 잡힌 쿠폰이 없으면 아무것도 하지 않는다 |

- 승인 전 점유 해제와 결제 후 환불 복원은 다른 업무다. 앞의 것은 항상 되돌리고, 뒤의 것은 팀 정책과 정산(할인 부담 주체)에 따른다. 그래서 메서드를 나눠 각자 받는 상태를 하나로 좁힌다.
- AUTO_VOID 환불은 `restoreAfterRefund`를 부르지 않는다. 그 결제는 DONE이 된 적이 없고, FAILED가 될 때 이미 `releaseReservation`으로 쿠폰을 돌려줬다.

## 쿠폰 쪽에 필요한 것

| 요청 사항 | 이유 |
|---|---|
| `user_coupon.status`에 `RESERVED` | PG 호출 전에 쿠폰을 잡아 동시 사용을 막는다 |
| `user_coupon.payment_id BIGINT NULL` | 잡은 결제. `use`·`releaseReservation`·`restoreAfterRefund`가 결제 ID로 쿠폰을 찾는다 |
| 만료 배치·관리 작업이 RESERVED 쿠폰을 바꾸지 않는다 | RESERVED는 결제가 끝날 때(`use`·`releaseReservation`)만 풀린다. 이 약속이 깨지면 승인된 결제의 확정이 롤백된다 |

`payments.user_coupon_id`와 `user_coupon.payment_id`는 같은 스키마지만 소유자가 달라 FK를 두지 않고 값만 저장한다.

## 합의할 항목

1. 환불 시 쿠폰 복원 정책 (`restoreAfterRefund`의 결과: AVAILABLE / EXPIRED / USED 유지)
2. 할인액의 정산 반영 (할인 부담 주체)

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 초안. payment/plan.md 9절에서 옮김 |
