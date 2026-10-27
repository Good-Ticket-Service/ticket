# 결제 내부 API

- 제공: 결제 (`payment-service`, 강현민)
- 소비: 예매 (`reservation-service` booking 모듈). 관리자 API는 운영자가 직접 부른다.
- 설계 근거: [payment/plan.md](../../payment/plan.md) 4절(처리 흐름), 5절(복구)

## 공통

- 변경 상태: #22 할인액 전달·쿠폰 책임 이전은 담당자 검토 대기다. 기존 합의 이력과 구분한다.

- #22: 쿠폰은 reservation-service가 처리한다. 결제는 쿠폰 ID를 받거나 CouponUsage를 호출하지 않고 예매가 확정한 정가·할인액·최종 금액만 검증·저장한다. 쿠폰 오류는 예매 책임이다.

- Gateway에 노출하지 않는다. 응답 본문을 `ApiResponse`로 감싸지 않고, 오류는 `ErrorResponse`다([conventions.md](../conventions.md)).
- 모두 `orderId` 기준으로 멱등하다. 같은 `orderId`의 같은 요청을 다시 보내면 처음 결과를 돌려준다.
- 업무 결과(승인·거절·결과 불명)는 HTTP 상태가 아니라 본문 `status`로 알린다. 4xx는 잘못된 요청과 아래에 적은 충돌에만 쓴다.
- `orderId`는 예매가 결제 준비 시도마다 새로 만든다(최대 64자). 같은 시도의 재요청에는 같은 값을 쓴다.
- ID는 모두 숫자(JSON number)다. 회원·판매자 ID도 회원 서비스와 같은 BIGINT다.
- 시각(DateTime)은 ISO 8601에 `+09:00`을 붙인다. 예: `2026-10-27T10:00:00+09:00`.
- 금액은 원 단위 정수(Integer)다.

### failureType

`status`가 `FAILED`이면 `failureType`과 `failureCode`가 함께 온다. 예매는 `failureType`만 보고 다음 행동을 고른다.

| failureType | failureCode | 예매가 할 일 |
|---|---|---|
| REJECTED | PG_REJECTED, AMOUNT_MISMATCH, INVALID_AUTH_TOKEN | 다른 수단으로 다시 결제 |
| UNAVAILABLE | PG_UNAVAILABLE, PG_BUSY, PG_UNRESOLVED | 잠시 후 같은 수단으로 다시 시도 |
| EXPIRED | EXPIRED | 선점이 끝났으면 만료, 아니면 다시 준비 |
| NOT_APPROVED | NOT_APPROVED, CANCELED_AT_PG | 결제 안 됨으로 확정. 다시 결제 가능 |
| CLOSED | SUPERSEDED, ABANDONED, READY_FAILED | 이 시도는 끝. 필요하면 새 준비 |

`PG_UNRESOLVED`는 PG 승인을 부른 뒤 1분 안에 결과를 알지 못해 결제가 이 시도를 끝냈다는 뜻이다. 예매는 다른 FAILED와 똑같이 처리한다. 나중에 PG 승인이 발견되면 결제는 FAILED 그대로 두고 스스로 전액 환불(`AUTO_VOID`)한다. 예매는 이 환불에 관여하지 않는다.

---

## POST /internal/payments — 결제 준비

- 상태: 합의 (Notion, 2026-10-26)
- 멱등성: `orderId` 기준. 같은 `orderId`·같은 내용이면 처음 응답을 그대로 돌려준다. 같은 예매의 이전 READY 결제는 실패(`SUPERSEDED`) 처리하고 새로 만든다.
- 재시도: 같은 `orderId`로 재시도해도 된다.

요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| orderId | String(64) | Y | 예매가 만든 시도 ID |
| reservationId | Long | Y | |
| userId | Long | Y | |
| sellerId | Long | Y | 정산 대상 판매자 |
| performanceId | Long | Y | |
| scheduleId | Long | Y | |
| totalPrice | Integer | Y | 할인 전 금액. 0보다 크다 |
| discountAmount | Integer | Y | 예매가 쿠폰 처리 후 확정한 할인액. 쿠폰 미사용은 0. 음수 불가 |
| pgProvider | String | Y | `TOSS`, `KAKAO` |
| expiresAt | DateTime | Y | 결제 허용 시각(좌석 선점 만료). 이 뒤에는 PG 승인을 부르지 않는다 |

응답 200

