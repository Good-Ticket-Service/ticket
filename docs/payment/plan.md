# 결제·환불(payment-service) 구현 계획

공연 좌석 예매 MSA에서 결제·환불을 맡는다. 1차 MVP(10/27~11/6)는 서비스 간 통신을 동기(Feign)로 구현하고, 트러블슈팅 기간(11/9~11/16)에 부하·장애 실험으로 한계를 잰 뒤 Kafka 비동기로 개선해 전후를 수치로 비교한다. 이후 대사·정산으로 확장한다.

| 문서 | 내용 | 읽는 때 |
|---|---|---|
| plan.md (이 문서) | 무엇을 어떻게 만드는가: 구조, 상태, 흐름, 복구, 구현 규칙 | 구현 전 항상 |
| [tables.md](tables.md) | ERD와 테이블 명세 | 엔티티·스키마를 만들 때 |
| [api.md](api.md) | 외부 API(없음), fake PG API | PG 어댑터·테스트를 만들 때 |
| [shared/internal-api/payment.md](../shared/internal-api/payment.md) | 예매와의 내부 API 계약 | 컨트롤러·예매 연동을 만들 때 |
| [shared/modules/coupon-usage.md](../shared/modules/coupon-usage.md) | 예매·쿠폰 책임 이전 계약 | 책임 경계를 확인할 때 |
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
| fake-pg, 토스·카카오 어댑터, 멱등 결제·환불, 결과 불명 복구(1분 시한), 늦은 승인 자동 취소와 최소 대사, 예매가 확정한 할인액 검증, 메트릭, 동기 기준선 측정 | 장애 격리, Kafka 부하 평탄화, Kafka 결과 이벤트(Outbox), 대량 환불 | 전체 대사, 정산, 샌드박스 실연동 |

범위 밖: 부분 환불·취소 수수료, PG 자동 전환, 판매자 지급(송금). 취소 가능 기간은 예매가 검사하고, 결제는 요청받은 결제 금액 전액을 환불한다.

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
  min-amount: 100           # PG 최소 결제 금액. 미달한 요청은 보정 없이 거절한다
  recovery:
    enabled: true           # 테스트에서 끄고 직접 호출한다
    delay-ms: 5000          # 잡 실행 간격
    batch-size: 50
    payment:
      check-interval-seconds: 10     # 승인 호출 후 첫 조회와 반복 조회 간격. 결과 확정 시한 안에 여러 번 본다
      not-found-grace-seconds: 60    # 승인 호출 후 이 시간이 지나야 "승인 기록 없음"을 거절로 확정한다
      result-deadline-seconds: 60    # 승인 호출 후 이 시간이 지나도 모르면 FAILED(PG_UNRESOLVED)로 확정한다
    refund:
      backoff-max-seconds: 300
      give-up-minutes: 60   # 환불 요청 후 이 시간이 지나도 모르면 차단한다
  reconcile:
    delay-minutes: 1        # 최소 대사 실행 간격
    checkpoints-minutes: [11, 1440]  # FAILED 확정 후 11분, 24시간에 한 번씩 조회한다
pg:
  connect-timeout-ms: 2000
  read-timeout-ms: 10000
  toss.base-url: http://fake-pg:8090
  kakao.base-url: http://fake-pg:8090
```

### 패키지

```
com.sparta.ticket.payment
├── presentation/internal   InternalPaymentController, InternalAdminController(환불 차단 해제·재시도)
├── application
│   ├── PaymentService, PaymentProcessor, RefundService   흐름 지휘. 트랜잭션 없음
│   ├── PaymentTransactions, RefundTransactions           @Transactional 메서드만. 상태 전이 하나 = 메서드 하나
│   ├── PaymentRecoveryJob, RefundRecoveryJob, PaymentReconcileJob   @Scheduled. 흐름 클래스를 부른다
│   ├── port/   PgClient, PaymentDispatcher, PaymentResultPublisher
│   ├── dto/    PaymentCommand, PaymentResult, RefundResult
│   # 쿠폰 모듈은 reservation-service로 이전한다. 결제에는 쿠폰 포트·구현을 두지 않는다
├── domain      Payment, Refund, PaymentStatus, RefundStatus, PgProvider, PgResult, PgInquiry, 저장소 인터페이스
│   # 쿠폰 엔티티는 reservation-service 소유다
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
| READY | 준비됨. 결제 금액이 확정됐고 승인 요청을 기다린다. PG 승인은 아직 부르지 않았다 | IN_PROGRESS, FAILED |
| IN_PROGRESS | PG 승인을 불렀고 결과를 모른다. 복구 재개 지점이다 | DONE, FAILED |
| DONE | 승인 확정. 금액·PG 정보는 바뀌지 않는다 | CANCELED |
| FAILED | 이 결제 시도를 끝냈다 (종료). 승인 전 종료·거절이면 돈이 오가지 않았다. 결과 불명으로 끝낸 결제(`PG_UNRESOLVED`)에 늦은 승인이 발견되면 자동 취소 환불이 붙는다(4절) | - |
| CANCELED | 전액 환불 완료 (종료) | - |

