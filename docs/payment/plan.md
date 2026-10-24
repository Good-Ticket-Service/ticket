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
| fake-pg, 토스·카카오 어댑터, 멱등 결제·환불, 결과 불명 복구, 늦은 승인 자동 취소와 최소 대사, 쿠폰 사용 예약, 메트릭, 동기 기준선 측정 | 장애 격리, Kafka 부하 평탄화, Kafka 결과 이벤트(Outbox), 대량 환불 | 전체 대사, 정산, 샌드박스 실연동 |

범위 밖: 부분 환불·취소 수수료, PG 자동 전환, 판매자 지급(송금).

## 2. 구조: 바꿔 끼울 수 있는 세 축

application 계층은 포트(인터페이스)만 알고, infrastructure의 어댑터가 Feign·Kafka·PG를 다룬다. `LayeredArchitectureRules`(ArchUnit)가 이 방향을 강제한다.

| 축 | 포트 | 1차 어댑터 | Kafka 전환 후 |
|---|---|---|---|
| PG (어느 PG로) | `PgClient` | `TossPgClient`, `KakaoPgClient` | 같음. 샌드박스는 base-url만 교체 |
| 실행 시점 (언제) | `PaymentDispatcher` | `InlinePaymentDispatcher`: 요청 스레드에서 바로 | `KafkaPaymentDispatcher`: 워커가 처리 한도 안에서 |
| 결과 알림 (어떻게) | `PaymentResultPublisher` | `NoopResultPublisher`: HTTP 응답이 결과 | `KafkaResultPublisher`: Outbox |

결제는 PG 구조를 따라 **준비 → 사용자 인증 → 승인** 두 번의 요청으로 나뉜다(4절). 준비(`PaymentService.checkout`)와 승인 접수(`PaymentService.confirm`)는 요청 스레드에서 끝나고, PG 승인(`PaymentProcessor.process`)은 Dispatcher를 거친다. `dispatch`는 `void`다. Inline이면 접수 후 최신 상태를 다시 읽어 결과를 돌려주고, Kafka면 `ACCEPTED`를 돌려준 뒤 결과를 이벤트로 알린다.

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
  min-amount: 100           # PG 최소 결제 금액. 할인 후 금액이 이보다 작아지지 않게 한다
  recovery:
    enabled: true           # 테스트에서 끄고 직접 호출한다
    delay-ms: 5000          # 잡 실행 간격
    batch-size: 50
    first-check-seconds: 30 # 승인 호출 후 첫 조회까지
    backoff-max-seconds: 300
    not-found-grace-minutes: 10  # 승인 호출 후 이 시간이 지나야 "승인 기록 없음"을 거절로 확정한다
    give-up-minutes: 60     # 승인 호출 후 이 시간이 지나도 모르면 차단한다
  reconcile:
    delay-minutes: 60       # 최소 대사 실행 간격
    after-minutes: 60       # FAILED 확정 후 이 시간이 지난 건을 한 번 더 조회한다
pg:
  connect-timeout-ms: 2000
  read-timeout-ms: 10000
  toss.base-url: http://fake-pg:8090
  kakao.base-url: http://fake-pg:8090
```

### 패키지

```
com.sparta.ticket.payment
├── presentation/internal   InternalPaymentController, InternalAdminController
├── application
│   ├── PaymentService, PaymentProcessor, RefundService   흐름 지휘. 트랜잭션 없음
│   ├── PaymentTransactions, RefundTransactions           @Transactional 메서드만. 상태 전이 하나 = 메서드 하나
│   ├── PaymentRecoveryJob, RefundRecoveryJob, PaymentReconcileJob   @Scheduled. 흐름 클래스를 부른다
│   ├── port/   PgClient, PaymentDispatcher, PaymentResultPublisher
│   ├── dto/    PaymentCommand, PaymentResult, RefundResult
│   └── coupon/ CouponUsage 와 구현                          쿠폰 담당 소유. 결제는 CouponUsage만 부른다
├── domain      Payment, Refund, PaymentStatus, RefundStatus, PgProvider, PgResult, PgInquiry, 저장소 인터페이스
│   └── coupon/ UserCoupon 등                                쿠폰 담당 소유
└── infrastructure
    ├── pg/      toss/, kakao/, PgClientRegistry
    ├── dispatch/  InlinePaymentDispatcher (Kafka 전환 후 KafkaPaymentDispatcher)
    └── messaging/ NoopResultPublisher (Kafka 전환 후 KafkaResultPublisher, PaymentCommandConsumer)