| 필드 | 타입 | 설명 |
|---|---|---|
| paymentId | Long | |
| orderId | String | |
| amount | Integer | `totalPrice - discountAmount`. 최소 100원이며 결제창에 넘긴다. 결제는 할인액을 보정하지 않는다 |
| status | String | `READY` / `FAILED` |
| failureType, failureCode | String | FAILED일 때 |
| checkoutUrl | String | 카카오 결제창 URL. 토스는 null |

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 409 | `PAYMENT_ORDER_CONFLICT` | 같은 orderId에 다른 내용. 할인액 변경도 포함한다 |
| 409 | `PAYMENT_ALREADY_IN_PROGRESS` | 같은 예매에 승인 진행 중(IN_PROGRESS, 또는 승인 요청을 받은 READY) |
| 409 | `PAYMENT_ALREADY_DONE` | 같은 예매가 이미 결제됨 |
| 409 | `PAYMENT_CHECKOUT_CONFLICT` | 같은 예매의 준비가 동시에 옴 |
| 400 | `INVALID_PAYMENT_AMOUNT` | 할인액이 음수이거나 최종 금액이 100원 미만 |
| 400 | `VALIDATION_ERROR` | 입력값 오류 (공통 코드) |

## POST /internal/payments/{orderId}/confirm — 결제 승인

- 상태: 합의 (Notion, 2026-10-26)
- 멱등성: `orderId` 기준. 이미 READY가 아니면 PG를 다시 부르지 않고 현재 상태를 돌려준다.
- 타임아웃: 최악 응답 약 14초. 예매 read timeout은 20초를 제안한다.
- 결과 확정 시한: PG 승인을 부른 뒤 1분 안에 결과를 모르면 결제가 FAILED(`PG_UNRESOLVED`)로 확정한다. 예매가 결과를 확인해 쿠폰을 반납한다. 그래서 `IN_PROGRESS`는 약 1분(결제 복구 주기 포함) 안에 끝난다.
- 재시도: 응답을 받지 못하면 바로 재전송하지 말고 결제 조회로 확인한다(아래 "결과를 모를 때").

요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| pgAuthToken | String | Y | 사용자 인증 결과. 토스 paymentKey, 카카오 pg_token |
| amount | Integer | Y | 결제창에 넘긴 금액. 저장 금액과 대조한다 |

응답 200

| 필드 | 타입 | 설명 |
|---|---|---|
| paymentId | Long | |
| status | String | `DONE` / `FAILED` / `IN_PROGRESS`(결과 불명, 1분 안에 확정된다). Kafka 전환 후에는 `ACCEPTED` |
| failureType, failureCode | String | FAILED일 때 |

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | `PAYMENT_AMOUNT_MISMATCH` | 금액이 저장 금액과 다름. PG를 부르지 않는다 |
| 404 | `PAYMENT_NOT_FOUND` | 준비 기록 없음 |

## GET /internal/payments/{orderId} — 결제 조회

- 상태: 합의 (Notion, 2026-10-26)
- 멱등성: 조회. 재시도해도 된다.

응답 200

| 필드 | 타입 | 설명 |
|---|---|---|
| paymentId, orderId, reservationId | | |
| status | String | `READY` / `IN_PROGRESS` / `DONE` / `FAILED` / `CANCELED` |
| amount | Integer | |
| failureType, failureCode | String | |
| confirmRequestedAt | DateTime | 승인 요청을 받은 시각. 예매가 재전송 여부를 판단한다 |
| approvedAt | DateTime | |
| refund | Object | `{status, reason, refundAmount, refundedAt}`. 없으면 null |

오류: 404 `PAYMENT_NOT_FOUND`.

## POST /internal/payments/{orderId}/refund — 환불 요청

- 상태: 합의 (Notion, 2026-10-26)
- 멱등성: `orderId` 기준. 이미 환불이 있으면 기존 환불을 돌려준다.
- 책임: 예매는 환불 완료를 기다리지 않는다. 환불을 끝까지 처리할 책임은 결제가 진다.

요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| reason | String | Y | `USER_CANCEL`(사용자 취소), `SCHEDULE_CANCELED`(회차 취소), `CONFIRM_FAILED`(결제는 됐는데 예매 확정 실패) |

응답 200

| 필드 | 타입 | 설명 |
|---|---|---|
| refundStatus | String | `WAITING` / `REQUESTED` / `DONE` / `FAILED` / `SKIPPED` / `NOT_REQUIRED` |
| refundAmount | Integer | |

결제 상태별 처리: READY는 결제 포기(`NOT_REQUIRED`), FAILED는 `NOT_REQUIRED`, IN_PROGRESS는 `WAITING`, DONE은 `REQUESTED` 후 PG 취소, CANCELED는 기존 환불을 돌려준다.

- 환불은 언제나 결제 금액 전액이다. 취소 가능 기간·수수료는 예매가 요청 전에 검사하고, 결제는 사유와 관계없이 검사하지 않는다. 회차 취소(`SCHEDULE_CANCELED`)는 예매가 취소 기간 검사 없이 예약마다 부른다.
- `NOT_REQUIRED`: 돌려줄 돈이 없다. READY면 결제를 FAILED(`ABANDONED`)로 끝낸다. 쿠폰 반납은 예매가 처리한다. 환불 행은 만들지 않는다. 예매는 환불이 끝난 것으로 보고 다음 상태로 넘어가며 다시 요청하지 않는다.
- 예매는 외부 환불 호출 전에 예매를 `CANCELING`으로 커밋하고, 요청이 접수되면 `CANCELED`로 바꾼다. 응답을 받지 못하면 예매 스케줄러가 같은 `orderId`로 다시 요청한다(멱등).

