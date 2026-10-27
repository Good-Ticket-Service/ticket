# 결제 API 명세

- 상태: 작성 완료
- 담당: 강현민

## 1. 외부 API

결제 서비스는 외부 API(`/api/v1/**`)를 두지 않는다. 사용자의 결제 준비·승인·취소 요청은 예매 서비스가 받아 결제 내부 API를 부른다.

| Method | URI | 소유 | 결제 쪽 동작 |
|---|---|---|---|
| POST | `/api/v1/reservations/{id}/payments/checkout` | 예매 | 결제 준비 |
| POST | `/api/v1/reservations/{id}/payments/confirm` | 예매 | 결제 승인 |
| POST | `/api/v1/reservations/{id}/cancel` | 예매 | 환불 요청 |

경로와 응답 형식은 예매 담당이 정한다. 위 표는 참고용이다.

## 2. 내부 API

서비스 간 계약이라 [shared/internal-api/payment.md](../shared/internal-api/payment.md)에 둔다. 결제 준비·승인·조회·환불 요청 4개와 환불 관리자 API 2개(차단 해제, 재시도)가 있다.

## 3. fake PG API (테스트용, 포트 8090)

결제 테스트·부하 실험용 PG 서버(`backend/fake-pg`)의 API다. 토스와 카카오를 흉내 낸다. 결제 서비스의 PG 어댑터와 k6 시나리오가 부른다. Gateway에 노출하지 않는다.

### 토스 호환 API

모두 `Authorization: Basic base64("{시크릿 키}:")`가 필요하다(로컬 기본 키 `test_sk_fake_toss`). 틀리면 401 `UNAUTHORIZED_KEY`. 오류 본문은 `{code, message}`.

| Method | URI | 요청 | 응답 |
|---|---|---|---|
| POST | `/v1/payments/confirm` | 헤더 `Idempotency-Key`(선택), 본문 `{paymentKey, orderId, amount}` | 200 Payment 객체(`status: DONE`) |
| GET | `/v1/payments/orders/{orderId}` | | 200 Payment 객체 |
| GET | `/v1/payments/{paymentKey}` | | 200 Payment 객체 |
| POST | `/v1/payments/{paymentKey}/cancel` | 헤더 `Idempotency-Key`(선택), 본문 `{cancelReason, cancelAmount?}` | 200 Payment 객체(`status: CANCELED`, `cancels`) |

Payment 객체 필드: `mId`, `version`, `paymentKey`, `type`, `orderId`, `orderName`, `currency`, `method`, `totalAmount`, `balanceAmount`, `status`(READY / DONE / CANCELED / ABORTED / EXPIRED), `requestedAt`, `approvedAt`, `cancels[{cancelAmount, cancelReason, canceledAt, transactionKey}]`. 시각은 `+09:00` ISO 8601.

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 주문번호·금액 불일치, 부분 취소, 입력값 오류 |
| 400 | `ALREADY_PROCESSED_PAYMENT` | READY가 아닌 결제 승인 |
| 400 | `ALREADY_CANCELED_PAYMENT` | 이미 취소됨 |
| 403 | `REJECT_CARD_PAYMENT` | 승인 거절 (장애 `failRate`) |
| 403 | `NOT_CANCELABLE_PAYMENT` | 승인 전 결제 취소, 취소 거절 (장애 `failRate`) |
| 404 | `NOT_FOUND_PAYMENT` | 없는 결제 조회·취소 |
| 404 | `NOT_FOUND_PAYMENT_SESSION` | 없거나 만료된 paymentKey로 승인 |
| 409 | `IDEMPOTENT_REQUEST_PROCESSING` | 같은 멱등 키 처리 중 |
| 422 | `INVALID_IDEMPOTENCY_KEY` | 같은 멱등 키·다른 내용 |
| 429 | `TOO_MANY_REQUESTS` | 초당 처리 한도 초과 (처리 안 함) |
| 500 | `FAILED_INTERNAL_SYSTEM_PROCESSING` | 처리 안 한 5xx, 또는 응답 유실·지연 승인 |

### 카카오 호환 API

카카오페이 단건 결제 형식이다. 모두 `Authorization: SECRET_KEY {시크릿 키}`가 필요하고(로컬 기본 키 `DEV_fake_kakao`), 본문은 snake_case JSON이다. `cid`는 테스트 가맹점 코드 `TC0ONETIME`만 받는다. 멱등 키가 없고 전액 취소만 지원한다. 시각은 오프셋 없는 한국 시간이다.

| Method | URI | 요청 | 응답 |
|---|---|---|---|
| POST | `/online/v1/payment/ready` | `{cid, partner_order_id, partner_user_id, item_name, quantity, total_amount, tax_free_amount, approval_url, cancel_url, fail_url}` | 200 `{tid, next_redirect_pc_url, next_redirect_mobile_url, next_redirect_app_url, android_app_scheme, ios_app_scheme, created_at}`. 같은 주문을 다시 준비하면 새 tid를 발급하고 이전 tid는 쓸 수 없다 |
| POST | `/online/v1/payment/approve` | `{cid, tid, partner_order_id, partner_user_id, pg_token}` | 200 결제 객체 |
| POST | `/online/v1/payment/order` | `{cid, tid}` | 200 결제 객체. 주문번호로는 조회할 수 없다 |
| POST | `/online/v1/payment/cancel` | `{cid, tid, cancel_amount, cancel_tax_free_amount}` | 200 결제 객체(`status: CANCEL_PAYMENT`) |