```

`PgClient`는 `ready`, `approve`, `inquire`, `cancel`을 가진다.

- `ready`: 카카오만 PG를 부른다(tid, 결제창 URL 발급). 토스는 PG를 부르지 않고 결제창에 넘길 값만 돌려준다. 결제창은 클라이언트가 띄운다.
- `approve`·`cancel` 결과는 sealed interface `PgResult`다: `Approved(transactionId, amount, at)`, `Rejected(code, message)`, `Unknown(reason)`.
- `inquire` 결과는 `PgInquiry`다: `Approved(...)`, `Canceled(...)`, `NotFound`, `Unknown(reason)`. "승인 기록 없음"은 승인 요청이 아직 PG로 가는 중일 수 있어 바로 거절로 보지 않는다(5절).
- `cancel`은 같은 결제에 다시 보내도 안전해야 한다. 토스는 `Idempotency-Key`, 카카오는 키가 없으므로 어댑터가 먼저 조회해 이미 취소됐으면 성공으로 돌려준다. 취소 응답이 "이미 취소됨"이면(동시 취소 등) 어댑터가 다시 조회해 Canceled면 성공으로 돌려준다.

## 3. 상태

| payment | 의미 | 다음 |
|---|---|---|
| READY | 준비됨. 금액·쿠폰이 확정됐고 승인 요청을 기다린다. PG 승인은 아직 부르지 않았다 | IN_PROGRESS, FAILED |
| IN_PROGRESS | PG 승인을 불렀고 결과를 모른다. 복구 재개 지점이다 | DONE, FAILED |
| DONE | 승인 확정. 금액·PG 정보는 바뀌지 않는다 | CANCELED |
| FAILED | 돈이 오가지 않음으로 확정 (종료). 늦은 승인이 발견되면 자동 취소 환불이 붙는다(4절) | - |
| CANCELED | 전액 환불 완료 (종료) | - |

FAILED의 `failure_code`: `SUPERSEDED`(새 준비로 대체), `READY_FAILED`(카카오 준비 실패), `EXPIRED`(결제 허용 시각 경과), `ABANDONED`(승인 전 예매 취소), `PG_UNAVAILABLE`(연결 실패·서킷 열림), `PG_BUSY`(429), `INVALID_AUTH_TOKEN`(인증값 재사용·조작), `NOT_APPROVED`(조회로 승인 없음 확정), `AMOUNT_MISMATCH`(승인 금액 불일치, AUTO_VOID 동반), `CANCELED_AT_PG`(조회해 보니 PG에서 이미 취소됨), `MANUAL`(관리자 확정), 그 밖은 PG 거절 코드.

**모든 FAILED 전이는 같은 트랜잭션에서 `CouponUsage.release`를 부른다.** 쿠폰이 없거나 이미 반납됐으면 release는 아무것도 하지 않는다.

| refund | 의미 | 다음 |
|---|---|---|
| WAITING | 환불 요청을 받았는데 결제가 아직 IN_PROGRESS다. 결제가 확정되면 이어서 처리한다 | REQUESTED, SKIPPED |
| REQUESTED | PG 취소를 불렀거나 부를 차례이고 결과를 모른다. 복구 재개 지점이다 | DONE, FAILED |
| DONE | 취소 확정 (종료). 결제가 DONE이었으면 같은 트랜잭션에서 CANCELED |  - |
| FAILED | PG가 취소를 업무상 거절했다. 자동 재시도하지 않고 차단·경보로 남긴다 | REQUESTED (관리자 재시도) |
| SKIPPED | WAITING이었는데 결제가 FAILED로 확정돼 환불할 돈이 없다 | REQUESTED (늦은 승인 발견) |

- **환불 보장(`ensureRefund`)**: PG에 돈이 잡혀 있다고 확인될 때(결제 DONE + 환불 요청, 늦은 승인, 금액 불일치) 부르는 단일 규칙이다. 환불 행이 없으면 REQUESTED로 만들고, WAITING·SKIPPED면 REQUESTED로 바꾸고, REQUESTED·DONE·FAILED면 그대로 둔다. 기존 사유는 유지하고 행이 새로 생길 때만 `AUTO_VOID`를 쓴다. 그래서 AUTO_VOID와 사용자 환불이 겹쳐도 행은 하나다.
- 상태 전이는 엔티티 메서드에서만 일어난다. 메서드는 현재 상태를 검사하고 맞지 않으면 공통 `INVALID_STATUS_TRANSITION`을 던진다.
- 결과 불명과 자동 처리 중단은 상태가 아니라 `recovery_attempts`, `next_attempt_at`, `recovery_started_at`, `recovery_blocked`, `last_error`로 표현한다. 중단된 결제는 IN_PROGRESS 그대로 차단되어 사람이 확인한다. 실제로 승인됐을 수 있는 결제를 FAILED로 확정하지 않기 위해서다.
- Orders의 `recovery_phase`는 두지 않는다. 외부 호출 단계가 결제는 승인 하나, 환불은 취소 하나라 상태 자체가 재개 지점이다.

## 4. 처리 흐름

### 결제 (1차, reservation이 지휘)

토스와 카카오 모두 사용자 인증 결과(토스 `paymentKey`, 카카오 `pg_token`)가 있어야 승인할 수 있다. 그래서 준비와 승인을 따로 요청한다.

```
① 준비  사용자 → reservation: checkout {pgProvider, couponId}
        reservation: HOLD 확인, 새 orderId 저장 → POST /internal/payments
        payment checkout:
          1) order_id로 기존 결제 조회. 있으면 내용 비교 후 재현(같음) / 409 PAYMENT_ORDER_CONFLICT(다름)
             재현할 결제가 카카오 READY인데 tid가 없으면(준비 직후 중단) 3)부터 다시 한다
          2) tx prepare:
             - 같은 예매의 이전 READY를 FAILED(SUPERSEDED) + 쿠폰 release   ← 반드시 새 reserve보다 먼저
             - 같은 예매에 IN_PROGRESS·DONE 결제가 있으면 409 PAYMENT_ALREADY_IN_PROGRESS / PAYMENT_ALREADY_DONE
               승인 요청이 이미 접수된(confirm_requested_at이 있는) READY도 대체하지 않고 409 PAYMENT_ALREADY_IN_PROGRESS
             - CouponUsage.reserve → 할인액. 할인액은 total_price - min-amount를 넘지 않게 자른다
             - 새 Payment READY(금액 확정, expires_at) 커밋
             order_id UK 충돌이면 이긴 행을 1)처럼 재현, reservation 부분 UK 충돌(동시 준비)이면 409 PAYMENT_CHECKOUT_CONFLICT
          3) PgClient.ready (트랜잭션 밖. 카카오만 PG 호출)
             카카오 성공 → tx: tid·결제창 URL 저장 / 실패·불명 → tx: FAILED(READY_FAILED) + 쿠폰 release
        응답: orderId, amount, status, (카카오) 결제창 URL