FAILED의 `failure_code`: `SUPERSEDED`(새 준비로 대체), `READY_FAILED`(카카오 준비 실패), `EXPIRED`(결제 허용 시각 경과), `ABANDONED`(승인 전 예매 취소), `PG_UNAVAILABLE`(연결 실패·서킷 열림), `PG_BUSY`(429), `INVALID_AUTH_TOKEN`(인증값 재사용·조작), `NOT_APPROVED`(조회로 승인 없음 확정), `PG_UNRESOLVED`(승인 호출 후 결과 확정 시한 1분 안에 결과를 모름), `AMOUNT_MISMATCH`(승인 금액 불일치, AUTO_VOID 동반), `CANCELED_AT_PG`(조회해 보니 PG에서 이미 취소됨), `PG_REJECTED`(PG 업무 거절. 원본 코드는 `pg_failure_code`에 둔다).

응답에는 예매가 다음 행동을 고를 수 있게 `failureType`을 함께 싣는다.

| failureType | failure_code | 예매가 할 일 |
|---|---|---|
| `REJECTED` | `PG_REJECTED`, `AMOUNT_MISMATCH`, `INVALID_AUTH_TOKEN` | 사용자가 다른 수단으로 다시 결제 |
| `UNAVAILABLE` | `PG_UNAVAILABLE`, `PG_BUSY`, `PG_UNRESOLVED` | 잠시 후 같은 수단으로 다시 시도 |
| `EXPIRED` | `EXPIRED` | 선점이 끝났으면 만료, 아니면 다시 준비 |
| `NOT_APPROVED` | `NOT_APPROVED`, `CANCELED_AT_PG` | 결제 안 됨으로 확정. 다시 결제 가능 |
| `CLOSED` | `SUPERSEDED`, `ABANDONED`, `READY_FAILED` | 이 시도는 끝. 필요하면 새 준비 |

