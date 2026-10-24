# 결제·환불(payment-service) 구현 계획

공연 좌석 예매 MSA에서 결제·환불을 맡는다. 1차 MVP(10/27~11/6)는 서비스 간 통신을 동기(Feign)로 구현하고, 트러블슈팅 기간(11/9~11/16)에 부하·장애 실험으로 한계를 잰 뒤 Kafka 비동기로 개선해 전후를 수치로 비교한다. 이후 대사·정산으로 확장한다.

| 문서 | 내용 | 읽는 때 |
|---|---|---|
| plan.md (이 문서) | 무엇을 어떻게 만드는가: 구조, 상태, 흐름, 복구, 구현 규칙, ERD, API | 구현 전 항상 |
| [todo.md](todo.md) | 작업 순서와 완료 조건 | 작업을 고를 때 |
| [decisions.md](decisions.md) | 설계 결정의 이유와 대안 | 규칙의 이유가 궁금할 때, 규칙을 바꾸고 싶을 때 |
| [experiments.md](experiments.md) | 부하·장애 실험 시나리오와 메트릭 | Task 11 이후 |

## 1. 목표와 범위

| 구분 | 목표 |
|---|---|
| 정합성 | 이중 결제 0, 중복 환불 0, "돈은 빠졌는데 티켓·환불 없음" 0. 장애 주입으로 숫자로 증명한다 |
| 장애 격리 | PG 지연·장애가 결제와 무관한 기능으로 번지지 않는다 |
| 처리량 | PG 처리 한도를 넘는 오픈 결제 폭주를 Kafka로 평탄화한다 |

| 1차 MVP | 트러블슈팅 기간 | 확장 (여유 시) |
|---|---|---|
| fake-pg, 토스·카카오 어댑터, 멱등 결제·환불, 결과 불명 복구, 쿠폰 사용 예약, 메트릭, 동기 기준선 측정 | 장애 격리, Kafka 부하 평탄화, Kafka 결과 이벤트(Outbox), 대량 환불 | 대사, 정산, 샌드박스 실연동 |

범위 밖: 부분 환불·취소 수수료, PG 자동 전환, 판매자 지급(송금).

## 2. 구조: 바꿔 끼울 수 있는 세 축

application 계층은 포트(인터페이스)만 알고, infrastructure의 어댑터가 Feign·Kafka·PG를 다룬다. `LayeredArchitectureRules`(ArchUnit)가 이 방향을 강제한다.

| 축 | 포트 | 1차 어댑터 | Kafka 전환 후 |
|---|---|---|---|
| PG (어느 PG로) | `PgClient` | `TossPgClient`, `KakaoPgClient` | 같음. 샌드박스는 base-url만 교체 |
| 실행 시점 (언제) | `PaymentDispatcher` | `InlinePaymentDispatcher`: 요청 스레드에서 바로 | `KafkaPaymentDispatcher`: 워커가 처리 한도 안에서 |
| 결과 알림 (어떻게) | `PaymentResultPublisher` | `NoopResultPublisher`: HTTP 응답이 결과 | `KafkaResultPublisher`: Outbox |

결제는 PG 구조를 따라 **준비 → 사용자 인증 → 승인** 두 번의 요청으로 나뉜다(4절). 준비(`PaymentService.checkout`)와 승인 접수(`PaymentService.confirm`)는 요청 스레드에서 끝나고, PG 승인(`PaymentProcessor.process`)은 Dispatcher를 거친다. `dispatch`는 `void`다. Kafka 구현은 결과를 즉시 돌려줄 수 없으므로, 결과는 접수 후 최신 상태를 다시 읽어 얻는다.

```
InternalPaymentController ─┬─▶ PaymentService.checkout ─▶ PgClient.ready            (READY 저장)
                           └─▶ PaymentService.confirm ─▶ dispatcher.dispatch ─▶ PaymentProcessor.process ─▶ PgClient.approve
PaymentCommandConsumer ─────────────────────────────────▲ (Kafka 전환 후)           (PG 승인, 결과 저장)      └─▶ resultPublisher
```

구현체는 설정으로 고른다. 동기·Kafka 비교를 같은 코드로 돌리기 위해서다.