② 인증  사용자가 결제창에서 인증한다. fake-pg에서는 "결제창 대체" API가 대신한다
③ 승인  사용자 → reservation: confirm {orderId, pgAuthToken}
        reservation: 선점 만료 확인, HOLD → PAYMENT_IN_PROGRESS + pgAuthToken 저장 → POST /internal/payments/{orderId}/confirm {pgAuthToken, amount}
        payment confirm:
          4) READY가 아니면 현재 상태를 재현한다 (동시에 온 승인 요청도 여기서 걸린다)
          5) 요청 amount가 저장된 amount와 다르면 400 PAYMENT_AMOUNT_MISMATCH (상태 유지, PG 호출 없음)
          6) tx: confirm_requested_at 기록 (READY 유지)
          7) dispatcher.dispatch(paymentId, pgAuthToken)
        payment process:
          8) tx markInProgress: 잠금 후 READY일 때만 판단한다 (READY가 아니면 아무것도 하지 않는다)
             expires_at이 지났으면 FAILED(EXPIRED)
             서킷이 열려 있으면 FAILED(PG_UNAVAILABLE)
             토스 paymentKey가 UK에 걸리면 FAILED(INVALID_AUTH_TOKEN)
             그 밖이면 IN_PROGRESS, pg_transaction_id(토스 paymentKey), approve_requested_at,
                       recovery_started_at, next_attempt_at = now + first-check-seconds
             FAILED면 PG를 부르지 않는다
          9) PgClient.approve(저장된 amount로) (트랜잭션 밖)
         10) tx complete: payment 잠금 후 (환불 행은 그다음에 잠근다)
             IN_PROGRESS + Approved(금액 일치) → DONE + CouponUsage.use, WAITING 환불이 있으면 ensureRefund
             IN_PROGRESS + Approved(금액 불일치) → FAILED(AMOUNT_MISMATCH) + ensureRefund(승인 금액)
             IN_PROGRESS + Rejected → FAILED, WAITING 환불은 SKIPPED
             IN_PROGRESS + Unknown → 상태 유지, recovery_attempts + 1, last_error
             FAILED + Approved → 늦은 승인. FAILED 유지 + ensureRefund(승인 금액) + 경보
             DONE + Approved → 이미 반영됨, 무시
        confirm 응답: Inline은 최신 상태 status = DONE / FAILED / IN_PROGRESS, Kafka는 ACCEPTED
```

- orderId는 준비 시도마다 새로 만든다. PG를 바꾸거나 거절 후 다시 시도하면 새 준비가 이전 READY를 대체한다.
- 만료 확인은 reservation과 payment(8)가 둘 다 한다. Kafka 전환 후에는 승인 요청이 줄에서 기다리다 만료될 수 있어, PG를 부르기 직전의 8)이 마지막 관문이다. 서킷 확인도 8) 안에서 잠금과 READY 확인 뒤에 한다. 그래야 같은 결제의 중복 승인 요청이 이미 PG를 부르는 중인 결제를 FAILED로 바꾸지 못한다.
- payment는 인증값을 DB에 저장하지 않는다. 승인 요청에 실려 올 때만 쓴다. 승인 요청을 다시 보내는 것은 인증값을 가진 reservation의 일이다(9절). Kafka 모드에서는 인증값이 `payment.requested` 메시지에 실리므로 토픽 보관 기간을 짧게 둔다(인증값은 일회용이고 PG에서 약 10분 뒤 만료된다).

### 환불

환불은 결제(orderId) 단위다. reservation은 환불을 요청하면 결과를 기다리지 않고 예매를 취소하고, 환불을 끝까지 마무리하는 책임은 payment에 있다.

```
POST /internal/payments/{orderId}/refund {reason}
  1) tx: payment 행을 잠그고 상태를 다시 읽는다. 환불 행이 있으면 그 상태를 재현한다
  2) 결제 상태별 (같은 트랜잭션):
     READY       → FAILED(ABANDONED). 응답 NOT_REQUIRED
     FAILED      → 환불 행이 없으면 응답 NOT_REQUIRED
     IN_PROGRESS → Refund WAITING. 결제가 확정될 때 4절 10)이 이어서 처리한다
     DONE        → ensureRefund (REQUESTED)
     CANCELED    → 기존 환불 재현
     동시 요청이 UK에 걸리면 이긴 행을 재현한다
  3) REQUESTED면 커밋 후 바로 PgClient.cancel(PG 키 = orderId + ":cancel:" + cancel_attempt) (트랜잭션 밖)
  4) tx complete: payment → refund 순서로 잠근 뒤 refund가 REQUESTED일 때만
     성공 → refund DONE (+ 결제가 DONE이면 CANCELED와 쿠폰 release)
     업무 거절 → FAILED + recovery_blocked + 경보
     일시 장애·불명 (연결 실패, 429, 타임아웃, 5xx) → REQUESTED 유지, 복구가 같은 키로 다시 보낸다