**결제는 쿠폰 상태를 바꾸지 않는다.** 쿠폰은 reservationId에 연결한다. 예매는 결제 DONE을 확인해 쿠폰을 사용하고, FAILED로 HOLD에 돌아가면 유지한다. EXPIRED·RELEASED 때 해제하고, 환불 접수 후 CANCELED 확정 때 같은 로컬 트랜잭션에서 복원한다 (#27 제안). 결제 상태와 쿠폰 상태는 서로 다른 로컬 트랜잭션이다.

| refund | 의미 | 다음 |
|---|---|---|
| WAITING | 환불 요청을 받았는데 결제가 아직 IN_PROGRESS다. 결제가 확정되면 이어서 처리한다 | REQUESTED, SKIPPED |
| REQUESTED | PG 취소를 불렀거나 부를 차례이고 결과를 모른다. 복구 재개 지점이다 | DONE, FAILED |
| DONE | 취소 확정 (종료). 결제가 DONE이었으면 같은 트랜잭션에서 CANCELED |  - |
| FAILED | PG가 취소를 업무상 거절했다. 자동 재시도하지 않고 차단·경보로 남긴다 | REQUESTED (관리자 재시도) |
| SKIPPED | WAITING이었는데 결제가 FAILED로 확정돼 환불할 돈이 없다 | REQUESTED (늦은 승인 발견) |

- **환불 보장(`ensureRefund`)**: PG에 돈이 잡혀 있다고 확인될 때(결제 DONE + 환불 요청, 늦은 승인, 금액 불일치) 부르는 단일 규칙이다. 환불 행이 없으면 REQUESTED로 만들고, WAITING·SKIPPED면 REQUESTED로 바꾸고, REQUESTED·DONE·FAILED면 그대로 둔다. 기존 사유는 유지하고 행이 새로 생길 때만 `AUTO_VOID`를 쓴다. 그래서 AUTO_VOID와 사용자 환불이 겹쳐도 행은 하나다.
- 상태 전이는 엔티티 메서드에서만 일어난다. 메서드는 현재 상태를 검사하고 맞지 않으면 공통 `INVALID_STATUS_TRANSITION`을 던진다.
- 결과 불명은 상태가 아니라 `recovery_attempts`, `next_attempt_at`, `recovery_started_at`, `last_error`로 표현한다. 결제는 차단하지 않는다. PG 승인 호출 후 1분(`result-deadline-seconds`) 안에 결과를 모르면 FAILED(`PG_UNRESOLVED`)로 확정하고 예매가 남은 선점 시간에 따라 HOLD로 복귀하거나 EXPIRED로 종료하게 하며, 실제로 승인됐다면 최소 대사가 찾아 자동 취소 환불한다. 환불만 `recovery_blocked`로 자동 처리를 멈추고 사람이 확인한다.
- Orders의 `recovery_phase`는 두지 않는다. 외부 호출 단계가 결제는 승인 하나, 환불은 취소 하나라 상태 자체가 재개 지점이다.

## 4. 처리 흐름

### 결제 (1차, reservation이 지휘)

토스와 카카오 모두 사용자 인증 결과(토스 `paymentKey`, 카카오 `pg_token`)가 있어야 승인할 수 있다. 그래서 준비와 승인을 따로 요청한다.

```
① 준비  사용자 → reservation: checkout {pgProvider, userCouponId}
        reservation: HOLD 확인, 새 orderId 저장, 쿠폰 예약·할인액 확정 → POST /internal/payments {totalPrice, discountAmount}
        payment checkout:
          1) order_id로 기존 결제 조회. 있으면 내용 비교 후 재현(같음) / 409 PAYMENT_ORDER_CONFLICT(다름)
             재현할 결제가 카카오 READY인데 tid가 없으면(준비 직후 중단) 3)부터 다시 한다
          2) tx prepare:
             - 같은 예매의 이전 READY를 FAILED(SUPERSEDED)로 바꾼다. 쿠폰 정리는 예매 책임이다
             - 같은 예매에 IN_PROGRESS·DONE 결제가 있으면 409 PAYMENT_ALREADY_IN_PROGRESS / PAYMENT_ALREADY_DONE
               승인 요청이 이미 접수된(confirm_requested_at이 있는) READY도 대체하지 않고 409 PAYMENT_ALREADY_IN_PROGRESS
             - 예매가 보낸 discountAmount >= 0, totalPrice - discountAmount >= 100 검증. 할인액을 보정하지 않는다
             - 새 Payment READY(금액 확정, expires_at) 커밋
             order_id UK 충돌이면 이긴 행을 1)처럼 재현, reservation 부분 UK 충돌(동시 준비)이면 409 PAYMENT_CHECKOUT_CONFLICT
          3) PgClient.ready (트랜잭션 밖. 카카오만 PG 호출)
             카카오 성공 → tx: tid·결제창 URL 저장 / 실패·불명 → tx: FAILED(READY_FAILED)
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
                       recovery_started_at, next_attempt_at = now + check-interval-seconds
             FAILED면 PG를 부르지 않는다
          9) PgClient.approve(저장된 amount로) (트랜잭션 밖)
         10) tx complete: payment 잠금 후 (환불 행은 그다음에 잠근다)
             IN_PROGRESS + Approved(금액 일치) → DONE, WAITING 환불이 있으면 ensureRefund. 쿠폰 사용은 예매가 결과 확인 후 처리
             IN_PROGRESS + Approved(금액 불일치) → FAILED(AMOUNT_MISMATCH) + ensureRefund(승인 금액)
             IN_PROGRESS + Rejected → FAILED, WAITING 환불은 SKIPPED
             IN_PROGRESS + Unknown → 상태 유지, recovery_attempts + 1, last_error (결과 확정 시한 1분은 복구 잡이 본다)
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
     READY       → FAILED(ABANDONED). 환불 행을 만들지 않고 응답 NOT_REQUIRED
     FAILED      → 환불 행이 없으면 응답 NOT_REQUIRED
     IN_PROGRESS → Refund WAITING. 결제가 확정될 때 4절 10)이 이어서 처리한다
     DONE        → ensureRefund (REQUESTED)
     CANCELED    → 기존 환불 재현
     동시 요청이 UK에 걸리면 이긴 행을 재현한다
  3) REQUESTED면 커밋 후 바로 PgClient.cancel(PG 키 = orderId + ":cancel:" + cancel_attempt) (트랜잭션 밖)
  4) tx complete: payment → refund 순서로 잠근 뒤 refund가 REQUESTED일 때만
     성공 → refund DONE (+ 결제가 DONE이면 CANCELED. 쿠폰 복원은 이 시점이 아니라 예매의 CANCELED 확정 시 처리)
     업무 거절 → FAILED + recovery_blocked + 경보 (관리자 refund/retry)
     일시 장애·불명 (연결 실패, 429, 타임아웃, 5xx) → REQUESTED 유지, 복구가 같은 키로 다시 보낸다
```

- REQUESTED가 되는 모든 전이는 `requested_at`(처음일 때), `recovery_started_at`, `next_attempt_at`을 같이 설정한다. 요청 경로는 커밋 뒤 바로 cancel을 부르므로 `next_attempt_at = now + 점유 기간`, 그 밖의 경로(4절 10)의 ensureRefund, 대사)는 `next_attempt_at = now`로 두어 복구 잡이 부른다.
- `refund_amount`: 사용자 환불(`USER_CANCEL`)·회차 취소(`SCHEDULE_CANCELED`)·확정 실패(`CONFIRM_FAILED`)는 결제 금액 전액, AUTO_VOID는 PG가 승인한 금액이다. 취소 가능 기간·수수료는 보지 않는다(예매가 요청 전에 검사한다).
- 자동 취소 환불(`AUTO_VOID`)이 붙은 결제는 FAILED 그대로다. refund DONE이면 "잡혔던 돈을 돌려줌"이다. 예매가 HOLD로 복귀하면 쿠폰을 유지한다. 쿠폰은 예매 종료·취소 정책에 따라 처리하며 AUTO_VOID는 쿠폰을 바꾸지 않는다.