```yaml
payment:
  dispatch: inline          # inline | kafka
  result-publish: none      # none | kafka
  recovery:
    enabled: true           # 테스트에서 끄고 직접 호출한다
    delay-ms: 5000
    batch-size: 50
    stale-seconds: 30       # 이보다 오래된 IN_PROGRESS가 복구 대상. READY는 expires_at 기준
    max-attempts: 20
pg:
  toss.base-url: http://fake-pg:8090
  kakao.base-url: http://fake-pg:8090
```

### 패키지

```
com.sparta.ticket.payment
├── presentation/internal   InternalPaymentController, InternalRefundController
├── application
│   ├── PaymentService, PaymentProcessor, RefundService   흐름 지휘. 트랜잭션 없음
│   ├── PaymentTransactions, RefundTransactions           @Transactional 메서드만. 상태 전이 하나 = 메서드 하나
│   ├── PaymentRecoveryJob, RefundRecoveryJob             @Scheduled. 흐름 클래스를 부른다
│   ├── port/   PgClient, PaymentDispatcher, PaymentResultPublisher
│   ├── dto/    PaymentCommand, PaymentResult, RefundCommand, RefundResult
│   └── coupon/ CouponUsage 와 구현                          쿠폰 담당 소유. 결제는 CouponUsage만 부른다
├── domain      Payment, Refund, PaymentStatus, RefundStatus, PgProvider, PgResult, 저장소 인터페이스
│   └── coupon/ UserCoupon 등                                쿠폰 담당 소유
└── infrastructure
    ├── pg/      toss/, kakao/, PgClientRegistry
    ├── dispatch/  InlinePaymentDispatcher (Kafka 전환 후 KafkaPaymentDispatcher)
    └── messaging/ NoopResultPublisher (Kafka 전환 후 KafkaResultPublisher, PaymentCommandConsumer)
```

`PgClient`는 `ready`, `approve`, `inquire`, `cancel`을 가진다. `ready`는 카카오만 PG를 부르고(tid, 결제창 URL 발급), 토스는 PG를 부르지 않고 결제창에 넘길 값만 돌려준다. 결제창은 클라이언트가 띄운다.

`PgResult`는 sealed interface다: `Approved(transactionId, approvedAt)`, `Rejected(code, message)`, `Unknown(reason)`. 취소 결과도 같은 세 가지다.

## 3. 상태

| payment | 의미 | 다음 |
|---|---|---|
| READY | 준비됨. 금액·쿠폰이 확정됐고 사용자 인증을 기다린다. PG 승인은 아직 부르지 않았다 | IN_PROGRESS, FAILED |
| IN_PROGRESS | PG 승인을 불렀고 결과를 모른다. 복구 재개 지점이다 | DONE, FAILED |
| DONE | 승인 확정. 금액·PG 정보는 바뀌지 않는다 | CANCELED |
| FAILED | 거절 확정 (종료) | - |
| CANCELED | 전액 환불 완료 (종료) | - |

| refund | 의미 | 다음 |
|---|---|---|
| REQUESTED | PG 취소를 불렀거나 부를 차례이고 결과를 모른다. 복구 재개 지점이다 | DONE, FAILED |
| DONE | 취소 확정. 같은 트랜잭션에서 payment CANCELED (종료) | - |
| FAILED | PG가 취소를 거절했다. 같은 멱등 키로 다시 REQUESTED가 될 수 있다 | REQUESTED |

- 상태 전이는 엔티티 메서드(`markInProgress`, `approve`, `reject`, `cancel` 등)에서만 일어난다. 메서드는 현재 상태를 검사하고 맞지 않으면 공통 `INVALID_STATUS_TRANSITION`을 던진다.
- 결과 불명과 재시도 한도 소진은 상태가 아니라 `recovery_attempts`, `recovery_blocked`, `last_error`로 표현한다. 소진된 결제는 IN_PROGRESS 그대로 `recovery_blocked = true`가 되어 사람이 확인한다. 실제로 승인됐을 수 있는 결제를 FAILED로 확정하지 않기 위해서다.
- Orders의 `recovery_phase`는 두지 않는다. 외부 호출 단계가 결제는 승인 하나, 환불은 취소 하나라 상태 자체가 재개 지점이다.

## 4. 처리 흐름

