# 결제·환불 작업 목록

작업마다 참조할 plan.md 절을 적었다. 규칙은 그 절에만 있다. 공통 검증 명령:

- 모듈 테스트: `cd backend && ./gradlew :payment-service:test`
- 전체 빌드: `cd backend && ./gradlew build`
- 기동·점검: `docker compose up -d --build && script/smoke.sh`

작업 하나는 완료 조건을 모두 만족하고 전체 빌드가 통과해야 끝난다.

## Phase 0. 계약

### Task 1: API 계약 합의
**참조:** plan.md 4절, 8절, 9절, 10절 질문 3·4, #9 orchestration.md
**할 일:** #9 예매 설계와 이 문서를 대조해 예매(2번)·쿠폰(6번)·공연(5번)과 계약을 확정하고 `docs/payment/contract.md`에 쓴다. 준비·승인 두 단계 흐름, 내부 API 4개와 관리자 API, `status`·`failureType` 의미, 오류 코드, `expiresAt` 값, 결과를 모를 때 예매의 동작, 환불 책임, plan.md 9절 "예매 쪽에 제안하는 보완" 6개, 할인액 전달과 예매의 쿠폰 처리 책임이 대상이다.
**완료 조건:**
- [ ] contract.md에 내부 API의 요청·응답·오류 코드와 `expiresAt` 결정이 있고, #9와 다른 항목이 모두 정리됐다
- [ ] contract.md에 조회 결과(DONE / FAILED / IN_PROGRESS / READY / 404)별 예매 동작, 예매의 인증값 저장·재전송, orderId 단위 환불과 B안(예매는 기다리지 않음)이 있다
- [ ] contract.md에 "예매 쪽에 제안하는 보완"의 결론이 있다. 특히 `CANCELING`(환불 후 예매 DB 실패 대비, 결정됨), orderId를 먼저 기록하는 순서, read timeout 20초
- [ ] 계약에 totalPrice·discountAmount 전달, 최소 100원 검증, 할인액 멱등 비교와 예매의 쿠폰 책임이 있다
- [ ] 2번·6번 담당자가 승인했다
**의존:** 없음 · **규모:** S

## Phase 1. 기반

### Task 2: 도메인과 스키마
**참조:** plan.md 3절, 7절
**할 일:** `Payment`, `Refund`, 상태 enum(refund WAITING·SKIPPED 포함), 전이 메서드, `matches`, `schema.sql`을 만든다.
**완료 조건:**
- [ ] 3절 표에 없는 전이는 `INVALID_STATUS_TRANSITION`을 던진다 (단위 테스트)
- [ ] 7절의 UK·부분 UK·CHECK(`amount > 0` 포함)가 DB에서 동작한다 (`PostgresIntegrationTest`)
**의존:** 1 · **규모:** M

### Task 3: 포트와 아키텍처 테스트
**참조:** plan.md 2절, 6절 트랜잭션 경계
**할 일:** `PgClient`, `PgResult`, `PgInquiry`, `PaymentDispatcher`, `PaymentResultPublisher`, `PgClientRegistry`를 만들고 흐름 클래스의 `@Transactional`을 금지하는 ArchUnit 규칙을 추가한다.
**완료 조건:**
- [ ] `ArchitectureTest`가 통과하고, 흐름 클래스에 `@Transactional`을 붙이면 실패한다
**의존:** 1 · **규모:** S

### Task 4: fake-pg (토스 흉내와 관리 API)
**참조:** plan.md 7절 fake-pg, 8절 fake-pg
**할 일:** 별도 모듈·컨테이너로 토스 형식 API, `pg_transaction` 기록, 장애 설정·거래 조회 관리 API를 만든다.
**완료 조건:**
- [ ] Compose로 뜨고 Gateway 라우트에 없다
- [ ] 장애 설정(지연, 실패율, 승인 후 응답 유실, 승인 처리 지연, 초당 한도 초과 시 429)이 즉시 반영된다
- [ ] 같은 `Idempotency-Key`의 승인·취소 재요청은 업무 응답(성공·거절)을 그대로 재현하고, 5xx·429는 재현하지 않는다
**의존:** 1 · **규모:** M