## 5. 복구

Orders의 `OrderRecoveryJob` 구조를 따른다.

1. `@Scheduled(fixedDelay)` 잡이 `@ConditionalOnProperty(payment.recovery.enabled)`로 켜진다.
2. 후보는 세 종류다. `batch-size`건씩, 오래된 순으로 고른다. 대사 잡(8번)도 같은 방식으로 후보를 고르고 점유한다.
   - `expires_at`이 지난 READY
   - `next_attempt_at`이 지난 IN_PROGRESS
   - `recovery_blocked = false`이고 `next_attempt_at`이 지난 refund REQUESTED
3. **점유:** 짧은 트랜잭션에서 `FOR UPDATE SKIP LOCKED`로 후보를 잠그고 `next_attempt_at`을 미룬 뒤 바로 커밋한다. 다른 인스턴스는 갱신된 시각 때문에 같은 행을 잡지 않는다. PG 호출은 그 뒤 트랜잭션 밖에서 하고, 결과 저장은 4절 complete처럼 다시 잠근 뒤 상태를 확인한다.
   - 결제: `next_attempt_at = now + check-interval-seconds`(10초). 결과 확정 시한(1분) 안에 여러 번 봐야 하므로 백오프하지 않는다. PG 최대 호출 시간(connect 2초 + read 10초)보다 짧아 조회가 겹칠 수 있지만, 조회는 읽기이고 결과 저장은 잠근 뒤 IN_PROGRESS일 때만 하므로 안전하다.
   - 환불: `next_attempt_at = now + max(점유 기간 30초, 백오프)`. 같은 취소를 겹쳐 보내지 않게 점유 기간을 PG 최대 호출 시간보다 길게 둔다. 백오프는 `min(5초 × 2^recovery_attempts, backoff-max-seconds)`다.
4. 처리:
   - READY: PG를 부르지 않고 FAILED(`EXPIRED`)
   - IN_PROGRESS: PG에 **조회**한다(승인을 다시 보내지 않는다)
     - Approved → 4절 10) complete
     - Canceled → FAILED(`CANCELED_AT_PG`). 돈은 PG에서 이미 돌려줬다
     - NotFound → `approve_requested_at`부터 `not-found-grace-seconds`(60초)가 지났으면 FAILED(`NOT_APPROVED`), 아니면 Unknown과 같이 다음 시도
     - Unknown → `approve_requested_at`부터 `result-deadline-seconds`(60초)가 지났으면 FAILED(`PG_UNRESOLVED`), 아니면 `recovery_attempts + 1`, 다음 시도
     - FAILED로 확정할 때는 4절 10)의 Rejected처럼 WAITING 환불은 SKIPPED. 예매는 HOLD 복귀 시 쿠폰을 유지하고 EXPIRED·RELEASED 때 해제
   - refund REQUESTED: 같은 PG 키로 cancel을 다시 보낸다(2절 `cancel` 규칙으로 안전하다). 결과는 4절 환불 4)와 같다
