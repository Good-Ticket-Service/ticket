# 결제·환불 작업 목록

작업마다 참조할 plan.md 절을 적었다. 규칙은 그 절에만 있다. 공통 검증 명령:

- 모듈 테스트: `cd backend && ./gradlew :payment-service:test`
- 전체 빌드: `cd backend && ./gradlew build`
- 기동·점검: `docker compose up -d --build && script/smoke.sh`

작업 하나는 완료 조건을 모두 만족하고 전체 빌드가 통과해야 끝난다.

## Phase 0. 계약

### Task 1: API 계약 합의
**참조:** plan.md 4절, 8절, 9절, 10절 질문 3·4, #9 orchestration.md
**할 일:** #9 예매 설계와 이 문서를 대조해 예매(2번)·쿠폰(6번)·공연(5번)과 계약을 확정하고 `docs/payment/contract.md`에 쓴다. 준비·승인 두 단계 흐름, 내부 API 4개와 관리자 API, `status` 의미, 오류 코드, `expiresAt` 값, 결과를 모를 때 예매의 동작, 환불 책임, `CouponUsage` 계약이 대상이다.
**완료 조건:**
- [ ] contract.md에 내부 API의 요청·응답·오류 코드와 `expiresAt` 결정이 있고, #9와 다른 항목이 모두 정리됐다
- [ ] contract.md에 조회 결과(DONE / FAILED / IN_PROGRESS / READY / 404)별 예매 동작, 예매의 인증값 저장·재전송, orderId 단위 환불과 B안(예매는 기다리지 않음)이 있다
- [ ] contract.md에 `CouponUsage`의 세 메서드 동작과 실패 규칙(`use`는 실패하지 않음), 트랜잭션 전파, 환불 시 쿠폰 반납 정책이 있다
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
- [ ] 같은 `Idempotency-Key`의 승인·취소 재요청은 거절을 포함해 같은 응답을 돌려준다 (실제 토스 동작)
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
**참조:** plan.md 4절 결제 ①, 6절 멱등 재현·쿠폰 접근, 7절 payment
**할 일:** `POST /internal/payments`, `GET /internal/payments/{orderId}`, `PaymentService.checkout`, `PaymentTransactions.prepare`를 만들고 `CouponUsage.reserve`·`release`를 부른다. 쿠폰 담당 구현이 오기 전에는 Task 1 계약대로 만든 테스트용 `CouponUsage` 대역으로 개발한다.
**완료 조건:**
- [ ] 같은 orderId 재요청은 PG 준비를 다시 부르지 않고 같은 응답(카카오 결제창 URL 포함)을, 다른 내용은 409를 돌려준다
- [ ] 새 준비가 이전 READY를 FAILED(`SUPERSEDED`)로 바꾸고 같은 쿠폰을 다시 잡는다. 같은 예매에 IN_PROGRESS·DONE 결제가 있으면 409 `PAYMENT_ALREADY_IN_PROGRESS`·`PAYMENT_ALREADY_DONE`이다
- [ ] 카카오 준비가 거절·불명이면 FAILED(`READY_FAILED`) + 쿠폰 AVAILABLE이고, tid 없이 남은 READY는 같은 orderId 재준비 때 준비를 다시 부른다. 할인 후 금액은 `min-amount` 이상이다
**검증:** `PgClient` 포트 픽스처를 쓰는 통합 테스트
**의존:** 2, 3 · **규모:** M

### Task 8: 결제 승인 (Inline)
**참조:** plan.md 4절 결제 ③, 6절 트랜잭션 경계·잠근 뒤 다시 판단
**할 일:** `POST /internal/payments/{orderId}/confirm`, `PaymentService.confirm`, `PaymentProcessor`, `markInProgress`·`complete`(4절 11)의 모든 행), `InlinePaymentDispatcher`, `NoopResultPublisher`를 만든다.
**완료 조건:**
- [ ] 금액 불일치는 400, 만료는 FAILED(`EXPIRED`), 재사용된 paymentKey는 FAILED(`INVALID_AUTH_TOKEN`)이고 셋 모두 PG 승인을 부르지 않는다
- [ ] 같은 orderId로 승인 요청 50개를 동시에 보내도 PG 승인은 한 번만 불린다
- [ ] Approved → DONE + 쿠폰 USED, Rejected → FAILED + 쿠폰 AVAILABLE, Unknown → IN_PROGRESS, FAILED 결제에 Approved → FAILED 유지 + AUTO_VOID 환불 행
**검증:** `PgClient` 포트 픽스처를 쓰는 통합 테스트
**의존:** 7 · **규모:** M

### Task 9: 결제 복구 잡
**참조:** plan.md 5절
**할 일:** `PaymentRecoveryJob`, 점유·백오프, 조회 기반 확정, NotFound 유예, 시간 기준 차단, 관리자 `unblock` API를 만든다.
**완료 조건:**
- [ ] 승인 후 응답이 유실된 결제는 DONE이 되고, 만료된 READY는 PG 호출 없이 FAILED(`EXPIRED`)가 된다. NotFound는 유예 시간 안에는 확정하지 않고 지나면 FAILED(`NOT_APPROVED`)다
- [ ] 두 인스턴스에서 동시에 돌려도 같은 결제를 두 번 조회하지 않고, 점유하는 동안 DB 트랜잭션을 열어 두지 않는다
- [ ] 후보 하나의 예외가 다음 후보를 막지 않고, `give-up-minutes` 뒤 IN_PROGRESS + 차단으로 남으며 `unblock`으로 다시 처리된다
**검증:** `payment.recovery.enabled=false`로 두고 잡을 직접 호출하는 통합 테스트
**의존:** 6, 8 · **규모:** M