### Task 5: fake-pg (카카오 흉내)
**참조:** plan.md 8절 fake-pg
**완료 조건:**
- [ ] 준비 → 사용자 승인 대체 → 승인 → 조회 → 취소가 curl로 끝까지 되고, 이미 취소된 결제의 재취소는 오류다 (실제 카카오 동작)
- [ ] Task 4의 장애 설정이 카카오 API에도 적용된다
**의존:** 4 · **규모:** S

### Checkpoint A
- [ ] 전체 빌드 통과
- [ ] 두 PG 흐름을 fake-pg에 curl로 끝까지 실행할 수 있다

## Phase 2. MVP

### Task 6: PG 어댑터와 계약 테스트
**참조:** plan.md 6절 PG 응답 분류, 자동 재시도 없음
**할 일:** `TossPgClient`, `KakaoPgClient`의 `ready`, `approve`, `inquire`, `cancel`을 만들고 같은 테스트 묶음을 두 어댑터에 돌린다(Testcontainers fake-pg).
**완료 조건:**
- [ ] 준비 → 결제창 대체 → 승인 → 조회 → 취소가 두 어댑터에서 끝까지 된다
- [ ] 6절 분류표의 모든 행과 조회의 NotFound가 두 어댑터에서 테스트된다. 특히 읽기 타임아웃이 Rejected가 아니라 Unknown이다
- [ ] 승인·취소는 자동 재시도하지 않고, 같은 결제에 cancel을 두 번 불러도 PG 거래는 한 번만 취소된다 (카카오는 조회 후 취소)
**의존:** 3, 5 · **규모:** M

### Task 7: 결제 준비
**참조:** plan.md 4절 결제 ①, 6절 멱등 재현·쿠폰 책임, 7절 payment
**할 일:** `POST /internal/payments`, `GET /internal/payments/{orderId}`, `PaymentService.checkout`, `PaymentTransactions.prepare`를 만든다. 예매가 확정한 할인액을 검증·저장하고 쿠폰을 직접 호출하지 않는다.
**완료 조건:**
- [ ] 같은 orderId 재요청은 PG 준비를 다시 부르지 않고 같은 응답(카카오 결제창 URL 포함)을, 다른 내용은 409를 돌려준다
- [ ] 새 준비가 이전 READY를 FAILED(`SUPERSEDED`)로 바꾼다. 쿠폰 예약·정리는 예매 책임이다. 같은 예매에 IN_PROGRESS·DONE 결제가 있으면 409 `PAYMENT_ALREADY_IN_PROGRESS`·`PAYMENT_ALREADY_DONE`이다
- [ ] 카카오 준비가 거절·불명이면 FAILED(`READY_FAILED`)이고, tid 없이 남은 READY는 같은 orderId 재준비 때 준비를 다시 부른다. 할인 후 금액은 `min-amount` 이상이다
**검증:** `PgClient` 포트 픽스처를 쓰는 통합 테스트
**의존:** 2, 3 · **규모:** M