5. 후보 하나의 예외는 로그만 남기고 다음 후보로 넘어간다.
6. **결제는 차단하지 않는다.** 결과 확정 시한이 지나면 4번처럼 FAILED(`PG_UNRESOLVED`)로 끝내고 경고 로그와 메트릭(`payment_unresolved_total`)을 남긴다. 그래야 결과를 모르는 결제가 좌석과 1인 예매 한도를 오래 묶지 않는다. 늦은 승인은 8번 최소 대사가 돌려준다.
7. **환불 차단:** refund는 `recovery_started_at`부터 `give-up-minutes`가 지나도 확정되지 않으면 REQUESTED 그대로 `recovery_blocked = true`, 경고 로그와 메트릭을 남긴다. 횟수가 아니라 시간으로 정해서, 짧은 PG 장애에 대량 차단되지 않게 한다. 관리자가 PG 상태를 확인한 뒤 관리자 API(`refund/unblock`)로 다시 자동 처리에 넣는다. 업무 거절(FAILED)은 `refund/retry`가 새 키 세대로 다시 보낸다.
8. **최소 대사:** `PaymentReconcileJob`이 PG 승인을 실제로 호출했는데 FAILED로 끝난 결제(`approve_requested_at`이 있는 건, `PG_UNRESOLVED` 포함)를 FAILED 확정 11분 뒤와 24시간 뒤에 PG에 조회한다(`checkpoints-minutes`). 11분은 토스 인증값 유효 시간(약 10분)이 지나 더는 승인될 수 없는 시점이다. Approved면 4절 10)의 늦은 승인처럼 ensureRefund + 경보, NotFound·Canceled면 다음 시점으로 넘긴다. 확정된 결과일 때만 `reconcile_count`를 올리고 `next_reconcile_at`을 다음 시점으로 옮기며, 마지막 시점이 끝나면 `next_reconcile_at`을 비운다. Unknown이면 그대로 두고 다음 주기에 다시 보며, 한 시점에서 24시간 넘게 Unknown이면 경보를 남긴다.
   - **가정:** 토스는 인증 후 약 10분 안에 승인해야 하므로, FAILED 확정 24시간 뒤에도 승인되지 않은 거래는 이후에도 승인되지 않는다고 본다. 실제 PG의 보장 범위는 샌드박스·문서로 확인한다(10절). 이 가정 밖의 누락은 전체 대사(Task 19)와 정산 내역 비교가 마지막으로 찾는다. PG 승인을 부르지 않은 FAILED(만료, 대체, 서킷 등)는 돈이 빠질 수 없어 대상이 아니다.

