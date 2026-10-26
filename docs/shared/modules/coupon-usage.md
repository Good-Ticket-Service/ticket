# CouponUsage (결제 → 쿠폰)

- 상태: 합의 (Notion 쿠폰 테이블 명세, 2026-10-26)
- 제공: 쿠폰 (`payment-service` coupon 모듈, 성윤범)
- 소비: 결제 (`payment-service` payment 모듈, 강현민)
- 쿠폰 테이블과 상태 전이: [coupon/tables.md](../../coupon/tables.md)

같은 서비스 안의 모듈 간 계약이다. 결제 코드는 `p_user_coupons`를 직접 조회·수정하지 않고 이 인터페이스로만 쿠폰을 다룬다. 쿠폰을 별도 서비스로 빼게 되면 이 인터페이스의 구현만 Feign 어댑터로 바꾼다.

## 트랜잭션

`CouponUsage`는 호출한 쪽 트랜잭션에 합류한다(`REQUIRED`). 결제 상태와 쿠폰 상태가 한 트랜잭션에 커밋된다.

## 메서드

| 메서드 | 결제가 부르는 때 | 쿠폰 상태 변화 |
|---|---|---|
| `reserve(userCouponId, userId, paymentId)` | 결제 준비 | AVAILABLE → RESERVED, `payment_id` 기록. 발급 시점 할인액을 돌려준다 |
| `use(paymentId)` | PG 승인 성공 | RESERVED → USED, `used_at` 기록 |
| `release(paymentId)` | 결제 FAILED 전이(승인 전 종료), 환불 DONE | RESERVED 또는 USED → 유효 기간이 남으면 AVAILABLE, 지났으면 EXPIRED. `payment_id`·`used_at`을 비운다 |

- `reserve`: 본인 쿠폰이 아니거나 AVAILABLE이 아니면 `COUPON_NOT_AVAILABLE`. 결제는 PG를 부르지 않는다.
- `use`: 실패하지 않는다. PG 승인이 끝난 뒤에 부르므로, RESERVED이고 `payment_id`가 같으면 그사이 유효 기간이 지났어도 USED로 바꾼다.
- `release`: 잡힌 쿠폰이 없으면 아무것도 하지 않는다. 여러 번 불려도 안전하다.

## 결제 쪽이 지킬 것

`release` 하나가 승인 전 해제와 환불 복원을 모두 처리하므로, 잘못된 때에 부르는 실수는 쿠폰이 막지 않는다. 결제 코드가 아래만 부른다.

| 결제 전이 | 쿠폰 호출 |
|---|---|
| READY·IN_PROGRESS → FAILED | `release` (RESERVED 해제) |
| IN_PROGRESS → DONE | `use` |
| 환불 DONE (사유가 AUTO_VOID가 아닐 때) | `release` (USED 복원) |
| 환불 DONE (AUTO_VOID) | 부르지 않는다. 그 결제는 FAILED가 될 때 이미 `release`했다 |
| DONE 결제의 그 밖의 전이 | 부르지 않는다 |

## 쿠폰 쪽이 지킬 것

- RESERVED 쿠폰은 만료 배치·관리 작업이 바꾸지 않는다. RESERVED는 결제가 끝날 때(`use`·`release`)만 풀린다. 이 약속이 깨지면 승인된 결제의 확정이 롤백된다.
- 할인액은 발급 시점 복사본(`p_user_coupons.discount_amount`)을 쓴다.

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 초안. payment/plan.md 9절에서 옮김 (메서드 4개) |
| 2026-10-26 | #3 | 쿠폰 명세에 맞춰 메서드 3개(`reserve`·`use`·`release`)로 합의 |