결제 객체 필드: `aid`, `tid`, `cid`, `status`, `partner_order_id`, `partner_user_id`, `payment_method_type`, `amount`, `canceled_amount`, `cancel_available_amount`(금액 객체 `{total, tax_free, vat, point, discount}`), `item_name`, `quantity`(항상 1), `created_at`, `approved_at`, `canceled_at`.

| 거래 상태 | 카카오 `status` | 뜻 |
|---|---|---|
| READY (pg_token 없음) | `READY` | 준비만 됨. 사용자 승인 전 |
| READY (pg_token 있음) | `AUTH_PASSWORD` | 사용자 승인 끝. 승인 요청 전 |
| DONE | `SUCCESS_PAYMENT` | 승인 완료 |
| CANCELED | `CANCEL_PAYMENT` | 전액 취소 |
| ABORTED | `FAIL_PAYMENT` | 승인 거절 |
| EXPIRED | `QUIT_PAYMENT` | 인증 유효 시간 안에 승인되지 않음 |

오류 본문은 `{error_code, error_message}`다. 코드값은 fake PG가 정한 대응이라 실제 카카오와 다를 수 있다. payment의 카카오 어댑터는 이 표로 분류하고, 샌드박스 검증 때 맞춘다.

| 상태 | error_code | 때 |
|---|---|---|
| 400 | -2 | 입력값 오류, 등록되지 않은 cid, 주문번호·회원 ID·pg_token 불일치, 부분 취소, 다른 금액·회원으로 재준비 |
| 400 | -701 | 사용자 승인 전 승인 요청 |
| 400 | -702 | 이미 승인됐거나 끝난 결제의 승인·재준비 |
| 400 | -703 | 없거나 만료된 tid로 승인·사용자 승인 |
| 400 | -704 | 없는 결제 조회·취소 |
| 400 | -721 | 이미 취소된 결제 재취소 |
| 400 | -722 | 승인 전 결제 취소, 취소 거절 (장애 `failRate`) |
| 400 | -780 | 승인 거절 (장애 `failRate`) |
| 401 | -401 | 시크릿 키 틀림 |
| 429 | -429 | 초당 처리 한도 초과 (처리 안 함) |
| 500 | -500 | 처리 안 한 5xx, 또는 응답 유실·지연 승인 |

멱등 키가 없으므로 응답 유실 뒤 같은 요청을 다시 보내면 승인은 -702, 취소는 -721이 난다. 어댑터는 다시 보내기 전에 조회로 결과를 확인한다.

### 결제 인증 시뮬레이션 (실제 토스에는 없음)

| Method | URI | 요청 | 응답 |
|---|---|---|---|
| POST | `/fake/toss/checkout` | `{orderId, amount, orderName?}` | 200 `{paymentKey, orderId, amount, authExpiresAt}`. 같은 주문을 인증 유효 시간 안에 다시 부르면 같은 paymentKey |

### 사용자 승인 시뮬레이션 (실제 카카오에는 없음)

카카오 결제창에서 사용자가 비밀번호로 인증하고 approval_url로 pg_token을 받는 단계를 대신한다. 오류는 카카오 형식이다.

| Method | URI | 요청 | 응답 |
|---|---|---|---|
| POST | `/fake/kakao/approve` | `{tid}` | 200 `{tid, partner_order_id, pg_token}`. 같은 tid를 다시 부르면 같은 pg_token |

### 관리 API

| Method | URI | 요청 | 하는 일 |
|---|---|---|---|
| GET | `/fake/admin/faults` | | 걸린 장애 목록 (`"TOSS.CONFIRM"`, `"KAKAO.READY"` 등) |
| GET | `/fake/admin/faults/{provider}/{operation}` | | 한 API의 장애 설정 |
| PUT | `/fake/admin/faults/{provider}/{operation}` | 장애 설정(아래) | 즉시 반영. `provider`: `TOSS`, `KAKAO`. `operation`: `READY`(카카오 준비만), `CONFIRM`, `INQUIRE`, `CANCEL` |
| DELETE | `/fake/admin/faults` | | 모든 장애 해제 (204) |
| GET | `/fake/admin/transactions?orderId=&status=&limit=` | | 거래 기록 (최신순, 최대 1000) |
| DELETE | `/fake/admin/transactions` | | 거래·멱등 기록 초기화 (204) |

장애 설정 (비운 항목은 장애 없음, `holdMs`만 기본 15000)

| 필드 | 타입 | 범위 | 동작 |
|---|---|---|---|
| latencyMs | Long | ≥ 0 | 처리 전 지연 |
| serverErrorRate | Double | 0~1 | 처리하지 않고 500 |
| failRate | Double | 0~1 | 승인 거절 / 취소 불가 (403) |
| responseLossRate | Double | 0~1 | 처리·기록 후 응답만 holdMs 붙잡았다가 500 |
| delayedApprovalRate | Double | 0~1 | 승인 기록을 delayedApprovalMs 뒤로 미루고 응답은 붙잡았다가 500 (승인만) |
| delayedApprovalMs | Long | ≥ 0 | 지연 승인까지의 시간 |
| holdMs | Long | ≥ 0 | 응답을 붙잡는 시간. 클라이언트 read timeout보다 길게 |
| rateLimitPerSecond | Integer | ≥ 0 | 초당 처리 한도. 0은 무제한 |