| 실패 지점 | 처리 |
|---|---|
| 준비 요청 유실 | 기록 없음 또는 READY. reservation이 같은 orderId로 다시 준비하면 재현된다 |
| 카카오 준비 성공 후 tid 저장 전 중단 | tid 없는 READY. 같은 orderId 재준비 때 카카오 준비를 다시 부른다. 재준비가 없으면 만료 |
| 카카오 준비 결과 불명 | FAILED(`READY_FAILED`). 돈이 오가지 않았고 tid를 사용자에게 주지 않았으므로 안전하다 |
| 사용자가 결제창을 닫음 | READY가 `expires_at`까지 남았다가 FAILED(`EXPIRED`) |
| 승인 요청이 payment에 도착하지 못함 | READY. reservation이 저장한 인증값으로 같은 승인 요청을 다시 보낸다(9절) |
| 승인 요청이 만료 후 도착 (Kafka 대기 포함) | 4절 8)에서 FAILED(`EXPIRED`). PG를 부르지 않는다 |
| 같은 결제의 승인 요청이 중복으로 옴 (재전송, Kafka 중복) | 4절 4)·8)의 잠금과 READY 확인에서 걸려 현재 상태만 재현한다 |
| markInProgress 후 PG 호출 전 중단 | IN_PROGRESS. 조회가 NotFound를 돌려주고, 유예 시간이 지나면 FAILED(`NOT_APPROVED`) |
| PG 승인 응답 유실·타임아웃 | IN_PROGRESS → 복구가 조회로 확정 |
| 조회가 "없음"인데 승인이 늦게 처리됨 | 유예 시간(1분) 안이면 아직 확정하지 않는다. 유예 뒤 FAILED가 된 다음 승인되면 최소 대사(8번)가 찾아 AUTO_VOID로 돌려준다 |
| PG 조회도 계속 실패 | 결과 확정 시한(1분) 뒤 FAILED(`PG_UNRESOLVED`). 실제로 승인됐다면 최소 대사가 찾아 AUTO_VOID로 돌려준다. 결제는 쿠폰 상태를 바꾸지 않는다 |
| 조회해 보니 PG에서 이미 취소됨 | FAILED(`CANCELED_AT_PG`) |
| complete 저장 실패 | IN_PROGRESS로 남아 복구가 조회로 다시 확정 |
| 환불 PG 일시 장애·응답 유실 | REQUESTED 유지 → 복구가 같은 키로 다시 보낸다 |
| 환불 PG 업무 거절 | FAILED + 차단 + 경보. 관리자 재시도는 `cancel_attempt + 1`로 새 PG 키를 쓴다(토스는 같은 키의 거절 응답을 15일간 재현한다) |
| 환불 결과가 계속 불명 | `give-up-minutes` 뒤 REQUESTED 그대로 차단. 관리자가 PG 상태를 확인하고 `refund/unblock`으로 다시 넣는다 |
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
| PG 계약상 "처리하지 않았음"이 보장되는 429 | Rejected(`PG_BUSY`). fake PG는 이렇게 정의했다. 실제 PG는 문서로 확인되기 전까지 Unknown으로 둔다 |
| PG 업무 거절 코드 (잔액 부족, 카드 거절, 취소 불가 등) | Rejected(`PG_REJECTED`, PG 원본 코드) |
| 읽기 타임아웃, 쓰기 후 연결 끊김, 5xx, 프록시가 보낸 오류, 해석 불가 응답, "이미 처리됨" | Unknown |

  - Feign은 연결 실패와 읽기 타임아웃을 같은 `RetryableException`으로 감싼다. 어댑터는 원인 예외(`ConnectException`, `SocketTimeoutException`의 단계)를 보고 구분하고, 구분할 수 없으면 Unknown이다.
  - 환불에서는 Rejected(`PG_UNAVAILABLE`·`PG_BUSY`)도 일시 장애로 보고 REQUESTED를 유지한다. 업무 거절 코드만 FAILED다.
  - 카카오는 승인 응답의 금액도 저장 금액과 대조한다(토스는 PG가 대조한다).
  - 취소의 "이미 취소됨"은 업무 거절이 아니다. 어댑터가 조회해 Canceled면 성공이다.
  - Rejected는 "확실히 승인되지 않았다"일 때만 쓴다. 조금이라도 처리됐을 가능성이 있으면 Unknown이다.
- **자동 재시도 없음:** PG 승인·취소 클라이언트는 자동 재시도를 끈다(`Retryer.NEVER_RETRY` 또는 같은 설정). 재시도는 복구 잡만 한다. PG 조회는 GET이라 팀 `IdempotentRetryConfig`를 붙여도 된다.
- **타임아웃 예산:** PG connect 2초 + read 10초, payment DB 커넥션 대기(Hikari `connection-timeout`) 2초로 payment의 승인 응답이 15초 안에 끝나게 한다. payment의 최악 응답이 약 14초이므로 reservation의 payment 호출 read timeout은 20초로 두고(15초는 여유가 부족하다) Gateway(60초)보다 짧게 둔다.
- **동시 처리:** 같은 결제를 두 곳에서 처리하지 않게 하는 수단은 DB 잠금과 5절 점유다. 여러 인스턴스(`--scale payment-service=2`)에서도 성립해야 한다.
  - 점유 기간(30초)을 넘겨 한 호출이 멈추면(GC 정지 등) 같은 취소가 두 번 나갈 수 있다. 전액 취소에서는 토스는 멱등 키, 카카오는 "이미 취소됨" 응답 후 조회로 막히고, 결과 저장은 REQUESTED일 때만 하므로 돈 사고는 없다. 부분 환불을 도입하면 이중 취소가 실제 금액 오류가 되므로 그때 실행 소유자(lease owner)나 fencing token을 둔다.
