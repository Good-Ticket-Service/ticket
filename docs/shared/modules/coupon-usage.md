# CouponUsage (예매 → 쿠폰)

- 상태: 초안 (#22 책임 이전, 쿠폰 모듈 이동 이슈). 기존 결제 소비 계약을 대체하는 변경안이며 쿠폰·예매 담당자 검토가 필요하다.
- 제공: 쿠폰 (`reservation-service` coupon 모듈, 성윤범)
- 소비: 예매 (`reservation-service` booking 모듈, 지한얼)
- 쿠폰 테이블과 상태 전이: [coupon/tables.md](../../coupon/tables.md)

## 책임과 트랜잭션

쿠폰 예약·사용·반납·복원은 예매가 처리한다. CouponUsage는 예매의 로컬 트랜잭션에 합류한다(`REQUIRED`). 결제 DB와 쿠폰 DB를 한 트랜잭션으로 묶지 않는다. 결제는 쿠폰 테이블·인터페이스에 접근하지 않는다.

예매는 서버에서 쿠폰을 검증하고 할인액을 확정한 뒤 결제에 `totalPrice`, `discountAmount`를 보낸다. 클라이언트가 보낸 최종 금액을 그대로 신뢰하지 않는다. 최소 결제 금액 100원을 만족하는 할인액을 확정하고, 쿠폰 미사용은 0으로 보낸다.

예매 코드는 `p_user_coupons`를 직접 조회·수정하지 않고 이 인터페이스로만 쿠폰을 다룬다. 쿠폰을 별도 서비스로 빼게 되면 이 인터페이스의 구현만 Feign 어댑터로 바꾼다.

## 연결 키 (제안)

쿠폰은 결제 시도(`orderId`)가 아니라 **예매(`reservationId`)** 하나에 잡는다. `p_user_coupons.reservation_id`에 기록한다.

- 준비를 다시 해도(결제창 다시 열기, PG 변경, 실패 후 재시도) 같은 예매에 같은 쿠폰이 그대로 잡혀 있으므로 시도 세대를 구분할 필요가 없다.
- 덮이거나 만료된 `orderId`가 쿠폰을 붙잡는 문제가 생기지 않는다. 쿠폰은 예매가 끝날 때만 풀린다.

## 메서드 (제안)

| 메서드 | 예매가 부르는 때 | 쿠폰 상태 변화 |
|---|---|---|
| `reserve(userCouponId, userId, reservationId)` | 결제 준비에서 쿠폰을 고르거나 바꿀 때 (결제 준비 요청 전) | AVAILABLE → RESERVED, `reservation_id` 기록. 발급 시점 할인액을 돌려준다 |
| `use(reservationId)` | 결제 DONE을 확인해 예매를 확정할 때 (`PAYMENT_IN_PROGRESS → CONFIRMED`) | RESERVED → USED, `used_at` 기록 |
| `release(reservationId)` | 쿠폰 변경·빼기, 예매 만료·해제, 취소 확정 | RESERVED 또는 USED → 유효 기간이 남으면 AVAILABLE, 지났으면 EXPIRED. `reservation_id`·`used_at`을 비운다 |

- `reserve`: 본인 쿠폰이 아니거나 AVAILABLE이 아니거나 유효 기간이 지났으면 `COUPON_NOT_AVAILABLE`. 같은 예매에 같은 쿠폰이 이미 RESERVED면 그 할인액을 돌려준다(멱등). 이 오류는 예매 결제 준비 API가 돌려준다.
- `use`: 실패하지 않는다. 결제 승인 뒤 확정 트랜잭션에서 부르므로, RESERVED이고 `reservation_id`가 같으면 그사이 유효 기간이 지났어도 USED로 바꾼다.
- `release`: 잡힌 쿠폰이 없으면 아무것도 하지 않는다. 여러 번 불려도 안전하다.

## 예매가 처리할 시점

| 예매 전이 | 쿠폰 호출 |
|---|---|
| 결제 준비에서 다른 쿠폰을 고름 | 이전 쿠폰 `release` 뒤 새 쿠폰 `reserve` (같은 트랜잭션, 순서 지킴) |
| 결제 준비에서 쿠폰을 뺌 | `release` |
| 준비·승인 응답 유실 (`PAYMENT_IN_PROGRESS` 유지) | 부르지 않는다. 같은 `orderId` 조회·재요청으로 결과를 확인한다. 결과 불명인 쿠폰을 임의로 해제하지 않는다 |
| 결제 FAILED 확인, 시간 남음 (`PAYMENT_IN_PROGRESS → HOLD`) | 부르지 않는다. 쿠폰은 예매에 잡힌 채로 두고 같은 쿠폰으로 다시 결제한다 (제안) |
| 결제 FAILED 확인, 시간 지남 (`→ EXPIRED`), `HOLD → EXPIRED`, 선점 해제 | `release` (RESERVED 해제) |
| 결제 DONE 확인 (`PAYMENT_IN_PROGRESS → CONFIRMED`) | `use` |
| 취소 확정 (`CANCELING → CANCELED`, 결제가 환불 요청 접수) | `release` (USED 복원) (제안. 환불 완료를 예매가 따로 알 수 없으므로 접수 시점에 복원한다) |
| 늦은 승인 AUTO_VOID, 끝난 예매에 도착한 결제 성공의 자동 환불 | 부르지 않는다. 쿠폰은 예매가 끝날 때 이미 처리했다 |

## 쿠폰 쪽이 지킬 것

- RESERVED는 만료 배치·관리 작업이 임의로 해제하지 않는다. RESERVED는 예매가 끝날 때(`use`·`release`)만 풀린다. 이 약속이 깨지면 결제가 승인된 예매의 확정이 롤백된다.
- 할인액은 발급 시점 복사본(`p_user_coupons.discount_amount`)을 쓴다.
- 같은 요청 재실행, 중복 폴링, 서로 다른 시도의 늦은 응답에도 상태 변경은 멱등하다. 키가 `reservationId`이므로 시도마다 다른 결과가 오지 않는다.

## 남은 일

- 결제 결과 반영 실패와 취소 중 응답 유실을 재시도할 복구 경로는 예매가 가진다(예매 결과 확인 스케줄러, CANCELING 재요청).
- 실제 쿠폰 모듈·테이블·소비자 이전과 예매 호출 구현은 각 담당 이슈 범위다.

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 결제 소비 계약으로 reserve·use·release 3개를 합의했다 |
| 2026-10-27 | #22 | 소비자를 booking으로 옮기고 트랜잭션·복구 책임을 정리했다. 새 연결 키·메서드 인자·복원 시점은 담당자 검토 대기다 |
| 2026-10-27 | 쿠폰 모듈 이동 이슈 | 연결 키를 `reservationId`로, 메서드 인자와 예매 전이별 호출, 결제 실패 후 쿠폰 유지, 취소 확정 시 복원을 제안했다 |
