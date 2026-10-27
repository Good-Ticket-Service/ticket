# 좌석 작업 목록

- 담당: 변정민
- 설계: [plan.md](plan.md), 외부 API: [api.md](api.md), 테이블: [tables.md](tables.md)
- 계약: [BookingFacade](../shared/modules/booking-facade.md), [SeatFacade](../shared/modules/seat-facade.md), [공연 판매 검증](../shared/internal-api/event.md), [회차 좌석 준비](../shared/internal-api/seat.md)

작업마다 참조할 문서 절을 적었다. 규칙은 그 절에만 있고, 이 문서는 순서와 완료 조건만 다룬다.

공통 검증 명령:

- 모듈 테스트: `cd backend && ./gradlew :reservation-service:test`
- 전체 빌드: `cd backend && ./gradlew build` (통합 테스트는 Docker 필요)
- 기동·점검: `docker compose up -d --build && script/smoke.sh`

진행 방식:

- Task 하나를 이슈 1개, 브랜치 1개, PR 1개로 올린다. 구현 Task는 기능 이슈 양식(`feat(reservation): …`), 협의·문서 Task는 작업 이슈 양식을 쓴다.
- 이슈의 완료 조건에는 아래 체크박스를 그대로 옮기고 "`cd backend && ./gradlew build` 통과"를 더한다.
- 브랜치는 `<type>/reservation/<이슈 번호>-<설명>`이다(예: `feat/reservation/51-schedule-seat-entity`).
- Task를 끝내면 이 문서의 체크박스와 Task 제목 옆 이슈·PR 번호를 같은 PR에서 고친다.
- 동시성·트랜잭션이 들어간 PR(Task 3, 7, 8)은 올리기 전에 `concurrency-reviewer` 에이전트로 검토한다.
- Task 0은 협의 정리라 이 문서를 올린 PR(#57)에 함께 올린다.

## Phase 0. 선행 확인

### Task 0: 남은 협의 정리 (#55, PR #57)
**참조:** plan.md 5장, 7장 열린 질문 1~3·9
**할 일:** 구현 전에 정해야 할 것을 담당자와 맞추고 결론을 plan.md 7장에 적는다.
**완료 조건:**
- [ ] Gateway 라우트: `/api/schedules/*/seats`, `/api/schedules/*/remaining`, `/api/schedules/*/holds/**`를 reservation-service로 보내는 방식과 담당·시점이 정해졌다. 공연 서비스의 `GET /api/schedules/{id}`와 겹치지 않는다 (#34 합의 항목, @nuyheat)
- [x] 좌석 DDL 파일 위치가 정해졌다. booking처럼 `backend/sql/reservation-service/seat.sql`로 나눈다(B27, plan.md 7장 9번, 예매 담당 합의)
- [ ] 감사 작성자(`created_by`·`updated_by`)를 채우는 방식이 booking과 같다. 조건부 UPDATE·요청 밖 작업의 값 포함
- [ ] 외부 API 시각을 `+09:00`으로 내보내는 방식이 정해졌다(Jackson 설정 또는 DTO 타입)
**의존:** 없음 · **규모:** S

## Phase 1. 기반

### Task 1: 회차 좌석 엔티티와 스키마
**참조:** tables.md 2·3장, plan.md 2·3장
**할 일:** `seat.domain`에 `ScheduleSeat`(BaseEntity, `@SQLRestriction`), `SeatStatus`(AVAILABLE·HOLD·SOLD), 상태 전이 메서드(선점, 판매, 반환)를 만든다. DDL은 Task 0에서 정한 파일에 쓰고, Repository에 좌석 id 오름차순 `SELECT ... FOR UPDATE` 조회를 둔다.
**완료 조건:**
- [ ] 엔티티, DDL, tables.md의 컬럼·타입·제약이 일치한다
- [ ] 통합 테스트(`PostgresIntegrationTest`)가 DDL로 테이블을 만들고 Entity 검증(`ddl-auto=validate`)을 통과한다
- [ ] UK(schedule_id, venue_seat_id)가 DB에서 동작한다
- [ ] 상태 표에 없는 전이(예: SOLD → HOLD)는 예외를 던진다 (단위 테스트)
- [ ] HOLD에는 `reservation_id`·`hold_expires_at`이 있고, AVAILABLE·SOLD 전이에서 맞게 비워진다 (단위 테스트)
**의존:** 0 · **규모:** M

### Task 2: 좌석 오류 코드
**참조:** api.md 선점·해제 오류 표, booking-facade.md "오류·예외 책임"
**할 일:** `ReservationErrorCode`에 `SEAT_NOT_FOUND`(404), `SEAT_ALREADY_TAKEN`(409), `SEAT_NOT_SELLABLE`(409), `SEAT_CONFIG_VERSION_MISMATCH`(409), `PURCHASE_LIMIT_EXCEEDED`(409)를 추가한다. booking과 함께 쓰는 파일이라 PR에 예매 담당을 리뷰어로 넣는다. Task 1과 같은 PR로 묶어도 된다.
**완료 조건:**
- [ ] `CommonErrorCode`에 있는 이름을 다시 정의하지 않았다
- [ ] 예매 담당이 리뷰했다
**의존:** 없음 · **규모:** S

### Task 3: SeatFacade 구현
**참조:** seat-facade.md, tables.md 3장 "선점·만료 처리"
**할 일:** `seat.application.facade`에 `SeatFacade`, `SeatLock`, `SeatState`와 구현을 만든다. `lockSeats`는 id 오름차순 `FOR UPDATE`, `sell`·`release`는 `reservation_id = :reservationId` 조건부 UPDATE로 바뀐 행 수를 돌려준다.
**완료 조건:**
- [ ] 모든 메서드가 `Propagation.MANDATORY`이고, 트랜잭션 밖에서 부르면 `IllegalTransactionStateException`이다
- [ ] `sell`은 이 예약의 HOLD 좌석만 SOLD로 바꾸고 `hold_expires_at`을 비운다. 다른 예약 좌석이면 0을 돌려준다
- [ ] `release`는 이 예약이 잡은 좌석만 AVAILABLE로 돌린다. 이미 다른 예약에 넘어간 좌석은 건드리지 않는다
- [ ] 조건부 UPDATE가 `updated_at`·`updated_by`를 직접 채운다
- [ ] `ArchitectureTest`(`BOOKING_USES_SEAT_ONLY_THROUGH_FACADE`)가 통과한다
- [ ] 예매 담당에 booking 어댑터(`UnconnectedSeatAdapter`) 교체를 요청했다
**의존:** 1 · **규모:** S

### Checkpoint A
- [ ] 전체 빌드 통과
- [ ] booking이 실제 `SeatFacade`로 확정·만료 통합 테스트를 돌릴 수 있다

## Phase 2. MVP 외부 API

booking의 `countActiveSeats`·`findActiveHold`·`expireIfDue`·`findPaymentInProgress`는 계약(booking-facade.md)에만 있고 `BookingFacade` 인터페이스에는 선언도 아직 없다. 예매 담당이 시그니처를 먼저 머지하고 구현은 따로 올린다(예매 담당 이슈, 번호가 나오면 적는다). 이 메서드가 필요한 Task는 머지된 시그니처를 구현한 테스트용 가짜 `BookingFacade`로 먼저 만들고, booking 구현이 머지되면 실제 구현으로 통합 테스트를 다시 돌린다.

### Task 4: 선택 가능 좌석 조회
**참조:** api.md "선택 가능 좌석 판정", `GET /api/schedules/{scheduleId}/seats`, plan.md 4.3
**할 일:** 선택 가능 판정을 도메인 메서드로 만들고(선점·잔여석이 함께 쓴다) 조회 API를 만든다.
**완료 조건:**
- [ ] 판정 표의 6개 행(AVAILABLE, 만료 전 HOLD, 만료 후 결제 중 HOLD, 만료 후 결제 중 아닌 HOLD, SOLD, 판매 제외)이 테스트된다
- [ ] 만료 HOLD 좌석의 예약 ID를 모아 `findPaymentInProgress`를 한 번만 부른다
- [ ] 응답에 다른 사용자의 `reservationId`·`holdExpiresAt`과 판매 제외 사유가 없다
- [ ] 응답이 `ApiResponse`이고 ID는 숫자, 시각은 `+09:00`이다
- [ ] `now`는 공용 `Clock`에서 읽는다
**의존:** 1 · **규모:** M

### Task 5: 등급별 잔여석 조회
**참조:** api.md `GET /api/schedules/{scheduleId}/remaining`
**할 일:** 등급별 집계와 Redis 캐시 `remaining:{scheduleId}`를 만든다.
**완료 조건:**
- [ ] Task 4의 판정으로 선택 가능한 좌석만 등급별로 센다
- [ ] 캐시가 있으면 `cached = true`로 그대로 돌려주고, 없으면 집계해 저장한다
- [ ] TTL은 설정값이다(값은 plan.md 7장 질문 7에서 정한다)
- [ ] 입장 토큰 없이 호출된다
**의존:** 4 · **규모:** S

### Task 6: 공연 판매 검증 클라이언트
**참조:** [event.md](../shared/internal-api/event.md) 판매 검증, api.md 선점 2단계
**할 일:** `seat.infrastructure.client`에 performance-service `POST /internal/schedules/{id}/sale-validations` Feign 클라이언트를 만들고, application에는 포트로 노출한다.
**완료 조건:**
- [ ] 요청·응답 필드 이름과 타입이 계약과 같다. 다르면 구현을 바꾸지 않고 공연 담당(@kiteof-park)에 알린다
- [ ] `allowed=false`면 `reasonCode`를 그대로 넘긴다
- [ ] 404는 그대로 전달하고, 연결 실패·타임아웃·5xx는 503 `SERVICE_UNAVAILABLE`이다
- [ ] 재시도(`IdempotentRetryConfig`)는 계약에 멱등으로 적혀 있을 때만 붙인다(지금 계약은 "미정")
**의존:** 0 · **규모:** S

### Task 7: 좌석 선점
**참조:** api.md `POST /api/schedules/{scheduleId}/holds`, plan.md 4.1, booking-facade.md hold·findActiveHold·expireIfDue·countActiveSeats·findPaymentInProgress
**할 일:** api.md 처리 1~13단계대로 선점 API를 만든다.
**완료 조건:**
- [ ] 신규 선점은 201, 같은 회원·같은 좌석의 유효 HOLD 재요청은 200이다
- [ ] 같은 좌석을 동시에 선점하면 1건만 성공하고 나머지는 409 `SEAT_ALREADY_TAKEN`이다 (Testcontainers 동시성 테스트)
- [ ] 판매 검증 거절의 `reasonCode`가 그대로 외부 `code`가 된다(400·409 구분은 api.md 오류 표)
- [ ] 본인 만료 HOLD는 `findActiveHold` → `expireIfDue`로 정리하고 그 좌석을 반환한다
- [ ] 만료됐지만 결제 중인 좌석은 409이고, `expireIfDue`가 false면 409다
- [ ] `holdExpiresAt`을 한 번 계산해 `hold()`와 좌석에 같은 값을 쓴다
- [ ] 부분 UNIQUE `uk_p_reservations_active_user_schedule` 위반을 트랜잭션 밖에서 409 `PURCHASE_LIMIT_EXCEEDED`로 바꾼다. 다른 무결성 오류는 바꾸지 않는다
- [ ] 잠금 순서가 좌석(id 오름차순) → 예약이다
- [ ] `concurrency-reviewer` 검토를 받았다
**의존:** 2, 3, 4, 6, booking의 미구현 Facade 메서드(가짜로 먼저) · **규모:** L

### Task 8: 선점 해제
**참조:** api.md `DELETE /api/schedules/{scheduleId}/holds/{reservationId}`, plan.md 4.2, booking-facade.md getReservation·release
**할 일:** `getReservation` → 좌석 잠금 → `release` 순서로 해제 API를 만든다.
**완료 조건:**
- [ ] 없는 예약·다른 회차 예약은 404, 남의 예약은 403이다(`getReservation`으로 먼저 판단)
- [ ] RELEASED·EXPIRED·ALREADY_CLOSED면 이 예약이 잡은 좌석만 반환하고 204다
- [ ] NOT_RELEASABLE이면 좌석을 바꾸지 않고 409 `INVALID_STATUS_TRANSITION`이다
- [ ] 같은 요청을 반복해도 204다
- [ ] 결제 시작과 해제가 동시에 오면 한쪽만 성공한다 (통합 테스트)
**의존:** 3, 7 · **규모:** S

### Checkpoint B
- [ ] Gateway 라우트가 반영되어 `docker compose up` 후 curl로 조회 → 선점 → 해제가 끝까지 된다
- [ ] 동시 선점, 재선점과 결제 시작 경합 테스트가 통과한다
- [ ] booking의 미구현 Facade 메서드가 머지됐다면 가짜 대신 실제 구현으로 Task 4·7 통합 테스트가 통과한다

## Phase 3. 연동

### Task 9: 회차 좌석 준비 이벤트 소비
**참조:** [seat.md](../shared/internal-api/seat.md), tables.md "컬럼이 바뀌는 시점", plan.md 4.4
**할 일:** performance-service의 `ScheduleSeatSetupRequested`를 소비해 좌석을 만들거나 교체하고 `ScheduleSeatSetupCompleted`/`Failed`를 발행한다.
**완료 조건:**
- [ ] 최초 준비, 같은 버전 재요청(재발행), 오래된 버전(무시), 재준비(모든 좌석 AVAILABLE일 때만 교체) 케이스가 테스트된다
- [ ] 좌석 저장이 한 트랜잭션에서 전부 반영되거나 전부 반영되지 않는다
- [ ] 같은 회차 이벤트는 scheduleId 파티션 키로 순서대로 처리한다
**의존:** 공연 담당과 이벤트 형식·토픽 이름 확정, `spring-kafka` 의존성 추가(공유 `build.gradle`, 예매 담당과 시점 합의) · **규모:** M

### Task 10: 관람 연령 확인 (보류)
**참조:** api.md 선점 3단계, plan.md 7장 질문 5
**할 일:** 생년월일·연령 기준·ageRating 전달 계약이 정해지면 선점 3단계와 오류 코드를 추가한다.
**의존:** 회원·공연 담당과 계약 확정 · **규모:** S

## Phase 4. 트러블슈팅 (11/9~)

### Task 11: 선점 방식 비교
**참조:** tables.md 3장 "선점·만료 처리", 5장
**할 일:** MVP의 비관적 락과 조건부 UPDATE, `NOWAIT`, Redis, 낙관적 락(`version` 컬럼 ALTER)을 같은 시나리오로 비교한다.
**완료 조건:**
- [ ] 같은 k6 시나리오와 조건으로 방식별 p95·처리량·실패율을 쟀다
- [ ] 결과와 선택 이유를 `docs/perf/` 양식으로 남겼다
- [ ] `version` 컬럼을 추가했다면 tables.md와 DDL을 같은 PR에서 고쳤다
**의존:** 7 · **규모:** M

### Task 12: 좌석 준비 결과 유실·중복 방지
**참조:** tables.md 5장
**할 일:** 좌석 준비 결과 이벤트에 `outbox_event`, 소비에 `processed_event`를 붙인다.
**완료 조건:**
- [ ] 두 테이블을 reservation-service 공용으로 둘지 예매 담당과 정했다
- [ ] 같은 요청이 다시 와도 처리하지 않고 이전 결과를 재발행한다
**의존:** 9 · **규모:** M