- **감사 컬럼:** 작성자는 팀 `HeaderAuditorAware`가 요청 헤더로 정하고, 요청 밖 작업은 `SYSTEM`으로 남긴다. 결제 주체는 `user_id`로 추적한다.
- **응답 값 형식:** 내부 API 응답의 ID는 숫자, 시각은 `+09:00`을 붙인 ISO 8601이다(`OffsetDateTime`으로 내보낸다). DB와 엔티티는 KST `TIMESTAMP`·`LocalDateTime`이다.
- **쿠폰 책임:** reservation-service의 예매·쿠폰 모듈이 처리한다. 결제는 쿠폰 포트·테이블에 접근하지 않는다. 할인액은 준비 요청에 고정해 저장·멱등 비교한다.
- **결과 이벤트 (Kafka 전환 후):** 결제·환불의 모든 확정 전이(DONE, FAILED, CANCELED, refund DONE·FAILED·SKIPPED)는 같은 트랜잭션에서 Outbox에 쓴다. 요청 경로든 복구 잡이든 같다.
- **승인 명령 (Kafka 전환 후):** `confirm_requested_at` 기록과 승인 명령(Outbox) 저장을 한 트랜잭션으로 묶는다. 그래야 커밋 후 발행이 실패해도 payment 안에서 다시 발행된다(예매의 재전송에 기대지 않는다). 명령에 실린 인증값은 발행이 끝나면 Outbox에서 지우고, 토픽 보관 기간은 짧게(예: 1시간) 둔다.
- **삭제:** payment·refund는 금융 기록이라 삭제 API를 두지 않는다. 팀 규칙대로 `@SQLRestriction`은 붙인다.
- **테스트 두 층:** application 테스트는 `PgClient` 포트를 메모리 픽스처로 바꿔 응답 유실·지연·늦은 승인을 주입한다(Orders `OrderClientFixture` 방식). 실제 HTTP는 어댑터 계약 테스트와 E2E에서만 fake-pg 컨테이너로 확인한다.

## 7. ERD

컬럼·제약·인덱스는 [tables.md](tables.md)에 있다. 여기에는 설계 메모만 둔다.

PostgreSQL, `BaseEntity` 감사 컬럼 6개, 시각 `TIMESTAMP`, 금액 원 단위 `INT`. 다른 서비스 ID는 FK 없이 값만 둔다. 회원·판매자 ID는 회원 서비스와 같은 `BIGINT`다.

- 시각은 팀 공통 규칙대로 `TIMESTAMP`(KST)다. 모든 컨테이너가 `TZ=Asia/Seoul`이고, PG가 준 `+09:00` 시각은 KST로 바꿔 저장한다. `TIMESTAMPTZ`로 바꾸는 것은 팀 전체 결정이다(10절).
- 금액은 원화만 다루므로 `INT`(약 21억 원까지)로 충분하다.
- 환불은 결제 하나에 행 하나다(1차 전액 환불). 부분 환불을 지원하면 refund를 환불 시도(거래) 단위로 바꾸고 `payment_id` 유니크를 푼다.
- 예매가 확정한 할인액을 저장한다. `discountAmount >= 0`, `totalPrice - discountAmount >= 100`을 검사하며 미달이면 `INVALID_PAYMENT_AMOUNT`다. 결제에서 할인액을 자르지 않는다
- 쿠폰 ID는 결제에 저장하지 않는다. 예매가 쿠폰 관계를 소유하고 결제에는 정가·할인액·최종 결제액만 남긴다.

Kafka 전환 시 `outbox_event`, `processed_event`, 정산 확장 시 `settlement`를 추가한다.

## 8. API

- 내부 API(준비·승인·조회·환불, 환불 관리자 2개): [shared/internal-api/payment.md](../shared/internal-api/payment.md)
- 외부 API: 결제 서비스에는 없다. 예매가 받는 경로는 [api.md](api.md) 1절에 참고용으로 적었다.
- fake PG API: 구현된 토스 호환 API는 [api.md](api.md) 3절에 있다. 아래는 카카오까지 포함한 계획이다.

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
| 예매 (2번) | #9 orchestration.md와 대조해 확정한다. 4절에서 reservation이 하는 일, 8절 API, 아래 "예매가 결제 결과를 모를 때"·"예매 쪽에 제안하는 보완", 6절 타임아웃 예산, `expiresAt` 값 | 1주차 초 |
| 쿠폰·예매 | reservationId 연결, HOLD 복귀 시 유지, EXPIRED·RELEASED 때 해제, CANCELED 때 복원하는 #27 제안을 검토한다. 결제에는 할인액만 전달한다 (#22) | 1주차 |
| 공연 (5번) | 결제 요청에 들어갈 `seller_id`, `performance_id`, `schedule_id` | 1주차 |
| 대기열 (4번) | 결제 처리 용량 기반 입장 속도 | 트러블슈팅 |

서비스마다 DTO를 따로 두고 계약 문서로 필드를 맞춘다. Feign 본문과 Kafka 메시지 필드는 같다. 환불 결과 이벤트에는 사유(`reason`)와 결제 상태를 싣고, 예매는 `AUTO_VOID` 환불 이벤트를 예매 상태 변경 없이 무시한다.

### CouponUsage 계약

[shared/modules/coupon-usage.md](../shared/modules/coupon-usage.md)는 예매 → 쿠폰 계약이다. 결제는 호출하지 않는다. reservationId 연결과 예매 상태 기준 해제·복원은 #27 제안에 따른다. 세부 계약 문서는 예매 담당자의 후속 PR에서 반영하며 쿠폰 담당자 검토가 남아 있다.