### 결제 (1차, reservation이 지휘)

토스와 카카오 모두 사용자 인증 결과(토스 `paymentKey`, 카카오 `pg_token`)가 있어야 승인할 수 있다. 그래서 준비와 승인을 따로 요청한다.

```
① 준비  사용자 → reservation: POST /api/v1/reservations/{id}/payments/checkout {pgProvider, userCouponId}
        reservation: HOLD 확인, 새 orderId 저장 → POST /internal/payments
        payment checkout:
          1) order_id로 기존 결제 조회. 있으면 내용 비교 후 재현(같음) / 409(다름)
          2) tx prepare: 같은 예매의 이전 READY를 FAILED(SUPERSEDED) + 쿠폰 복구
                         새 Payment READY(금액 확정, expires_at) + 쿠폰 AVAILABLE→RESERVED 커밋
             동시 요청이 UK에 걸리면 이긴 행을 다시 읽어 1)처럼 재현한다
          3) PgClient.ready (트랜잭션 밖. 카카오만 PG 호출)
             카카오 성공 → tx: tid 저장 / 실패·불명 → tx: FAILED(READY_FAILED) + 쿠폰 복구
        응답: orderId, amount, status, (카카오) 결제창 URL
② 인증  사용자가 결제창에서 인증한다. fake-pg에서는 "결제창 대체" API가 대신한다
③ 승인  사용자 → reservation: POST /api/v1/reservations/{id}/payments/confirm {pgAuthToken, amount}
        reservation: 선점 만료 확인, HOLD → PAYMENT_IN_PROGRESS → POST /internal/payments/{orderId}/confirm
        payment confirm:
          4) 이미 READY가 아니면 현재 상태를 재현한다
          5) 요청 amount가 저장된 amount와 다르면 400 PAYMENT_AMOUNT_MISMATCH (상태 유지, PG 호출 없음)
          6) dispatcher.dispatch(paymentId, pgAuthToken)
        payment process:
          7) tx markInProgress: 잠금 후 READY이고 expires_at 전일 때만 IN_PROGRESS
             만료됐으면 FAILED(EXPIRED) + 쿠폰 복구, PG를 부르지 않는다
          8) PgClient.approve (트랜잭션 밖)
          9) tx complete: 잠금 후 IN_PROGRESS일 때만
             Approved → DONE + 쿠폰 USED / Rejected → FAILED + 쿠폰 AVAILABLE
             Unknown → 상태 유지, recovery_attempts + 1, last_error 기록
        confirm 응답: 최신 상태 status = DONE / FAILED / IN_PROGRESS
        reservation: DONE 확정 / FAILED면 HOLD 복귀(새 준비로 재시도) / IN_PROGRESS면 결제 중 유지
```

- orderId는 준비 시도마다 새로 만든다. PG를 바꾸거나 거절 후 다시 시도하면 새 준비가 이전 READY를 대체한다.
- 만료 확인은 reservation과 payment(7)가 둘 다 한다. Kafka 전환 후에는 승인 요청이 줄에서 기다리다 만료될 수 있어, PG를 부르기 직전의 7)이 마지막 관문이다.

### 환불

```
POST /internal/refunds (reservationId, reason)
  1) reservationId로 기존 환불 조회. DONE·REQUESTED면 재현, FAILED면 같은 키로 재시도
  2) tx begin: payment DONE 확인 + Refund REQUESTED 커밋 (부분 UK가 중복을 막는다)
  3) PgClient.cancel(멱등 키 = refund.idempotency_key) (트랜잭션 밖)
  4) tx complete: 잠금 후 REQUESTED일 때만
     성공 → refund DONE + payment CANCELED + 쿠폰 복구 / 거절 → FAILED / 불명 → 유지
```

## 5. 복구

Orders의 `OrderRecoveryJob` 구조를 따른다.

1. `@Scheduled(fixedDelay)` 잡이 `@ConditionalOnProperty(payment.recovery.enabled)`로 켜진다.
2. 후보는 두 종류다. `batch-size`건씩, 오래된 순으로 고른다.
   - `expires_at`이 지난 READY
   - `recovery_blocked = false`이고 `updated_at`이 `stale-seconds`보다 오래된 IN_PROGRESS(환불은 REQUESTED)