```

- REQUESTED가 되는 모든 전이는 `requested_at`(처음일 때), `recovery_started_at`, `next_attempt_at`을 같이 설정한다. 요청 경로는 커밋 뒤 바로 cancel을 부르므로 `next_attempt_at = now + 점유 기간`, 그 밖의 경로(4절 10)의 ensureRefund, 대사)는 `next_attempt_at = now`로 두어 복구 잡이 부른다.
- `refund_amount`: 사용자 환불은 결제 금액, AUTO_VOID는 PG가 승인한 금액이다.
- 자동 취소 환불(`AUTO_VOID`)이 붙은 결제는 FAILED 그대로다. refund DONE이면 "잡혔던 돈을 돌려줌"이다. 쿠폰은 FAILED 전이 때 이미 반납됐다.

## 5. 복구

Orders의 `OrderRecoveryJob` 구조를 따른다.

1. `@Scheduled(fixedDelay)` 잡이 `@ConditionalOnProperty(payment.recovery.enabled)`로 켜진다.
2. 후보는 세 종류다. `batch-size`건씩, 오래된 순으로 고른다. 대사 잡(8번)도 같은 방식으로 후보를 고르고 점유한다.
   - `expires_at`이 지난 READY
   - `recovery_blocked = false`이고 `next_attempt_at`이 지난 IN_PROGRESS
   - `recovery_blocked = false`이고 `next_attempt_at`이 지난 refund REQUESTED
3. **점유:** 짧은 트랜잭션에서 `FOR UPDATE SKIP LOCKED`로 후보를 잠그고 `next_attempt_at = now + max(점유 기간, 백오프)`로 갱신한 뒤 바로 커밋한다. 다른 인스턴스는 갱신된 시각 때문에 같은 행을 잡지 않는다. 점유 기간은 PG 최대 호출 시간(connect + read)보다 길어야 하므로 30초로 둔다. 백오프는 `min(5초 × 2^recovery_attempts, backoff-max-seconds)`다. PG 호출은 그 뒤 트랜잭션 밖에서 하고, 결과 저장은 4절 complete처럼 다시 잠근 뒤 상태를 확인한다.
4. 처리:
   - READY: PG를 부르지 않고 FAILED(`EXPIRED`) + 쿠폰 release
   - IN_PROGRESS: PG에 **조회**한다(승인을 다시 보내지 않는다)
     - Approved → 4절 10) complete
     - Canceled → FAILED(`CANCELED_AT_PG`). 돈은 PG에서 이미 돌려줬다
     - NotFound → `approve_requested_at`부터 `not-found-grace-minutes`가 지났으면 FAILED(`NOT_APPROVED`), 아니면 Unknown과 같이 다음 시도
     - Unknown → `recovery_attempts + 1`, 다음 시도
   - refund REQUESTED: 같은 PG 키로 cancel을 다시 보낸다(2절 `cancel` 규칙으로 안전하다). 결과는 4절 환불 4)와 같다
5. 후보 하나의 예외는 로그만 남기고 다음 후보로 넘어간다.
6. `recovery_started_at`부터 `give-up-minutes`가 지나도 확정되지 않으면 `recovery_blocked = true`, 경고 로그와 메트릭을 남긴다. 횟수가 아니라 시간으로 정해서, 짧은 PG 장애에 대량 차단되지 않게 한다. 결제가 차단될 때 WAITING 환불이 있으면 경보에 함께 표시한다(예매는 이미 취소됐다).
7. 차단 건은 관리자가 PG 상태를 확인한 뒤 관리자 API(8절)로 다시 자동 처리에 넣거나, 근거를 남기고 직접 확정한다.
8. **최소 대사:** `PaymentReconcileJob`이 PG 승인을 실제로 호출했는데 FAILED로 끝난 결제(`approve_requested_at`이 있고 `reconciled_at`이 없으며, FAILED 확정 후 `after-minutes`가 지난 건)를 PG에 한 번 더 조회한다. Approved면 4절 10)의 늦은 승인처럼 ensureRefund + 경보, NotFound·Canceled면 그대로 둔다. 이 셋일 때만 `reconciled_at`을 기록해 한 번만 본다. Unknown이면 기록하지 않고 다음 주기에 다시 보며, 24시간이 지나도 Unknown이면 경보를 남긴다. PG 승인을 부르지 않은 FAILED(만료, 대체, 서킷 등)는 돈이 빠질 수 없어 대상이 아니다.

| 실패 지점 | 처리 |
|---|---|
| 준비 요청 유실 | 기록 없음 또는 READY. reservation이 같은 orderId로 다시 준비하면 재현된다 |
| 카카오 준비 성공 후 tid 저장 전 중단 | tid 없는 READY. 같은 orderId 재준비 때 카카오 준비를 다시 부른다. 재준비가 없으면 만료 |
| 카카오 준비 결과 불명 | FAILED(`READY_FAILED`). 돈이 오가지 않았고 tid를 사용자에게 주지 않았으므로 안전하다 |
| 사용자가 결제창을 닫음 | READY가 `expires_at`까지 남았다가 FAILED(`EXPIRED`) + 쿠폰 release |
| 승인 요청이 payment에 도착하지 못함 | READY. reservation이 저장한 인증값으로 같은 승인 요청을 다시 보낸다(9절) |
| 승인 요청이 만료 후 도착 (Kafka 대기 포함) | 4절 8)에서 FAILED(`EXPIRED`). PG를 부르지 않는다 |
| 같은 결제의 승인 요청이 중복으로 옴 (재전송, Kafka 중복) | 4절 4)·8)의 잠금과 READY 확인에서 걸려 현재 상태만 재현한다 |
| markInProgress 후 PG 호출 전 중단 | IN_PROGRESS. 조회가 NotFound를 돌려주고, 유예 시간이 지나면 FAILED(`NOT_APPROVED`) |
| PG 승인 응답 유실·타임아웃 | IN_PROGRESS → 복구가 조회로 확정 |
| 조회가 "없음"인데 승인이 늦게 처리됨 | 유예 시간 안이면 아직 확정하지 않는다. 유예 뒤 FAILED가 된 다음 승인되면 최소 대사(8번)가 찾아 AUTO_VOID로 돌려준다 |
| PG 조회도 계속 실패 | `give-up-minutes` 뒤 차단. FAILED로 확정하지 않는다. 사람이 PG 콘솔로 확인해 관리자 API로 확정한다 |
| 조회해 보니 PG에서 이미 취소됨 | FAILED(`CANCELED_AT_PG`) |
| complete 저장 실패 | IN_PROGRESS로 남아 복구가 조회로 다시 확정 |
| 쿠폰 use 실패 | 일어나지 않게 계약으로 막는다(9절 쿠폰). RESERVED이고 payment_id가 같으면 use는 실패하지 않는다 |
| 환불 PG 일시 장애·응답 유실 | REQUESTED 유지 → 복구가 같은 키로 다시 보낸다 |
| 환불 PG 업무 거절 | FAILED + 차단 + 경보. 관리자 재시도는 `cancel_attempt + 1`로 새 PG 키를 쓴다(토스는 같은 키의 거절 응답을 15일간 재현한다) |
| 결제 확정 전 환불 요청 | WAITING → 결제가 DONE이면 REQUESTED, FAILED면 SKIPPED. 나중에 늦은 승인이 발견되면 SKIPPED → REQUESTED |
| 금액 불일치 승인 | FAILED(`AMOUNT_MISMATCH`) + 승인 금액으로 AUTO_VOID |
| 동시 취소로 "이미 취소됨" 응답 | 어댑터가 다시 조회해 성공으로 확정한다 |

## 6. 구현 규칙

이전 프로젝트 Orders에서 검증된 방식을 따른다. 이유는 [decisions.md](decisions.md)에 있다.

- **트랜잭션 경계:** 흐름 클래스(`PaymentService`, `PaymentProcessor`, `RefundService`)는 트랜잭션 없이 `PaymentTransactions`·`RefundTransactions`의 `@Transactional` 메서드를 차례로 부른다. PG 호출은 항상 두 트랜잭션 사이에 있다. ArchUnit 테스트가 흐름 클래스의 `@Transactional`을 잡아낸다.
- **잠금 순서:** 한 트랜잭션에서 payment와 refund를 함께 바꿀 때는 항상 payment를 먼저 잠근다. 결제 complete, 환불 요청, 환불 complete가 모두 이 순서를 따라 교착을 막는다.
- **잠근 뒤 다시 판단:** 상태를 바꾸는 트랜잭션 메서드는 `lockById`로 행을 잠그고 현재 상태를 다시 확인한 뒤 엔티티 메서드로 바꾼다. 조건부 UPDATE를 쓰면 `@LastModifiedDate`가 적용되지 않으므로, 쓸 때는 시각 컬럼을 쿼리에서 직접 설정한다. 복구 시점 판단은 `updated_at`이 아니라 `approve_requested_at`, `next_attempt_at`으로 한다.
- **멱등 재현:** 같은 키가 다시 오면 저장된 결과를 돌려준다. 엔티티의 `matches(command)`가 요청 내용을 비교하고, 다르면 409 `PAYMENT_ORDER_CONFLICT`다. order_id UK와 reservation 부분 UK 충돌은 서로 다른 응답으로 구분한다(4절 2). 환불 행 insert도 UK에 걸리면 이긴 행을 재현한다.
- **PG 응답 분류:** 어댑터가 HTTP 결과를 `PgResult`·`PgInquiry`로 바꾸고, application은 그 타입만 본다.

| PG 응답 (승인·취소) | PgResult |
|---|---|
| 2xx 성공 | Approved |
| TCP 연결 단계 실패(connect timeout, connection refused). 요청 바이트를 보내기 전 실패만 해당 | Rejected(`PG_UNAVAILABLE`) |
| PG 본체가 보낸 429 | Rejected(`PG_BUSY`) |
| PG 업무 거절 코드 (잔액 부족, 카드 거절, 취소 불가 등) | Rejected(PG 코드) |
| 읽기 타임아웃, 쓰기 후 연결 끊김, 5xx, 프록시가 보낸 오류, 해석 불가 응답, "이미 처리됨" | Unknown |

  - Feign은 연결 실패와 읽기 타임아웃을 같은 `RetryableException`으로 감싼다. 어댑터는 원인 예외(`ConnectException`, `SocketTimeoutException`의 단계)를 보고 구분하고, 구분할 수 없으면 Unknown이다.
  - 환불에서는 Rejected(`PG_UNAVAILABLE`·`PG_BUSY`)도 일시 장애로 보고 REQUESTED를 유지한다. 업무 거절 코드만 FAILED다.
  - 카카오는 승인 응답의 금액도 저장 금액과 대조한다(토스는 PG가 대조한다).
  - 취소의 "이미 취소됨"은 업무 거절이 아니다. 어댑터가 조회해 Canceled면 성공이다.
- **자동 재시도 없음:** PG 승인·취소 클라이언트는 자동 재시도를 끈다(`Retryer.NEVER_RETRY` 또는 같은 설정). 재시도는 복구 잡만 한다. PG 조회는 GET이라 팀 `IdempotentRetryConfig`를 붙여도 된다.
- **타임아웃 예산:** PG connect 2초 + read 10초, payment DB 커넥션 대기(Hikari `connection-timeout`) 2초로 payment의 승인 응답이 15초 안에 끝나게 한다. reservation의 payment 호출 read timeout은 15초보다 길고 Gateway(60초)보다 짧게 둔다.
- **동시 처리:** 같은 결제를 두 곳에서 처리하지 않게 하는 수단은 DB 잠금과 5절 점유다. 여러 인스턴스(`--scale payment-service=2`)에서도 성립해야 한다.
- **감사 컬럼:** 팀 `HeaderAuditorAware`가 요청 밖 작업을 `SYSTEM`으로 남긴다. 결제 주체는 `user_id`로 추적한다.
- **쿠폰 접근:** 쿠폰 잡기·사용·반납은 쿠폰 담당이 만든 `CouponUsage`의 `reserve`, `use`, `release`로만 한다. 결제 코드는 `user_coupon`을 직접 조회·수정하지 않는다. `CouponUsage`는 호출한 쪽 트랜잭션에 합류하므로(`REQUIRED`) 결제 상태와 쿠폰 상태가 한 트랜잭션에 커밋된다. 쿠폰을 별도 서비스로 빼게 되면 이 인터페이스의 구현만 Feign 어댑터로 바꾼다.
- **결과 이벤트 (Kafka 전환 후):** 결제·환불의 모든 확정 전이(DONE, FAILED, CANCELED, refund DONE·FAILED·SKIPPED)는 같은 트랜잭션에서 Outbox에 쓴다. 요청 경로든 복구 잡이든 같다.
- **삭제:** payment·refund는 금융 기록이라 삭제 API를 두지 않는다. 팀 규칙대로 `@SQLRestriction`은 붙인다.
- **테스트 두 층:** application 테스트는 `PgClient` 포트를 메모리 픽스처로 바꿔 응답 유실·지연·늦은 승인을 주입한다(Orders `OrderClientFixture` 방식). 실제 HTTP는 어댑터 계약 테스트와 E2E에서만 fake-pg 컨테이너로 확인한다.

## 7. ERD

PostgreSQL, `BaseEntity` 감사 컬럼 6개, 시각 `TIMESTAMP`, 금액 원 단위 `INT`. 다른 서비스 ID는 FK 없이 값만 둔다.

### payment

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | |
| order_id | VARCHAR(64) | NOT NULL, UK | 멱등 키. reservation이 준비 시도마다 만든다 |
| reservation_id | BIGINT | NOT NULL | |
| user_id | UUID | NOT NULL | |
| seller_id | UUID | NOT NULL | 정산용 |
| performance_id, schedule_id | BIGINT | NOT NULL | 정산·조회용 |
| pg_provider | VARCHAR(20) | NOT NULL | TOSS / KAKAO |
| pg_transaction_id | VARCHAR(200) | NULL | 카카오 tid(준비 때), 토스 paymentKey(markInProgress 때) |
| checkout_url | VARCHAR(500) | NULL | 카카오 결제창 URL. 같은 orderId 준비 재요청에 재현한다 |
| expires_at | TIMESTAMP | NOT NULL | 결제 허용 시각. reservation이 준비 요청에 넣는다 |
| total_price | INT | NOT NULL | 정가 |
| discount_amount | INT | NOT NULL, 기본 0 | `CouponUsage.reserve`가 준 할인액을 자른 값 |
| amount | INT | NOT NULL | 결제 금액 |
| user_coupon_id | BIGINT | NULL | |
| status | VARCHAR(20) | NOT NULL | 3절 |
| failure_code, failure_message | VARCHAR(80), VARCHAR(255) | NULL | FAILED 사유. 응답에 실린다 |
| confirm_requested_at | TIMESTAMP | NULL | 승인 요청 접수 시각 |
| approve_requested_at | TIMESTAMP | NULL | PG 승인 호출 시각 (markInProgress). 복구 유예·중단 기준 |
| approved_at | TIMESTAMP | NULL | PG 승인 시각. 정산 기준 |
| next_attempt_at | TIMESTAMP | NULL | 복구 다음 시도 시각. 점유에도 쓴다 |
| reconciled_at | TIMESTAMP | NULL | 최소 대사가 확인한 시각 (5절 8번) |
| recovery_started_at | TIMESTAMP | NULL | 자동 처리 중단(`give-up-minutes`) 기준 시각. 관리자 `unblock`이 다시 센다 |
| recovery_attempts | INT | NOT NULL, 기본 0 | 메트릭·백오프용 |
| recovery_blocked | BOOLEAN | NOT NULL, 기본 false | |
| last_error | VARCHAR(80) | NULL | 마지막 결과 불명 사유. 운영용 |

- UK `order_id`, UK `(pg_provider, pg_transaction_id)`
- 부분 UK `(reservation_id) WHERE status IN ('READY','IN_PROGRESS','DONE')`: 한 예매에 살아 있는 결제는 하나
- CHECK `discount_amount >= 0 AND amount = total_price - discount_amount AND amount > 0`
- 할인액 = `min(쿠폰 할인액, max(0, total_price - min-amount))`. `total_price`가 `min-amount` 이하면 쿠폰을 쓸 수 없다(`COUPON_NOT_AVAILABLE`)
- 부분 INDEX `(expires_at) WHERE status = 'READY'`
- 부분 INDEX `(next_attempt_at) WHERE status = 'IN_PROGRESS' AND recovery_blocked = false`
- 부분 INDEX `(updated_at) WHERE status = 'FAILED' AND approve_requested_at IS NOT NULL AND reconciled_at IS NULL`: 최소 대사

### refund

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | |
| payment_id | BIGINT | FK → payment, NOT NULL, UK | 결제 하나에 환불 행 하나 (1차는 전액) |
| reservation_id | BIGINT | NOT NULL | 조회용 |
| refund_amount | INT | NOT NULL | 사용자 환불은 결제 금액, AUTO_VOID는 PG 승인 금액 |
| reason | VARCHAR(30) | NOT NULL | USER_CANCEL / SCHEDULE_CANCELED / CONFIRM_FAILED / AUTO_VOID |
| status | VARCHAR(20) | NOT NULL | 3절 |
| cancel_attempt | INT | NOT NULL, 기본 0 | PG 취소 키 세대. 관리자 재시도 때만 올린다 |
| pg_cancel_id | VARCHAR(200) | NULL | |
| failure_code, failure_message | VARCHAR(80), VARCHAR(255) | NULL | |
| requested_at | TIMESTAMP | NULL | 처음 REQUESTED가 된 시각. 중단 기준 |
| refunded_at | TIMESTAMP | NULL | 정산 기준 |
| next_attempt_at, recovery_started_at, recovery_attempts, recovery_blocked, last_error | | | payment와 같음 |

- 부분 INDEX `(next_attempt_at) WHERE status = 'REQUESTED' AND recovery_blocked = false`

### user_coupon (쿠폰 담당 6번 소유)

테이블·엔티티·`schema.sql`은 쿠폰 담당이 만든다. 결제가 필요로 하는 것은 아래 두 가지와 `CouponUsage`(6절, 9절)다.

| 요청 사항 | 이유 |
|---|---|
| status에 `RESERVED` | PG 호출 전에 쿠폰을 잡아 동시 사용을 막는다 |
| `payment_id BIGINT NULL` | 잡은 결제. `use`·`release`가 결제 ID로 쿠폰을 찾는다 |

`payment.user_coupon_id`와 `user_coupon.payment_id`는 같은 스키마지만 소유자가 달라 FK를 두지 않고 값만 저장한다.

### fake-pg `pg_transaction` (`pg_schema`)

`id`, `provider`, `transaction_id`(UK), `order_id`, `amount`, `status`(APPROVED / CANCELED), `approved_at`, `canceled_at`. 토스 흉내의 멱등 키 응답 저장은 별도 테이블(`idempotency_key`, 응답, 만료 시각)에 둔다. 대사의 상대편 기록이다. 장애 설정은 메모리에 두고 관리 API로 바꾼다.

Kafka 전환 시 `outbox_event`, `processed_event`, 정산 확장 시 `settlement`를 추가한다.

## 8. API

### 내부 (payment 소유. 모두 멱등. `ApiResponse`로 감싸지 않음)

| Method | URI | 요청 | 응답 |
|---|---|---|---|
| POST | `/internal/payments` (준비) | orderId, reservationId, userId, sellerId, performanceId, scheduleId, totalPrice, couponId, pgProvider, expiresAt | 200 paymentId, orderId, amount, `status` = READY / FAILED, failureCode, checkoutUrl(카카오). 409 `PAYMENT_ORDER_CONFLICT`, `PAYMENT_ALREADY_IN_PROGRESS`, `PAYMENT_ALREADY_DONE`, `PAYMENT_CHECKOUT_CONFLICT`, 400 `COUPON_NOT_AVAILABLE` |
| POST | `/internal/payments/{orderId}/confirm` (승인) | pgAuthToken(토스 paymentKey, 카카오 pg_token), amount | 200 paymentId, `status` = DONE / FAILED / IN_PROGRESS (Kafka는 ACCEPTED), failureCode. 400 `PAYMENT_AMOUNT_MISMATCH`, 404 기록 없음 |
| GET | `/internal/payments/{orderId}` | | 200 결제 상태, `confirmRequestedAt`, 환불 상태와 사유 / 404 기록 없음 |
| POST | `/internal/payments/{orderId}/refund` | reason | 200 refundStatus = WAITING / REQUESTED / DONE / FAILED / SKIPPED / NOT_REQUIRED |

관리자용 (사람이 PG 상태를 확인한 뒤 호출한다. Gateway에 노출하지 않는다):

| Method | URI | 하는 일 |
|---|---|---|
| POST | `/internal/admin/payments/{orderId}/unblock` | 차단된 IN_PROGRESS 결제나 REQUESTED 환불의 `recovery_blocked = false`, `recovery_started_at = now`, `next_attempt_at = now`. 다른 상태면 409 |
| POST | `/internal/admin/payments/{orderId}/refund/retry` | FAILED 환불을 REQUESTED로 되돌리고 `cancel_attempt + 1`, `recovery_blocked = false`, `recovery_started_at = now`, `next_attempt_at = now` |
| POST | `/internal/admin/payments/{orderId}/resolve` | 차단된 IN_PROGRESS 결제를 PG 콘솔 확인 결과(`DONE` 또는 `FAILED`, 근거)로 확정한다. 4절 10) complete와 같은 규칙으로 쿠폰·환불을 처리하고 `last_error`에 근거를 남긴다 |

업무 결과(승인·거절·불명)는 본문 `status`로 돌려준다. 4xx는 잘못된 요청과 위에 적은 충돌에만 쓴다.

### 외부 (reservation 소유. 참고용)

| Method | URI | 요청 |
|---|---|---|
| POST | `/api/v1/reservations/{id}/payments/checkout` | pgProvider, couponId |
| POST | `/api/v1/reservations/{id}/payments/confirm` | orderId, pgAuthToken |
| POST | `/api/v1/reservations/{id}/cancel` | reason |

응답 형식(HTTP 상태 또는 본문의 예매 상태)은 reservation 담당이 정한다(#9).

### fake-pg

경로·필드는 각 PG 공식 문서 형식을 따른다.

| 구분 | API |
|---|---|
| 토스 흉내 | 결제창 대체(paymentKey 발급), 승인, 주문번호 조회, 취소. 승인·취소는 `Idempotency-Key`를 받고 같은 키는 업무 응답(성공·거절)을 그대로 재현한다. 5xx·429는 저장하지 않는다 |
| 카카오 흉내 | 준비(tid), 사용자 승인 대체(pg_token 발급), 승인, 조회, 취소. 멱등 키 없음. 이미 취소된 결제의 재취소는 오류 |
| 관리 | PG별 장애 설정(지연, 실패율, 승인 후 응답 유실률, 승인 처리 지연, 초당 처리 한도), 거래 기록 조회 |

"결제창 대체"와 "사용자 승인 대체"는 브라우저 단계를 대신하는 fake-pg 전용 API다. 이 덕분에 k6로 결제 흐름 전체를 자동화한다. "승인 처리 지연"은 응답 후 늦게 승인되는 경우(늦은 승인)를 재현한다.

## 9. 다른 담당과의 계약

| 상대 | 맞출 것 | 시점 |
|---|---|---|
| 예매 (2번) | #9 orchestration.md와 대조해 확정한다. 4절에서 reservation이 하는 일, 8절 API, 아래 "예매가 결제 결과를 모를 때" 표, 6절 타임아웃 예산, `expiresAt` 값 | 1주차 초 |
| 쿠폰 (6번) | user_coupon은 쿠폰 담당 소유이고 payment 스키마에 둔다, `RESERVED`·`payment_id` 추가, `CouponUsage` 계약(아래), 트랜잭션 전파 `REQUIRED`, 환불 시 쿠폰 반납 정책 | 1주차 |
| 공연 (5번) | 결제 요청에 들어갈 `seller_id`, `performance_id`, `schedule_id` | 1주차 |
| 대기열 (4번) | 결제 처리 용량 기반 입장 속도 | 트러블슈팅 |

서비스마다 DTO를 따로 두고 계약 문서로 필드를 맞춘다. Feign 본문과 Kafka 메시지 필드는 같다. 환불 결과 이벤트에는 사유(`reason`)와 결제 상태를 싣고, 예매는 `AUTO_VOID` 환불 이벤트를 예매 상태 변경 없이 무시한다.

### CouponUsage 계약

| 메서드 | 동작 | 실패 |
|---|---|---|
| `reserve(couponId, userId, paymentId, totalPrice)` | AVAILABLE이고 본인 쿠폰이면 RESERVED + payment_id, 할인액 반환 | 사용 불가면 `COUPON_NOT_AVAILABLE` (결제는 PG를 부르지 않는다) |
| `use(paymentId)` | RESERVED → USED | RESERVED이고 payment_id가 같으면 실패하지 않는다. 그사이 유효 기간이 지났어도 USED로 바꾼다. 잡힌 쿠폰이 없으면 아무것도 하지 않는다 |
| `release(paymentId)` | RESERVED·USED → AVAILABLE (유효 기간이 지났으면 EXPIRED) | 잡힌 쿠폰이 없으면 아무것도 하지 않는다 |

쿠폰 쪽 만료 배치·관리 작업은 RESERVED 쿠폰을 바꾸지 않는다. RESERVED는 결제가 끝날 때(`use`·`release`)만 풀린다. 이 약속이 깨지면 승인된 결제의 complete가 롤백된다.

### 예매가 결제 결과를 모를 때

승인 요청이나 그 응답이 중간에 끊기면 예매는 PAYMENT_IN_PROGRESS로 남는다. 예매는 `GET /internal/payments/{orderId}`로 조회하고 결과에 따라 마무리한다. 예매 복구 잡도 오래된 PAYMENT_IN_PROGRESS를 같은 표로 처리한다.

| payment 결과 | 뜻 | 예매가 할 일 |
|---|---|---|
| DONE | 승인 확정 | CONFIRMED, 좌석 SOLD. 확정할 수 없으면 CONFIRM_FAILED 환불 요청 |
| FAILED | 돈이 오가지 않음으로 확정 | HOLD로 복귀 (선점 만료 후면 만료) |
| IN_PROGRESS | PG 결과를 모름. payment 복구가 확정한다 | 기다린다 |
| READY | 승인 요청이 payment에 도착하지 못했거나 아직 처리 전이다 | `confirmRequestedAt`이 없거나 1분 넘게 지났으면 저장한 pgAuthToken으로 같은 승인 요청을 다시 보낸다(Kafka에서 기다리는 건을 중복 발행하지 않기 위해). READY만 보고 HOLD로 되돌리지 않는다(늦게 도착한 승인과 경쟁한다) |
| 404 | 준비조차 기록되지 않았다 | 예매 담당과 결정(#9는 알림만, 이 문서는 HOLD 복귀 제안) |

## 10. 열린 질문

1. 판매자(SELLER) 역할을 1차에 넣는가 (정산 대상 유무)
2. 쿠폰 할인 부담 주체 (정산 기준이 정가인가 결제액인가)
3. 결제 허용 시각(`expiresAt`): 선점 만료 그대로인가, 결제창 진입 시 연장하는가. 연장한다면 payment의 `expires_at`도 갱신하는 API가 필요하다
4. 환불 시 쿠폰 반납 정책 (팀 결정 전. 이 문서는 "유효 기간이 남았으면 AVAILABLE"을 기본안으로 둔다)
5. 실제 토스가 `Idempotency-Key`에 어떤 응답(5xx·429 포함)을 저장하는지. fake-pg는 업무 응답만 저장한다고 가정한다. Task 21 샌드박스에서 확인한다
6. fake-pg 처리 한도·지연 기본값, 복구 설정값 (2절 설정의 기본값은 임시다)