오류: 404 `PAYMENT_NOT_FOUND`.

## 관리자 API

- 상태: 합의 (Notion, 2026-10-26)
- 운영자가 PG 상태를 확인한 뒤 Gateway를 거치지 않고 서비스에 직접 부른다(curl, Postman). 예매는 부르지 않는다.
- 환불만 대상이다. 결제는 결과 확정 시한(1분)이 지나면 FAILED로 끝나므로 차단·관리자 확정이 없다.

| Method | URI | 요청 | 하는 일 |
|---|---|---|---|
| POST | `/internal/admin/payments/{orderId}/refund/unblock` | | 시간 초과로 차단된 REQUESTED 환불을 다시 자동 처리에 넣는다. 다른 상태면 409 `INVALID_STATUS_TRANSITION` |
| POST | `/internal/admin/payments/{orderId}/refund/retry` | | FAILED 환불을 새 PG 취소 키 세대로 REQUESTED로 되돌린다 |

---

## 예매가 결제 결과를 모를 때

승인 요청이나 그 응답이 중간에 끊기면 예매는 PAYMENT_IN_PROGRESS로 남는다. 예매는 `GET /internal/payments/{orderId}`로 조회하고 결과에 따라 마무리한다. 예매 복구 잡도 오래된 PAYMENT_IN_PROGRESS를 같은 표로 처리한다.

| 결제 결과 | 뜻 | 예매가 할 일 |
|---|---|---|
| DONE | 승인 확정 | CONFIRMED, 좌석 SOLD. 확정할 수 없으면 `CANCELING`으로 바꾼 뒤 `CONFIRM_FAILED` 환불 요청 |
| FAILED | 이 결제 시도는 끝났다. 결과 불명(`PG_UNRESOLVED`)으로 끝난 건의 늦은 승인은 결제가 자동 환불한다 | HOLD로 복귀 (선점 만료 후면 EXPIRED + 좌석 반환) |
| IN_PROGRESS | PG 결과를 모름. 결제가 승인 호출 후 1분 안에 DONE 또는 FAILED로 확정한다 | 기다리고 다음 주기에 다시 조회한다 |
| READY | 승인 요청이 결제에 도착하지 못했거나 아직 처리 전 | `confirmRequestedAt`이 없거나 1분 넘게 지났으면 저장한 pgAuthToken으로 같은 승인 요청을 다시 보낸다. READY만 보고 HOLD로 되돌리지 않는다(늦게 도착한 승인과 경쟁한다) |
| 404 | 준비가 기록되지 않았다 | 다시 준비한다 |

- 결제 서비스 장애로 조회가 안 되면 예매는 혼자 좌석을 풀지 않고 기다리며 알림을 남긴다. 결제가 DONE이었다면 돈만 빠지기 때문이다.

## 합의할 항목

결제 쪽 제안이다. 근거는 [payment/plan.md](../../payment/plan.md) 9절 "예매 쪽에 제안하는 보완"이다.

| # | 내용 | 상태 |
|---|---|---|
| 1 | 예매가 `orderId`를 먼저 기록(트랜잭션)한 뒤 결제 준비를 부른다 | 제안 |
| 2 | 환불 전에 예매를 `CANCELING`(티켓 사용 불가)으로 커밋하고, 환불 요청이 접수되면 `CANCELED`로 바꾼다 | 결정 (Notion 검토 3-5·3-7, 2026-10-26) |
| 3 | 결과를 모르는 동안(PAYMENT_IN_PROGRESS) 좌석을 해제하지 않는다 | 결정. 결제가 1분 안에 결과를 확정한다 (Notion 검토 3-3) |
| 4 | 승인 호출 read timeout 20초 (예매 문서는 15초) | 제안 |
| 5 | 준비 요청에 `pgProvider`를 넣는다 | 결정 (Notion 검토 3-5) |
| 6 | 재전송용 `pgAuthToken`은 확정·만료 후 지우고 로그·응답·이벤트에 싣지 않는다 | 제안 |

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 합의 내용 반영. payment/spec.md에서 옮김 |
| 2026-10-26 | Notion 검토 | 팀 결정 반영: 회원·판매자 ID Long, `couponId` → `userCouponId`, 결과 확정 시한 1분과 `PG_UNRESOLVED`, 결제 차단·`resolve`·`MANUAL` 제거, 관리자 차단 해제를 환불 전용(`refund/unblock`)으로, 시각 `+09:00`, 환불 사유·`NOT_REQUIRED` 처리, 취소 상태 `CANCELING`·`CANCELED` |
| 2026-10-27 | #22 (검토 대기) | 준비 요청 userCouponId를 discountAmount로 변경하고 최소 100원 검증, 할인액 멱등 비교, 쿠폰 처리의 예매 책임을 반영했다 |