3. 후보마다 `FOR UPDATE SKIP LOCKED`로 잠그고 최신 상태를 다시 확인한다. 이미 끝났거나 다른 인스턴스가 잡은 행은 건너뛴다.
4. READY는 PG를 부르지 않고 FAILED(`EXPIRED`) + 쿠폰 복구. IN_PROGRESS는 PG에 **조회**하고(승인을 다시 보내지 않는다) 결과를 4절 9)와 같은 `complete`로 저장한다.
5. 후보 하나의 예외는 로그만 남기고 다음 후보로 넘어간다.
6. `recovery_attempts`가 `max-attempts`에 닿으면 `recovery_blocked = true`, 경고 로그와 메트릭을 남긴다.

| 실패 지점 | 처리 |
|---|---|
| 준비 요청 유실 | 기록 없음 또는 READY. reservation이 같은 orderId로 다시 준비하면 재현된다 |
| 카카오 준비 결과 불명 | FAILED(`READY_FAILED`). 돈이 오가지 않았고 tid를 사용자에게 주지 않았으므로 안전하다. 사용자는 새 준비로 재시도 |
| 사용자가 결제창을 닫음 | READY가 `expires_at`까지 남았다가 FAILED(`EXPIRED`) + 쿠폰 복구 |
| 승인 요청이 만료 후 도착 (Kafka 대기 포함) | 4절 7)에서 FAILED(`EXPIRED`). PG를 부르지 않아 보상 환불이 없다 |
| PG 승인 응답 유실·타임아웃 | IN_PROGRESS 유지 → 복구가 조회로 확정 |
| PG 조회도 계속 실패 | 한도 소진 시 차단. FAILED로 확정하지 않는다 |
| complete 저장 실패 | IN_PROGRESS로 남아 복구가 조회로 다시 확정 |
| 환불 PG 거절 | FAILED. 같은 예매로 환불 재요청 시 같은 멱등 키로 다시 시도 |
| 환불 응답 유실 | REQUESTED 유지 → 복구가 조회로 확정 |

## 6. 구현 규칙

이전 프로젝트 Orders에서 검증된 방식을 따른다. 이유는 [decisions.md](decisions.md)에 있다.

- **트랜잭션 경계:** 흐름 클래스(`PaymentService`, `PaymentProcessor`, `RefundService`)는 트랜잭션 없이 `PaymentTransactions`·`RefundTransactions`의 `@Transactional` 메서드를 차례로 부른다. PG 호출은 항상 두 트랜잭션 사이에 있다. ArchUnit 테스트가 흐름 클래스의 `@Transactional`을 잡아낸다.
- **잠근 뒤 다시 판단:** 상태를 바꾸는 트랜잭션 메서드는 `lockById`로 행을 잠그고 현재 상태를 다시 확인한 뒤 바꾼다. 단순 전이는 조건부 UPDATE(`where id = ? and status = ?`, 결과 1행 확인)로 써도 된다.
- **멱등 재현:** 같은 키가 다시 오면 저장된 결과를 돌려준다. 엔티티의 `matches(command)`가 요청 내용을 비교하고, 다르면 409 `PAYMENT_ORDER_CONFLICT`다. 최초 insert가 UK에 걸리면 이긴 행을 읽어 같은 규칙으로 재현한다.
- **PG 응답 분류:** 어댑터가 HTTP 결과를 `PgResult`로 바꾸고, application은 `PgResult`만 본다.

| PG 응답 | PgResult |
|---|---|
| 2xx 승인·취소 성공 | Approved |
| 연결 실패, 429 (요청이 처리되지 않음) | Rejected(`PG_UNAVAILABLE`, `PG_BUSY`) |
| PG 거절 코드 (잔액 부족, 카드 거절 등) | Rejected(PG 코드) |
| 읽기 타임아웃, 응답 끊김, 5xx, 해석 불가 응답, "이미 처리됨" | Unknown |