### Task 8: 결제 승인 (Inline)
**참조:** plan.md 4절 결제 ③, 6절 트랜잭션 경계·잠근 뒤 다시 판단
**할 일:** `POST /internal/payments/{orderId}/confirm`, `PaymentService.confirm`, `PaymentProcessor`, `markInProgress`(4절 8)의 모든 분기, 서킷 확인 포함)·`complete`(4절 10)의 모든 행), `ensureRefund`, `InlinePaymentDispatcher`, `NoopResultPublisher`를 만든다.
**완료 조건:**
- [ ] 금액 불일치는 400, 만료는 FAILED(`EXPIRED`), 재사용된 paymentKey는 FAILED(`INVALID_AUTH_TOKEN`)이고 셋 모두 PG 승인을 부르지 않는다
- [ ] 같은 orderId로 승인 요청 50개를 동시에 보내도 PG 승인은 한 번만 불린다
- [ ] Approved → DONE, Rejected·금액 불일치 → FAILED(금액 불일치는 승인 금액으로 AUTO_VOID), Unknown → IN_PROGRESS, FAILED 결제에 Approved → FAILED 유지 + AUTO_VOID. 이미 IN_PROGRESS인 결제는 중복 승인 요청이 FAILED로 바꾸지 못한다
**검증:** `PgClient` 포트 픽스처를 쓰는 통합 테스트
**의존:** 7 · **규모:** M

### Task 9: 결제 복구 잡
**참조:** plan.md 5절
**할 일:** `PaymentRecoveryJob`, 점유(결제는 10초 간격, 백오프 없음), 조회 기반 확정(Approved·Canceled·NotFound·Unknown), NotFound 유예(1분), 결과 확정 시한(1분) 뒤 FAILED(`PG_UNRESOLVED`)를 만든다. 결제 차단과 관리자 확정 API는 두지 않는다.
**완료 조건:**
- [ ] 승인 후 응답이 유실된 결제는 DONE이 되고, 만료된 READY는 PG 호출 없이 FAILED(`EXPIRED`)가 된다. NotFound는 유예 시간 안에는 확정하지 않고 지나면 FAILED(`NOT_APPROVED`)다
- [ ] 조회가 계속 Unknown이면 승인 호출 후 1분 직전에는 확정하지 않고, 1분부터 FAILED(`PG_UNRESOLVED`)이 되며 WAITING 환불은 SKIPPED다
- [ ] 두 인스턴스에서 동시에 돌려도 같은 결제를 같은 주기에 두 번 처리하지 않고, 점유하는 동안 DB 트랜잭션을 열어 두지 않는다
- [ ] 후보 하나의 예외가 다음 후보를 막지 않는다
**검증:** `payment.recovery.enabled=false`로 두고 잡을 직접 호출하는 통합 테스트
**의존:** 6, 8 · **규모:** M