### 예매가 결제 결과를 모를 때

[shared/internal-api/payment.md](../shared/internal-api/payment.md)의 "예매가 결제 결과를 모를 때"에 있다.

### 예매 쪽에 제안하는 보완

결제 정합성 목표(이중 결제 0, 돈만 빠짐 0)는 예매 쪽 처리까지 맞아야 지켜진다. Task 1 계약에서 예매 담당과 정한다.

| # | 문제 | 제안 |
|---|---|---|
| 1 | 준비 후 예매 상태 저장에 실패하면 payment에 READY가 남을 수 있다. 쿠폰 점유는 예매가 자신의 로컬 트랜잭션과 복구로 관리한다 | 예매가 orderId를 **먼저** 기록(트랜잭션)한 뒤 payment 준비를 부른다. 같은 요청의 재시도는 같은 orderId를 쓰고, payment 조회가 404면 "준비 안 됨"으로 보고 다시 준비한다 |
| 2 | **환불은 끝났는데 예매 DB 갱신이 실패하면 예매가 CONFIRMED로 남아 환불받고도 티켓을 쓸 수 있다** | **결정**: CONFIRMED → CANCELING(티켓 사용 불가) 커밋 → payment 환불 요청 → CANCELED·좌석 해제. 예매 스케줄러가 오래된 CANCELING을 다시 처리하고, payment는 같은 orderId 키에 처음 결과를 돌려준다. 확정 실패(`CONFIRM_FAILED`)·회차 취소(`SCHEDULE_CANCELED`)도 같은 흐름이다 |
| 3 | PAYMENT_IN_PROGRESS는 선점 만료 대상이 아니라, PG 결과가 오래 불명이면 좌석이 묶인다 | **결정**: 결과를 모르는 동안 예매는 좌석을 해제하지 않는다. 대신 payment가 승인 호출 후 1분 안에 DONE 또는 FAILED(`PG_UNRESOLVED`)로 확정하므로 점유는 약 1~2분(결제 1분 + 양쪽 스케줄러 주기)이다 |
| 4 | payment 최악 응답 약 14초에 예매 read timeout 15초는 여유가 부족하다 | 20초로 늘린다 |
| 5 | 준비 요청에 `pgProvider`가 없다 | **결정**: 외부 checkout 본문과 내부 준비 요청에 넣는다 |
| 6 | 예매가 재전송용으로 `pg_auth_token`을 저장한다 | CONFIRMED·EXPIRED가 되면 지우고, 로그·응답·이벤트에 싣지 않는다(마스킹). 인증값은 일회용이고 약 10분이면 만료되므로 암호화는 1차 범위 밖으로 둔다 |

## 10. 열린 질문

1. ~~판매자(SELLER) 역할을 1차에 넣는가~~ 결정: SELLER는 판매자 회원가입으로 생기고, 결제는 정산용 `seller_id`를 1차부터 저장한다
2. 쿠폰 할인 부담 주체 (정산 기준이 정가인가 결제액인가)
3. 결제 허용 시각(`expiresAt`): 선점 만료 그대로인가, 결제창 진입 시 연장하는가. 연장한다면 payment의 `expires_at`도 갱신하는 API가 필요하다
4. 환불 시 쿠폰 복원: 환불 접수 후 예매 CANCELED 확정과 같은 로컬 트랜잭션에서 처리하는 #27 제안. 결제·예매 담당 동의, 쿠폰 담당 검토 대기
5. 실제 토스가 `Idempotency-Key`에 어떤 응답(5xx·429 포함)을 저장하는지. fake-pg는 업무 응답만 저장한다고 가정한다. Task 21 샌드박스에서 확인한다
6. fake-pg 처리 한도·지연 기본값, 복구 설정값 (2절 설정의 기본값은 임시다)
7. 실제 토스의 429가 "처리하지 않았음"을 보장하는지. 확인 전까지 실제 PG 어댑터는 429를 Unknown으로 둔다
8. PG가 승인을 늦게 기록할 수 있는 최대 시간. 최소 대사 두 번째 시점(24시간)의 근거다
9. 시각 컬럼을 `TIMESTAMPTZ`로 바꿀지 (팀 전체 결정). API 응답의 `+09:00`은 결정됐고 DB 타입은 아직이다
10. 회차 시작 전 취소의 "정책에 따른 환불"에 수수료가 생기면 부분 환불이 필요하다. 지금 설계는 결제당 전액 환불 1건이다(7절)