- **자동 재시도 없음:** PG 승인·취소 클라이언트는 자동 재시도를 끈다(`Retryer.NEVER_RETRY` 또는 같은 설정). 재시도는 복구 잡의 조회만 한다. PG 조회는 GET이라 팀 `IdempotentRetryConfig`를 붙여도 된다.
- **동시 처리:** 같은 결제를 두 곳에서 처리하지 않게 하는 수단은 DB 잠금이다. 여러 인스턴스(`--scale payment-service=2`)에서도 성립해야 한다.
- **감사 컬럼:** 팀 `HeaderAuditorAware`가 요청 밖 작업을 `SYSTEM`으로 남긴다. 결제 주체는 `user_id`로 추적한다.
- **쿠폰 접근:** 4절의 쿠폰 잡기·사용·반납은 쿠폰 담당이 만든 `CouponUsage`의 `reserve`, `use`, `release`로만 한다. 결제 코드는 `user_coupon`을 직접 조회·수정하지 않는다. `CouponUsage`는 호출한 쪽 트랜잭션에 합류하므로(`REQUIRED`) 결제 상태와 쿠폰 상태가 한 트랜잭션에 커밋된다. 쿠폰을 별도 서비스로 빼게 되면 이 인터페이스의 구현만 Feign 어댑터로 바꾼다.
- **삭제:** payment·refund는 금융 기록이라 삭제 API를 두지 않는다. 팀 규칙대로 `@SQLRestriction`은 붙인다.
- **테스트 두 층:** application 테스트는 `PgClient` 포트를 메모리 픽스처로 바꿔 응답 유실·지연을 주입한다(Orders `OrderClientFixture` 방식). 실제 HTTP는 어댑터 계약 테스트와 E2E에서만 fake-pg 컨테이너로 확인한다.

## 7. ERD

PostgreSQL, `BaseEntity` 감사 컬럼 6개, 시각 `TIMESTAMP`, 금액 원 단위 `INT`. 다른 서비스 ID는 FK 없이 값만 둔다.

### payment

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | |
| order_id | VARCHAR(64) | NOT NULL, UK | 멱등 키. reservation이 만든다 |
| reservation_id | BIGINT | NOT NULL | |
| user_id | UUID | NOT NULL | |
| seller_id | UUID | NOT NULL | 정산용 |
| performance_id, schedule_id | BIGINT | NOT NULL | 정산·조회용 |
| pg_provider | VARCHAR(20) | NOT NULL | TOSS / KAKAO |
| pg_transaction_id | VARCHAR(200) | NULL | 카카오 tid(준비 때), 토스 paymentKey(4절 7) markInProgress 때) |
| checkout_url | VARCHAR(500) | NULL | 카카오 결제창 URL. 같은 orderId 준비 재요청에 재현한다 |
| expires_at | TIMESTAMP | NOT NULL | 결제 허용 시각. reservation이 준비 요청에 넣는다 |
| total_price | INT | NOT NULL | 정가 |
| discount_amount | INT | NOT NULL, 기본 0 | |
| amount | INT | NOT NULL | 결제 금액 |
| user_coupon_id | BIGINT | NULL | |
| status | VARCHAR(20) | NOT NULL | 3절 |
| failure_code, failure_message | VARCHAR(80), VARCHAR(255) | NULL | 확정된 거절 사유. 응답에 실린다 |
| approved_at | TIMESTAMP | NULL | 정산 기준 |
| recovery_attempts | INT | NOT NULL, 기본 0 | |
| recovery_blocked | BOOLEAN | NOT NULL, 기본 false | |
| last_error | VARCHAR(80) | NULL | 마지막 결과 불명 사유. 운영용 |

- UK `order_id`, UK `(pg_provider, pg_transaction_id)`
- 부분 UK `(reservation_id) WHERE status IN ('READY','IN_PROGRESS','DONE')`: 한 예매에 살아 있는 결제는 하나. 새 준비는 같은 트랜잭션에서 이전 READY를 FAILED로 바꾼 뒤 넣는다
- CHECK `amount = total_price - discount_amount AND amount >= 0`
- 부분 INDEX `(expires_at) WHERE status = 'READY'`: 만료된 준비
- 부분 INDEX `(updated_at, id) WHERE status = 'IN_PROGRESS' AND recovery_blocked = false`: 결과 불명 복구