### Checkpoint B
- [ ] 예매 서비스와 연동해 Gateway를 거친 "준비 → 결제창 대체 → 승인 → 예매 확정"이 두 PG 모두에서 동작한다
- [ ] 결제 거절 시 예매가 HOLD로 돌아오고, 조회 결과가 READY면 예매가 같은 승인 요청을 다시 보낸다

### Task 10: 환불과 환불 복구
**참조:** plan.md 4절 환불, 5절
**할 일:** `POST /internal/payments/{orderId}/refund`, 결제 상태별 처리(4절 환불 2), `RefundService`, `RefundTransactions`, `RefundRecoveryJob`, 관리자 `refund/retry` API, AUTO_VOID 처리를 만든다.
**완료 조건:**
- [ ] READY·FAILED는 NOT_REQUIRED, IN_PROGRESS는 WAITING 후 결제 확정에 따라 REQUESTED·SKIPPED, DONE은 REQUESTED로 처리된다. 같은 결제 환불 재요청은 PG를 다시 부르지 않는다
- [ ] 일시 장애·응답 유실은 REQUESTED로 남아 복구가 같은 키로 다시 보내고, fake-pg 거래는 한 번만 취소된다
- [ ] 업무 거절은 FAILED + 차단이고, `refund/retry`는 새 키 세대로 다시 보낸다 (fake-pg가 같은 키의 거절을 재현해도 성공할 수 있다)
**의존:** 9 · **규모:** M

### Task 11: 메트릭과 대시보드
**참조:** experiments.md 메트릭
**완료 조건:**
- [ ] 메트릭 표의 항목(트러블슈팅 기간 항목 제외)이 Prometheus에서 수집된다
- [ ] Grafana에 결과 분포, 결과 불명 수, PG 지연 패널이 있다
**의존:** 8 · **규모:** S

### Task 12: 장애 주입 정합성 테스트
**참조:** plan.md 1절 정합성 목표
**할 일:** fake-pg 응답 유실 10%와 승인 처리 지연으로 결제·환불 수백 건을 돌리고, 복구 후 정합성을 검사한다. payment를 재시작하는 경우도 포함한다.
**완료 조건:**
- [ ] 이중 결제 0, 중복 환불 0, 해소되지 않은 결과 불명 0, FAILED인데 PG 승인 상태로 남은 건 0 (늦은 승인은 모두 AUTO_VOID로 취소)
- [ ] payment 기록과 fake-pg `pg_transaction`이 건마다 일치한다
**의존:** 9, 10 · **규모:** M

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

Task 14·15·17·18은 단독 환경에서 하므로 예매 서비스 일정과 무관하다. Task 16과 19-1만 예매 연동이 필요하다.

### Task 14: 장애 격리 (시나리오 1a, 단독)
**완료 조건:**
- [ ] 타임아웃 → 벌크헤드 → 서킷 브레이커 단계별 결과가 같은 조건으로 기록됐다
- [ ] 서킷이 열리면 markInProgress 전에 FAILED(`PG_UNAVAILABLE`) + 쿠폰 release로 빠르게 끝나고 PG를 부르지 않는다 (plan.md 4절 8)
**의존:** 13 · **규모:** M

### Task 15: Kafka 부하 평탄화 (시나리오 2, 단독)
**할 일:** `KafkaPaymentDispatcher`와 처리 한도 안에서 `process`를 부르는 워커를 만든다.
**완료 조건:**
- [ ] `payment.dispatch=kafka` 설정만으로 전환된다
- [ ] 시나리오 2의 동기 대비 결과가 기록됐다
**의존:** 13 · **규모:** M

### Task 16: Kafka 명령·결과 이벤트
**할 일:** 예매 담당과 `PaymentCommandConsumer`, `KafkaResultPublisher`, `outbox_event`, `processed_event`를 만든다. 승인 응답은 ACCEPTED가 된다.
**완료 조건:**
- [ ] `payment.result-publish=kafka` 설정만으로 전환되고, 요청 경로와 복구 잡의 모든 확정 전이(plan.md 6절 결과 이벤트)가 같은 트랜잭션의 Outbox로 발행된다
- [ ] 같은 메시지를 두 번 받아도 한 번만 처리하고, 발행 직전 종료 후 재시작해도 이벤트가 유실되지 않는다
**의존:** 15 · **규모:** M

### Task 17: 동기 vs Kafka 정합성 비교 (시나리오 4, 단독)
**완료 조건:**
- [ ] 두 방식의 결과가 같은 조건으로 기록됐고, 두 방식 모두 Task 12 정합성 검사를 통과한다
**의존:** 12, 15 · **규모:** M

### Task 18: 대량 환불 (시나리오 5, 단독)
**완료 조건:**
- [ ] 회차 취소 5,000건 환불이 PG 한도 안에서 끝까지 처리되고, 계속 실패한 건은 차단으로 남는다
- [ ] 순차·병렬·Kafka 결과가 기록됐다
**의존:** 10, 15 · **규모:** M

### Task 19-1: 전체 연결 확인 (시나리오 1b·3)
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
| 19 대사 배치 | payment·refund 기록과 `pg_transaction`의 불일치를 찾아 보고한다 | 12 |
| 20 정산 | 판매자별 일 정산, 재실행 시 같은 결과, 100만 건 배치 시간 측정 | 19, 열린 질문 1·2 |
| 21 샌드박스 실연동 | 토스·카카오 샌드박스로 결제·취소 1건씩 성공 | 6 |