### Task 9-1: 최소 대사
**참조:** plan.md 5절 8번
**할 일:** `PaymentReconcileJob`과 `reconcile_count`·`next_reconcile_at` 컬럼을 만든다(#12의 `reconciled_at`을 바꾼다). 복구 잡과 같은 방식으로 점유하고, PG 승인을 호출했던 FAILED 결제(`PG_UNRESOLVED` 포함)를 확정 11분·24시간 뒤에 조회해 승인돼 있으면 `ensureRefund`를 부른다.
**완료 조건:**
- [ ] fake-pg "승인 처리 지연"으로 유예나 결과 확정 시한 뒤 승인된 결제가 대사 실행 후 AUTO_VOID로 취소되고, 결제는 쿠폰 상태를 변경하지 않으며, 각 결제는 시점마다 한 번만 조회된다
- [ ] 늦은 승인을 요청 경로·복구 조회·대사가 겹쳐 발견해도 환불은 한 건이다
- [ ] PG 승인을 호출하지 않은 FAILED는 조회하지 않고, 조회가 Unknown이면 시점을 넘기지 않아 다음 주기에 다시 본다
**의존:** 9 · **규모:** S

### Checkpoint B
- [ ] 예매 서비스와 연동해 Gateway를 거친 "준비 → 결제창 대체 → 승인 → 예매 확정"이 두 PG 모두에서 동작한다
- [ ] 결제 거절 시 예매가 HOLD로 돌아오고, 조회 결과가 READY면 예매가 같은 승인 요청을 다시 보낸다

### Task 10: 환불과 환불 복구
**참조:** plan.md 4절 환불, 5절
**할 일:** `POST /internal/payments/{orderId}/refund`(payment 잠금 후 상태별 처리), `RefundService`, `RefundTransactions`, `RefundRecoveryJob`(`give-up-minutes` 뒤 차단), 관리자 `refund/retry`·`refund/unblock` API를 만든다. 잠금 순서는 항상 payment → refund다.
**완료 조건:**
- [ ] READY는 FAILED(`ABANDONED`) + 환불 행 없이 NOT_REQUIRED, FAILED는 NOT_REQUIRED, IN_PROGRESS는 WAITING 후 결제 확정에 따라 REQUESTED·SKIPPED, DONE은 REQUESTED다. 세 사유(USER_CANCEL·SCHEDULE_CANCELED·CONFIRM_FAILED) 모두 결제 금액 전액이다. 환불 요청과 결제 complete가 동시에 와도 WAITING이 DONE 결제 위에 남지 않고, AUTO_VOID와 사용자 환불이 겹쳐도 행은 하나다
- [ ] 일시 장애·응답 유실은 REQUESTED로 남아 복구가 같은 키로 다시 보내고, fake-pg 거래는 한 번만 취소된다
- [ ] 업무 거절은 FAILED + 차단이고, `refund/retry`는 새 키 세대로 다시 보낸다 (fake-pg가 같은 키의 거절을 재현해도 성공할 수 있다). 시간 초과는 REQUESTED + 차단이고 `refund/unblock`으로 다시 처리된다. 환불 결과에 따른 쿠폰 복원은 예매 책임이며 결제는 CouponUsage를 호출하지 않는다
**의존:** 9 · **규모:** M

### Task 11: 메트릭과 대시보드
**참조:** experiments.md 메트릭
**완료 조건:**
- [ ] 메트릭 표의 항목(트러블슈팅 기간 항목 제외)이 Prometheus에서 수집된다
- [ ] 고도화 비교의 기준선이 되도록 승인·환불 완료 지연과 가장 오래된 미완료 환불 경과 시간도 수집한다
- [ ] Grafana에 결과 분포, 결과 불명 수, PG 지연 패널이 있다
**의존:** 8 · **규모:** S

### Task 12: 장애 주입 정합성 테스트
**참조:** plan.md 1절 정합성 목표
**할 일:** fake-pg 응답 유실 10%와 승인 처리 지연으로 결제·환불 수백 건을 돌리고, 복구 후 정합성을 검사한다. payment를 재시작하는 경우도 포함한다.
**완료 조건:**
- [ ] 이중 결제 0, 중복 환불 0, 해소되지 않은 결과 불명 0, FAILED인데 PG 승인 상태로 남은 건 0 (늦은 승인은 모두 AUTO_VOID로 취소)
- [ ] payment 기록과 fake-pg `pg_transaction`이 건마다 일치한다
**의존:** 9-1, 10 · **규모:** M

### Checkpoint C: MVP (11/6)
- [ ] 전체 빌드와 CI 통과
- [ ] 결제 성공, 거절 후 재시도, 예매 취소 환불을 시연할 수 있다
- [ ] Task 12 통과

### Task 13: 동기 기준선 측정 (단독)
**참조:** experiments.md 측정 환경 두 가지, 측정 규칙, 시나리오 0·1a·2
**할 일:** 단독 환경(payment, fake-pg, PostgreSQL, 모니터링)과 `ticket-net`에 붙는 k6 컨테이너, `준비 → 결제창 대체 → 승인` k6 스크립트를 만들고 기준선을 잰다. 예매 서비스 없이 진행한다.
**완료 조건:**
- [ ] 단독 환경을 명령 하나로 띄우고 k6를 돌릴 수 있다 (방법이 experiments.md 또는 `docs/payment/perf/README.md`에 있다)
- [ ] 시나리오 0·1a·2 결과가 측정 환경과 함께 `docs/payment/perf/`에 있다
**의존:** 11, 12 · **규모:** M

## Phase 3. 트러블슈팅 기간 (11/9~11/16)

Task 14·15·15-1·16-1·18은 단독 환경에서 진행한다. Task 16과 18-1은 예매 연동이 필요하다. Task 17은 Task 16의 결과 발행 구현 후 테스트 생산자·소비자로 단독 측정하고, 실제 예매 반영은 연동 환경에서 확인한다. Kafka 계약 합의는 단독 측정 전에도 필요하다.

### Task 14: 장애 격리 (시나리오 1a, 단독)
**완료 조건:**
- [ ] 타임아웃 → 벌크헤드 → 서킷 브레이커 단계별 결과가 같은 조건으로 기록됐다
- [ ] 서킷이 열리면 markInProgress 안에서(잠금과 READY 확인 뒤) FAILED(`PG_UNAVAILABLE`)로 빠르게 끝나고 PG를 부르지 않는다 (plan.md 4절 8)
**의존:** 13 · **규모:** M

### Task 15: 승인 호출 한도와 환불 Kafka 기준선
**참조:** decisions.md D16, experiments.md 시나리오 2·5
**할 일:** 승인은 동기 Inline을 유지하고 PG별 동시성·속도 제한을 적용한다. 환불 명령은 합의한 계약의 테스트 생산자로 보내고, Kafka 소비자가 기존 환불 흐름을 직접 실행하는 기준선을 만든다. 실제 예매 발행 연동은 Task 16에서 한다.
**완료 조건:**
- [ ] 동기 승인에서 처리 한도·429·완료 TPS를 제한 전후 비교했다. 승인 Kafka dispatcher는 추가하지 않는다
- [ ] 환불 Kafka 직접 소비가 기존 WAITING·REQUESTED·NOT_REQUIRED 분기와 orderId 멱등성을 따른다
- [ ] 소비자가 환불을 재전달받아도 기존 PG 키·조회·상태 확인으로 중복 취소를 막는다
- [ ] Kafka 수신이 환불 완료를 뜻하지 않으며 PG 취소 실패 시 기존 복구·차단 정책이 이어진다
**의존:** 10, 13 · **규모:** M

### Task 15-1: API·워커 실행 역할 분리 (설계안 검증)
**참조:** [worker-design.md](worker-design.md) 2·3·5절, decisions.md D14, experiments.md 시나리오 7
**할 일:** 환불·복구·대사부터 기존 Payment·Refund 상태를 영속 작업으로 활용한다. 같은 프로세스의 실행 풀 분리를 먼저 검증하고, 아래 기준을 넘을 때만 같은 코드·DB의 API 역할과 워커 역할을 별도 실행한다. 승인·준비의 기존 동기 경로는 유지한다 (D16). 분리는 MVP 선행 조건이 아니다.
**완료 조건:**
- [ ] 역할별 Controller·스케줄러·소비자·발행기 실행 목록을 정하고 1분 결과 확정 복구 작업이 빠지지 않는다
- [ ] 워커 전담 환불은 API 직접 cancel과 중복 시작하지 않는다. 최초 실행·복구가 동일한 점유 절차를 따른다
- [ ] 점유 후·PG 호출 중 종료 및 점유 만료 후 다른 워커 인수에도 미완료 작업이 누락되지 않고 중복 과금·환불이 없다
- [ ] PG별 동시성과 호출 속도를 나누고 다중 인스턴스 합계가 PG 한도 안에 있다. 대량 환불 중 결과 조회 자원을 확보한다
- [ ] 같은 총 CPU·메모리·DB 연결 예산에서 단일 실행 풀과 실행 풀 분리의 조회 p95·승인 완료 p95·환불 완료 시간·DB 연결 대기를 비교했다
- [ ] 프로세스 분리는 실행 풀 분리 뒤에도 대량 환불 중 조회 p95가 기준선(환불 없음) 대비 2배를 넘을 때만 진행하고, 진행하지 않으면 그 수치를 근거로 기록한다
**의존:** 10, 11, 13 · **규모:** M

### Task 16: 환불 명령 연동과 확정 결과 Outbox
**참조:** decisions.md D16, plan.md 6절 결과 이벤트
**할 일:** 예매 담당과 환불·CONFIRM_FAILED 보상 명령, 확정 결과 이벤트 계약을 합의한다. 예매의 영속 환불 의도를 Task 15 소비자에 연결하고, `KafkaResultPublisher`, `outbox_event`, `processed_event`를 구현한다. 준비·승인은 계속 Feign이며 승인 응답을 변경하지 않는다.
**완료 조건:**
- [ ] 요청 경로와 복구 잡의 모든 확정 전이(plan.md 6절 결과 이벤트)가 결과 Outbox와 같은 트랜잭션에 저장된다
- [ ] 결과 발행 설정을 바꿔도 승인 DONE·FAILED·IN_PROGRESS 응답은 같고 인증값은 메시지·Outbox에 없다
- [ ] 같은 메시지 재전달 및 다른 eventId의 같은 orderId에도 환불 업무 효과가 중복되지 않는다
- [ ] 발행 전 종료·발행 ACK 유실에 결과가 누락되지 않고 예매가 현재 orderId·상태를 확인해 결과를 멱등 반영한다
- [ ] 정상 결과 전달은 이벤트를 사용하고 장기 미반영 조회·복구 경로는 유지한다. 폴링 감소·반영 지연을 측정한다
- [ ] 환불 생산자·소비자 계약은 docs/shared 절차로 합의한다. 단독 시험의 접수 성공을 실제 예매 취소 성공으로 보고하지 않는다
**의존:** 15, Checkpoint B · **규모:** M

### Task 16-1: Inbox·DB 인계 후 워커 실행 비교 (시나리오 8, 단독)
**참조:** [worker-design.md](worker-design.md) 4절, decisions.md D15
**할 일:** 환불 명령부터 `processed_event`를 Inbox로 쓰는 DB 인계 방식을 만들고, Task 15의 소비자 직접 실행과 비교한다. 승인 요청과 인증값은 Kafka·Inbox 인계 대상이 아니다.
**완료 조건:**
- [ ] 수신 기록과 환불 인계(REQUESTED·WAITING 등 기존 환불 계약)를 같은 트랜잭션에 저장한 뒤 offset을 커밋한다
- [ ] 시나리오 8의 필수 종료 지점(DB 커밋 후 offset 커밋 전, 점유 만료 후 재인수, PG 성공 후 결과 저장 전)에서 누락·중복 과금·중복 환불이 없다
- [ ] 직접 실행과 DB 인계의 복구 시간, Kafka lag, DB 작업 대기가 기록됐다
**의존:** 15-1, 15 · **규모:** M

### Task 17: 동기 기준선과 환불·결과 Kafka 정합성 비교 (시나리오 4, 단독)
**완료 조건:**
- [ ] 승인 경로는 동일한 동기로 고정하고, 환불 실행·결과 전달 방식만 바꿔 Task 12 장애 행렬과 최종 금액 정합성을 비교했다
**의존:** 12, 15, 16 · **규모:** M

### Task 18: 대량 환불 (시나리오 5, 단독)
**완료 조건:**
- [ ] 회차 취소(`SCHEDULE_CANCELED`) 5,000건 환불이 PG 한도 안에서 끝까지 처리되고, 계속 실패한 건은 환불 차단으로 남는다
- [ ] 순차·병렬·Kafka 결과가 기록됐다
- [ ] Task 15-1의 역할 분리 결과와 함께 환불 중 조회 p95·승인 완료 p95, 가장 오래된 대기와 알려진 미환불 금액·PG 조회 불명 금액을 기록했다
- [ ] DLT 또는 환불 차단으로 남은 건은 업무 완료로 세지 않고, 경보·관리자 재처리 후 PG 거래와 금액을 대조한다
**의존:** 10, 15 · **규모:** M

### Task 18-1: 전체 연결 확인 (시나리오 1b·3)
**할 일:** 예매 연동이 안정되면 전체 Compose에서 1b·3을 개선 전후로 1~2회 잰다. 일정이 밀리면 빼고 그 사실을 결과에 적는다.
**완료 조건:**
- [ ] 단독에서 고른 개선이 전체 연결에서도 유지되는지 결과가 기록됐다
**의존:** 14, 16, Checkpoint B · **규모:** S

### Checkpoint D
- [ ] 단독 시나리오(0·1a·2·4·5) 결과가 experiments.md의 서술 구조로 정리됐다
- [ ] 전체 연결 결과(1b·3)가 있거나, 하지 못한 이유가 적혀 있다

## Phase 4. 확장 (여유 시)

| Task | 완료 조건 | 의존 |
|---|---|---|
| 19 전체 대사 | 최소 대사(9-1)를 넘어 payment·refund 전체 기록과 `pg_transaction`의 불일치를 찾아 보고한다 | 12 |
| 20 정산 | 판매자별 일 정산, 재실행 시 같은 결과, 100만 건 배치 시간 측정 | 19, 열린 질문 1·2 |
| 21 샌드박스 실연동 | 토스·카카오 샌드박스로 결제·취소 1건씩 성공 | 6 |
| 22 복합결제 | 한 결제를 여러 수단(예: 상품권 + 카드)으로 나눠 승인하고, 한 수단이 실패하거나 결과 불명이면 승인된 다른 수단을 취소한다. 환불은 수단별로 나눠 처리한다 | 10, 12 |
| 23 가상계좌 | 입금 대기 상태, PG 입금 웹훅(서명 검증·중복 통지 멱등), 입금 기한 만료, 환불 계좌 처리 | 10, 예매·대기열 정책 합의 |

- 22는 결제 1건 = PG 거래 1건 전제를 바꾼다. `pg_provider`·`pg_transaction_id`를 수단별 하위 테이블로 내리고, 환불도 결제당 1건(UK)에서 수단별 N건이 된다. 결제 담당 범위 안에서 끝나므로 23보다 먼저 한다.
- 23은 입금 기한(보통 수 시간 이상)이 선점 10분과 충돌한다. 입금 대기 중 좌석을 잡아 둘지 예매·대기열 담당과 정책을 먼저 정해야 하고, 웹훅용 외부 공개 경로가 필요하다.
- 둘 다 지금 구조를 미리 바꾸지 않는다. 대신 다음을 지킨다.
  - 예매에 주는 내부 API 응답에는 결제 단위 값(paymentId, status, amount, failureType)만 싣고 PG별 값을 싣지 않는다. 수단이 늘어도 예매 계약이 바뀌지 않게 한다.
  - `PgClient` 결과는 수단과 무관하게 Approved / Rejected / Unknown으로 둔다. 수단 추가는 어댑터 추가로 끝낸다.
  - 결제 수단 필드를 미리 계약에 넣지 않는다. 필요할 때 선택 필드로 추가한다.

## #22 결제의 쿠폰 의존 제거

- [x] PaymentOrder에 할인액을 고정하고 matches에서 비교한다.
- [x] 음수 할인·100원 미만 금액을 거절하고 100원 경계를 테스트한다.
- [x] userCouponId·DDL user_coupon_id를 제거하고 정가·할인액·최종 금액만 남긴다.
- [x] 아키텍처 테스트의 쿠폰 패키지 예외를 제거한다.
- [ ] 계약 변경과 예매·쿠폰 책임 이전을 담당자가 검토한다.
- [x] 결제 테스트·스키마 통합 테스트·전체 빌드를 검증한다.