### refund

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | |
| payment_id | BIGINT | FK → payment, NOT NULL | |
| reservation_id | BIGINT | NOT NULL | 조회 기준 |
| idempotency_key | VARCHAR(64) | NOT NULL, UK | 예매 기준. PG 취소 멱등 키로도 쓴다 |
| refund_amount | INT | NOT NULL | 1차는 전액 |
| reason | VARCHAR(30) | NOT NULL | USER_CANCEL / SCHEDULE_CANCELED / CONFIRM_FAILED |
| status | VARCHAR(20) | NOT NULL | 3절 |
| pg_cancel_id | VARCHAR(200) | NULL | |
| failure_code, failure_message | VARCHAR(80), VARCHAR(255) | NULL | |
| refunded_at | TIMESTAMP | NULL | 정산 기준 |
| recovery_attempts, recovery_blocked, last_error | | | payment와 같음 |

- 부분 UK `(payment_id) WHERE status IN ('REQUESTED','DONE')`: 중복 환불 방지
- 부분 INDEX `(updated_at, id) WHERE status = 'REQUESTED' AND recovery_blocked = false`

### user_coupon (쿠폰 담당 6번 소유)

테이블·엔티티·`schema.sql`은 쿠폰 담당이 만든다. 결제가 필요로 하는 것은 아래 두 가지와 `CouponUsage`(6절)다.

| 요청 사항 | 이유 |
|---|---|
| status에 `RESERVED` | PG 호출 전에 쿠폰을 잡아 동시 사용을 막는다 |
| `payment_id BIGINT NULL` | 잡은 결제. `use`·`release`가 결제 ID로 쿠폰을 찾는다 |

`payment.user_coupon_id`와 `user_coupon.payment_id`는 같은 스키마지만 소유자가 달라 FK를 두지 않고 값만 저장한다.

### fake-pg `pg_transaction` (`pg_schema`)

`id`, `provider`, `transaction_id`(UK), `order_id`, `amount`, `status`(APPROVED / CANCELED), `approved_at`, `canceled_at`, `cancel_idempotency_key`. 대사의 상대편 기록이다. 장애 설정은 메모리에 두고 관리 API로 바꾼다.

Kafka 전환 시 `outbox_event`, `processed_event`, 정산 확장 시 `settlement`를 추가한다.

## 8. API

### 내부 (payment 소유. 모두 멱등. `ApiResponse`로 감싸지 않음)

| Method | URI | 요청 | 응답 |
|---|---|---|---|
| POST | `/internal/payments` (준비) | orderId, reservationId, userId, sellerId, performanceId, scheduleId, totalPrice, userCouponId, pgProvider, expiresAt | 200 orderId, amount, `status` = READY / FAILED, checkoutUrl(카카오). 같은 orderId·다른 내용 409 `PAYMENT_ORDER_CONFLICT` |
| POST | `/internal/payments/{orderId}/confirm` (승인) | pgAuthToken(토스 paymentKey, 카카오 pg_token), amount | 200 `status` = DONE / FAILED / IN_PROGRESS. 금액 불일치 400 `PAYMENT_AMOUNT_MISMATCH`. 기록 없음 404 |
| GET | `/internal/payments/by-order/{orderId}` | | 200 결제 / 404 기록 없음 |
| POST | `/internal/refunds` | reservationId, reason | 200 `status` = DONE / FAILED / REQUESTED |
| GET | `/internal/refunds/by-reservation/{reservationId}` | | 200 환불 / 404 기록 없음 |

업무 결과(승인·거절·불명)는 본문 `status`로 돌려준다. 4xx는 잘못된 요청에만 쓴다.

### 외부 (reservation 소유. 참고용)

| Method | URI | 결제 관련 응답 |
|---|---|---|
| POST | `/api/v1/reservations/{id}/payments/checkout` | 200 orderId, amount, checkoutUrl / 400 COUPON_NOT_AVAILABLE / 410 HOLD_EXPIRED / 503 PG_UNAVAILABLE |
| POST | `/api/v1/reservations/{id}/payments/confirm` | 200 CONFIRMED / 202 PAYMENT_IN_PROGRESS / 400 PAYMENT_FAILED, PAYMENT_AMOUNT_MISMATCH / 410 HOLD_EXPIRED / 503 PG_UNAVAILABLE |
| POST | `/api/v1/reservations/{id}/cancel` | 200 CANCELED / 202 CANCELING |

### fake-pg

경로·필드는 각 PG 공식 문서 형식을 따른다.

| 구분 | API |
|---|---|
| 토스 흉내 | 결제창 대체(paymentKey 발급), 승인, 주문번호 조회, 취소(멱등 키 헤더) |
| 카카오 흉내 | 준비(tid), 사용자 승인 대체(pg_token 발급), 승인, 조회, 취소 |
| 관리 | PG별 장애 설정(지연, 실패율, 승인 후 응답 유실률, 초당 처리 한도), 거래 기록 조회 |

"결제창 대체"와 "사용자 승인 대체"는 브라우저 단계를 대신하는 fake-pg 전용 API다. 이 덕분에 k6로 결제 흐름 전체를 자동화한다.

## 9. 다른 담당과의 계약

| 상대 | 맞출 것 | 시점 |
|---|---|---|
| 예매 (2번) | 외부 API 두 단계(checkout, confirm)와 내부 API 5개, `status` 의미, 준비 시도마다 새 orderId, `expiresAt` 값(선점 만료와 연장), HOLD → PAYMENT_IN_PROGRESS는 승인 요청 때라는 점, 결과를 모를 때 조회 결과별 동작(아래 표), 타임아웃 순서(PG 10초 < payment 대기 15초 < Gateway 60초), Kafka 전환 후 승인 응답은 "접수"만 뜻한다는 점 | 1주차 초 |
| 쿠폰 (6번) | user_coupon은 쿠폰 담당 소유이고 payment 스키마에 둔다는 점, `RESERVED`·`payment_id` 추가, `CouponUsage`의 `reserve`·`use`·`release` 시그니처와 반환값(잡기 실패 표현), 트랜잭션 전파 `REQUIRED`, 유효 기간이 지난 쿠폰을 반납할 때 EXPIRED로 둘지 | 1주차 |
| 공연 (5번) | 결제 요청에 들어갈 `seller_id`, `performance_id`, `schedule_id` | 1주차 |
| 대기열 (4번) | 결제 처리 용량 기반 입장 속도 | 트러블슈팅 |

서비스마다 DTO를 따로 두고 계약 문서로 필드를 맞춘다. Feign 본문과 Kafka 메시지 필드는 같다.

### 예매가 결제 결과를 모를 때

승인 요청이나 그 응답이 중간에 끊기면 예매는 PAYMENT_IN_PROGRESS로 남는다. 예매는 같은 승인 요청을 다시 보내거나(멱등이라 PG를 두 번 부르지 않는다) `GET /internal/payments/by-order/{orderId}`로 조회하고, 결과에 따라 다음처럼 마무리한다. 예매 복구 잡도 오래된 PAYMENT_IN_PROGRESS를 같은 표로 처리한다.

| payment 결과 | 뜻 | 예매가 할 일 |
|---|---|---|
| DONE | 승인 확정 | CONFIRMED, 좌석 SOLD |
| FAILED | 거절·만료·대체 확정 | HOLD로 복귀 (선점 만료 후면 만료) |
| IN_PROGRESS | PG 결과를 모름. payment 복구가 확정한다 | 기다린다 |
| READY | 승인 요청이 payment에 도착하지 못했다. PG 승인은 불리지 않았다 | 선점 시간 안이면 HOLD로 복귀(사용자가 다시 시도), 지났으면 만료 |
| 404 | 준비조차 기록되지 않았다 | HOLD로 복귀 |

payment는 READY 결제를 스스로 승인하지 않는다. 인증값(paymentKey, pg_token)은 승인 요청에 실려 올 때만 쓰고 저장하지 않으므로, 승인 요청이 오지 않은 결제는 "결제 안 함"으로 끝난다.

## 10. 열린 질문

1. 판매자(SELLER) 역할을 1차에 넣는가 (정산 대상 유무)
2. 쿠폰 할인 부담 주체 (정산 기준이 정가인가 결제액인가)
3. 결제 허용 시각(`expiresAt`): 선점 만료 그대로인가, 결제창 진입 시 연장하는가 (예매 담당과 결정)
4. fake-pg 처리 한도·지연 기본값, 복구 주기·한도 값 (2절 설정의 기본값은 임시다)
