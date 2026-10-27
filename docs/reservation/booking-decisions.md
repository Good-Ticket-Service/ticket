# 예약 담당 결정 기록 (reservation / booking)

booking 담당(한얼)이 혼자 정할 수 있는 결정과 그 이유를 모은다. 다른 담당과 맞추는 계약과 열린 질문은 [orchestration.md](orchestration.md)에 있다.

- 결정마다 그 결정을 지키는 검사를 적는다. 구현하면서 검사를 테스트로 만들고, "지키는 검사" 칸을 테스트 이름으로 바꾼다.
- 필드, 설정값, 상수 이름, 처리 순서는 쓰지 않는다. 그것은 코드와 설정이 정본이다. 여기에는 코드만 봐서는 알 수 없는 이유를 남긴다.
- 결정을 바꾸면 이 문서와 검사를 같은 PR에서 고친다.
- 상태 이름은 orchestration.md 4.1을 따른다(확인 필요, T3). 클래스 이름은 가안이다. 패키지는 S1 합의대로 `com.sparta.ticket.reservation.booking` 아래를 계층으로 나눈다.
- **공유**는 seat 담당과 같이 쓰는 파일이나 모델이다. 바꾸기 전에 seat 담당에게 알린다.

| 버전 | 날짜 | 변경 |
| --- | --- | --- |
| 0.1 | 2026-10-25 | 첫 판. orchestration.md 0.3 기준 |
| 0.2 | 2026-10-25 | 구현(이슈 #15)에 맞춰 "지키는 검사"를 테스트 이름으로 바꿨다. 예약 생성을 3장으로 옮기고, `hold_expires_at`을 지금은 예약에 둔다고 적었다. 코드 리뷰로 B21~B26을 더했다 |
| 0.3 | 2026-10-26 | 예약 테이블 DDL을 `schema.sql`에서 `booking.sql`로 나눴다(B27) |
| 0.4 | 2026-10-26 | 예약이 담은 좌석을 티켓으로 키우고, 좌석 판매·반환에 예약 ID를 넘긴다(B28) |
| 0.5 | 2026-10-26 | 취소에 중간 상태 `CANCELING`을 두고, 취소 상태 철자를 `CANCELED`로 맞췄다(B29) |
| 0.6 | 2026-10-26 | 사용자 선점 해제 상태 `RELEASED`와 `BookingFacade.release`를 더했다(B30) |
| 0.7 | 2026-10-26 | S1 합의에 따라 booking 코드를 `reservation.booking` 아래로 옮겼다. 4장의 패키지 구조 항목을 지웠다 |

---

## 1. 넣는 기준

booking이 혼자 정하고, 다른 담당의 답이 바뀌어도 booking의 상태 전이나 테이블이 바뀌지 않는 결정만 2장에 넣는다. 다른 담당과 맞물리는 곳은 내 쪽 원칙만 3장에 둔다. 답에 따라 상태나 테이블이 바뀌는 항목과, 팀 문서와 다르게 가자는 제안은 4장에 남긴다.

---

## 2. 결정

### 2.1 상태와 전이

| # | 결정 | 이유 | 지키는 검사 |
| --- | --- | --- | --- |
| B1 | 허용되는 전이는 전이 표(`ReservationTransition`) 한 곳에서 정의한다. 표에는 전이마다 from, to, 좌석 영향(없음, 판매, 반환)이 있다. 상태를 바꾸는 쿼리는 전이 표의 항목을 받아 from과 to를 쓴다. 다른 코드에서 상태를 비교해 전이 여부를 가리지 않는다. **공유**: 상태 enum | 전이 규칙이 여러 메서드와 쿼리에 흩어지면 새 전이를 넣을 때 한 곳을 빠뜨린다 | 컴파일: `Reservation`에 상태 setter가 없다. 리뷰: 전이는 `ReservationJpaRepository`의 default 전이 메서드만 부르고 from·to는 `ReservationTransition`에서 받는다. 순서는 `ReservationTransitionExecutorIntegrationTest` |
| B2 | 상태는 조건부 UPDATE(`WHERE id = ? AND status = ?`와 전이 조건)로만 바꾼다. Entity를 읽어 값을 바꾸고 저장하는 방식은 쓰지 않는다 | 읽기와 쓰기 사이에 다른 요청이 끼면 둘 다 성공한다. 조건부 UPDATE는 DB가 한쪽만 성공시킨다(설계서 2.1) | `ReservationTransitionIntegrationTest.concurrentTransition`(모든 트랜잭션이 열린 뒤 동시에 UPDATE) |
| B3 | 결제 결과 반영에서 UPDATE가 0행이면 지금 상태를 다시 읽고 가른다. 같은 결과가 다시 왔으면 무시한다. 이미 끝난 예약에 성공이 왔으면 F6으로 보낸다. 지난 시도의 결과면 B4를 따른다 | 0행을 모두 오류로 보면 중복 수신이 F6 환불로 번지거나, 성공한 결제가 사용자 화면에 실패로 보인다. 결제 준비와 승인 요청의 0행 처리는 orchestration.md 5.2를 따른다 | `ReservationPaymentIntegrationTest.staleAndDuplicateResults`, `ReservationPaymentIntegrationTest.failureAfterExpiry`(끝난 예약에 온 성공) |
| B4 | 결제 결과는 결과의 `orderId`가 예약의 `order_id`와 같을 때만 반영한다. 다르면 지난 시도의 결과다. 실패면 무시하고, 성공이면 F6으로 보낸다 | 한 예약에 결제 시도가 여러 번 있을 수 있다(payment 답변, P1). 늦게 온 지난 시도의 결과가 지금 시도를 덮으면 안 된다 | `ReservationPaymentIntegrationTest.staleAndDuplicateResults`, `ReservationTransitionIntegrationTest.conditionsGuardTransitions` |
| B5 | 조건부 UPDATE 쿼리는 `updated_at`과 `updated_by`를 직접 설정한다. `updated_by`는 `AuditorAware`에서 받는다. 그러면 사용자 요청에서는 헤더의 사용자가, 스케줄러와 리스너처럼 요청 밖에서 도는 작업에서는 `SYSTEM`이 들어간다 | 조건부 UPDATE는 JPA 감사(`@LastModifiedDate`, `@LastModifiedBy`)를 거치지 않는다. 감사가 쓰는 `HeaderAuditorAware`와 같은 값을 써야 감사 컬럼의 뜻이 하나로 유지된다 | `ReservationTransitionIntegrationTest.auditColumns`(SYSTEM), `ReservationPaymentIntegrationTest.checkoutAndConfirm`(요청한 사용자) |
| B6 | 시간 판정은 reservation 서버 시계로 한다. 현재 시각은 `Clock` 빈으로 주입하고, 조건부 UPDATE의 `updated_at`도 이 `Clock`으로 채운다 | payment 시계와 몇 초 달라도 만료 스케줄러가 결국 맞춘다. `Clock`을 주입하면 만료 경계를 테스트에서 고정할 수 있다. JPA 감사가 채우는 시각은 `Clock`을 따르지 않으므로, 시간에 민감한 테스트는 `updated_at`을 직접 넣는다 | `ReservationTransitionIntegrationTest.paymentFailureDependsOnHoldTime`, `ReservationPaymentIntegrationTest.failureAfterExpiry` |
| B27 | booking이 소유한 테이블의 DDL은 `backend/sql/reservation-service/booking.sql`에 두고, seat와 함께 쓰는 `schema.sql`은 고치지 않는다. 통합 테스트는 `schema.sql` 다음에 `booking.sql`을 실행한다 | reservation-service는 booking과 seat가 함께 쓴다. 한 파일을 둘이 고치면 충돌이 나고 고칠 때마다 서로 알려야 한다. 테이블 소유(설계서 D12)대로 파일을 나누면 각자 자기 파일만 고친다. 팀 규칙은 아직 서비스마다 `schema.sql` 하나라서, 그 전까지는 이 서비스 테스트만 두 파일을 실행하게 설정한다. 도메인별 파일을 팀 규칙으로 바꾸는 일은 플랫폼 이슈로 따로 낸다 | `ReservationTransitionIntegrationTest.schemaFromSqlFile`. 모든 통합 테스트가 `ddl-auto=validate`로 예약 Entity와 `booking.sql`을 대조한다 |
| B28 | 예약이 담은 좌석은 booking이 소유한 티켓(`p_tickets`)에 둔다. 선점 때 좌석 id와 선점 시점 가격으로 만들고, 확정 때 티켓 코드를 발급하며, 만료·취소돼도 지우지 않는다. 티켓이 유효한지는 예약 상태로 판단하고, 1차에는 티켓 상태를 두지 않는다. 좌석 판매·반환은 예약 ID를 함께 넘긴다 | 설계서 3장은 다른 모듈의 Entity를 쓰지 않고 Facade로만 부르게 한다. booking이 자기 예약의 좌석과 가격을 스스로 알아야 seat에 묻지 않고 전이를 실행하고 금액을 계산한다. `schedule_seat.reservation_id`는 "지금 이 좌석을 잡은 예약"이고 티켓은 "이 예약이 담은 좌석"이라 서로 다른 사실이다. 만료된 선점은 정리 전에 다른 예약이 다시 잡을 수 있어서, 좌석 id만으로 반환하면 남의 좌석을 푼다. 1차는 예약 하나에 티켓 한 장이라 티켓 상태는 예약 상태의 복사본이 된다 | `ReservationTransitionIntegrationTest.hold`(티켓·가격, 같은 좌석 거절), `ReservationPaymentIntegrationTest.checkoutAndConfirm`(코드 발급, 판매 대상 예약), `BookingSchedulerIntegrationTest.expiresDueHolds`(티켓 유지, 반환 대상 예약) |
| B30 | 사용자가 선점을 직접 해제하면 예약을 `RELEASED`로 바꾼다. seat가 해제 API를 받아 `BookingFacade.getReservation`으로 좌석 id를 읽고, 좌석을 먼저 잠근 뒤 `BookingFacade.release`를 부른다. 좌석 반환은 seat가 한다. `release`는 결과를 돌려준다: `RELEASED`, 선점 시간이 이미 지났으면 `EXPIRED`로 정리, 이미 `RELEASED`·`EXPIRED`면 `ALREADY_CLOSED`(seat는 204), 결제 중·확정·취소면 `NOT_RELEASABLE`(seat는 409). 없는 예약과 남의 예약만 예외다(#34) | 1인 매수 제한이 회차당 1건이면 좌석을 바꾸려고 해제한 예약이 유효한 예약에서 빠져야 한다. `EXPIRED`로 대신하면 시간 초과와 사용자 포기를 나눠 셀 수 없고, `CANCELED`로 대신하면 환불 흐름과 섞인다. 시간이 지난 선점은 사용자에게 어느 쪽이든 좌석이 풀린 것이라 오류로 답하지 않고, 사실대로 `EXPIRED`로 남긴다. 결제 시작과 해제가 동시에 와도 둘 다 `status = HOLD` 조건부 UPDATE라 한쪽만 성공한다 | `ReservationTransitionIntegrationTest.releaseHold`, `ReservationTransitionIntegrationTest.releaseRejected`, `ReservationTransitionIntegrationTest.getReservationReturnsSeatsInLockOrder` |
| B31 | 한 회원은 한 회차에 진행 중인(HOLD·PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING) 예약을 하나만 가진다. `booking.sql`에 부분 UNIQUE `uk_p_reservations_active_user_schedule`을 두고, `BookingFacade.hold`는 `saveAndFlush`로 바로 써서 위반 예외를 호출 안에서 그대로 던진다 | 1인 매수 제한을 `countActiveSeats` 조회만으로 지키면 동시 선점 두 건이 모두 통과한다. DB 제약이 마지막 방어선이다. `save`만 하면 INSERT가 커밋 때로 미뤄져 좌석을 바꾼 뒤에야 실패를 안다. 위반 뒤 트랜잭션은 롤백 전용이 되므로 seat는 트랜잭션 밖에서 제약 이름을 보고 409 `PURCHASE_LIMIT_EXCEEDED`로 바꾼다(#34) | `ReservationTransitionIntegrationTest.holdRejectsSecondActiveReservationInsideCall`, `schemaFromSqlFile` |
| B32 | Facade로 주고받는 타입은 상대 모듈의 domain 타입을 쓰지 않는다. Facade와 그 요청·응답 타입(`BookingFacade`, `SeatLine`, `ReservationView`, `ReservationState`, `ReleaseResult`)은 `booking.application.facade`에 두고, seat의 것은 `seat.application.facade`에 둔다. 상대 모듈은 facade 패키지만 쓸 수 있다(#34) | 모듈 경계를 코드로 지키고, 나중에 Facade를 Feign으로 바꿀 때 이 타입이 그대로 요청·응답 DTO가 된다. 다만 Feign으로 바꾸면 좌석과 예약이 따로 커밋되므로 보상이 필요하다 | `ArchitectureTest.SEAT_USES_BOOKING_ONLY_THROUGH_FACADE`, `BOOKING_USES_SEAT_ONLY_THROUGH_FACADE` |
| B33 | reservation-service는 `Clock` 빈 하나(`reservation.infrastructure.config.ClockConfig`, Asia/Seoul)를 seat와 booking이 함께 쓴다. 선점 만료 시각 계산과 만료·결제 시작·결과 확인 판정은 이 Clock으로 구한 값을 조건부 UPDATE의 `:now`로 넘기고, DB `now()`는 쓰지 않는다 | 만료(`<= now`)와 결제 시작(`> now`)을 두 모듈이 다른 시계로 보면 경계가 어긋난다. 둘 다 `status = HOLD` 조건이라 한쪽만 성공하지만, 사용자에게 보이는 결과가 시계에 따라 달라진다. Clock이면 테스트가 경계를 정확히 만들 수 있다(#34) | `MutableClock`을 쓰는 통합 테스트 |

### 2.2 결제 흐름

| # | 결정 | 이유 | 지키는 검사 |
| --- | --- | --- | --- |
| B7 | 결제 결과를 반영하는 코드는 한 곳(`PaymentResultApplier`)에만 둔다. Phase 1은 Feign 응답이, Phase 2는 Kafka 리스너가 같은 코드를 부른다 | 두 Phase의 측정 차이를 통신 방식 때문이라고 말하려면 반영 코드가 같아야 한다(설계서 6.2) | `ArchitectureTest.PAYMENT_ADAPTERS_DO_NOT_CHANGE_RESERVATIONS` |
| B8 | 결제 실패는 선점 시간이 남았으면 `HOLD`로, 지났으면 `EXPIRED`와 좌석 반환으로 반영한다 | 남은 선점 시간 안에서 다시 결제할 수 있게 한다(F2) | `ReservationPaymentIntegrationTest.failureReturnsToHold`, `ReservationPaymentIntegrationTest.failureAfterExpiry` |
| B9 | 원격 호출은 트랜잭션 밖에서 한다. 원격 호출이 있는 유스케이스는 "트랜잭션, 원격 호출, 트랜잭션" 세 단계로 나눈다 | PG가 늦는 동안 DB 커넥션을 잡으면, Phase 1 측정이 스레드 포화가 아니라 커넥션 풀 고갈을 재게 된다(설계서 2.1) | `ReservationPaymentIntegrationTest.checkoutAndConfirm`(호출 중 트랜잭션 없음, 빌려 간 DB 연결 0개), `BookingSchedulerIntegrationTest.resendsWhenNotReceived`. 테스트도 운영처럼 open-in-view를 끈다 |
| B10 | 클라이언트가 보낸 금액은 받지 않는다. 결제 금액은 서버 쪽 값만 쓴다 | 금액 위변조를 막는다(설계서 8.2). 기본 금액의 출처와 할인 후 금액은 3장에 둔다 | 리뷰: `CheckoutRequest`와 `ConfirmRequest`에 금액 필드가 없다 |
| B11 | 결제 명령(준비, 승인, 환불)은 자동 재시도하지 않는다. 같은 승인을 다시 보내는 일은 결과 확인 스케줄러만 한다. 그래서 명령과 조회를 같은 Feign 클라이언트에 두지 않는다 | `IdempotentRetryConfig`는 메서드가 아니라 클라이언트 단위로 붙는다. 명령에 재시도가 붙으면 승인 한 번이 read 대기의 3배까지 늘어나 Phase 1 스레드 점유가 커진다 | `ReservationPaymentIntegrationTest.unknownOutcome`(요청 경로에서 다시 보내지 않음). 리뷰: Feign 어댑터를 만들 때 명령 클라이언트에 `IdempotentRetryConfig`가 없다 |
| B12 | `PaymentPort`는 booking의 말로 정의하고, payment의 응답 값은 어댑터에서 그 말로 바꾼다. 유스케이스 테스트는 가짜 구현으로 한다 | payment의 응답 형식(P1, P2)이나 전송 방식(Phase 1, 2)이 바뀌어도 어댑터 한 곳만 바뀐다. 결과 종류의 목록은 3장에 둔다 | `ArchitectureTest.LAYERS`(기존 계층 규칙). payment 응답 DTO는 infrastructure에 두므로 application이 쓰면 깨진다 |
| B13 | 결과 종류는 enum으로 두고 switch 식으로 다룬다. 결과 enum의 switch에는 `default`를 두지 않는다 | 툴체인이 JDK 17이다. sealed 타입에 대한 switch 패턴 매칭은 17에서 미리보기 기능이고 21에서 정식이 됐다. `default` 없는 enum switch 식은 빠진 경우를 컴파일러가 잡는다 | 리뷰: `ConfirmOutcome.Result`, `PaymentLookup.Status`의 switch에 `default`가 없다 |
| B21 | 결제가 실패해 HOLD로 돌아가면 `order_id`와 결제 금액을 지운다. 다시 결제하려면 새로 준비해야 한다 | 지우지 않으면 실패한 orderId로 승인이 다시 들어와 payment를 또 부른다(더블 클릭). 지운 뒤에도 늦게 온 그 시도의 결과는 orderId가 달라 지난 시도로 걸러진다(B4) | `ReservationPaymentIntegrationTest.failureReturnsToHold` |
| B22 | 선점 시간이 지난 뒤의 승인은 만료 스케줄러가 돌았는지와 관계없이 `HOLD_EXPIRED`로 답한다. 같은 승인이 다시 와서 지금 상태를 돌려주는 것은 결제 중이거나 확정일 때뿐이다 | 스케줄러 실행 시점에 따라 같은 상황에 409와 200이 섞이면 클라이언트 계약이 둘이 된다 | `ReservationPaymentIntegrationTest.confirmAfterExpiry` |
| B23 | 승인 뒤 결과 반영이 실패하면 오류 대신 지금 예약 상태(결제 중)를 돌려준다 | 승인은 성공했을 수 있다(F5). 오류로 답하면 클라이언트는 결제 실패로 안다. 결제 중이면 클라이언트는 폴링하고 결과 확인 스케줄러가 다시 반영한다 | `ReservationPaymentIntegrationTest.applyFailureReturnsState` |
| B24 | 조건부 UPDATE 뒤에 영속성 컨텍스트 전체를 비우지 않는다(`clearAutomatically`를 쓰지 않는다). UPDATE 뒤에 예약을 다시 봐야 하면 `reload`로 예약 하나만 다시 읽는다 | 예약과 좌석은 한 트랜잭션에서 바뀐다. 전체를 비우면 seat 모듈이 잠가 둔 좌석 Entity까지 준영속이 되고, 그 뒤 seat가 바꾼 값은 오류 없이 버려진다 | 리뷰: `@Modifying`에 `clearAutomatically`가 없다 |

### 2.3 스케줄러

| # | 결정 | 이유 | 지키는 검사 |
| --- | --- | --- | --- |
| B14 | 만료 스케줄러는 후보를 잠그지 않고 고르고, 건마다 트랜잭션 하나로 처리한다. 결제 중(`PAYMENT_IN_PROGRESS`) 예약은 만료하지 않는다. 처리 직전에 다시 읽어 이미 바뀐 예약은 좌석을 잠그지 않고 넘긴다. 건별 트랜잭션 안의 좌석 연산과 잠금 순서는 3장을 따른다 | 건별 트랜잭션이라 한 건이 실패해도 나머지가 진행된다. 결제 중 예약을 만료하면 좌석이 풀린 뒤 결제 성공이 도착해 "결제는 됐는데 표가 없는" 상태가 된다. seat도 이 좌석을 고르지 않아야 완전히 막힌다(S5). 한계: 같은 예약이 계속 실패해 한 묶음을 다 채우면 뒤 예약이 밀린다. 실패는 경고 로그로 드러난다 | `BookingSchedulerIntegrationTest.expiresDueHolds`, `BookingSchedulerIntegrationTest.oneFailureDoesNotStopOthers` |
| B15 | 만료 스케줄러는 인스턴스가 여러 개여도 분산 락을 두지 않는다. 중복 작업이 측정에서 문제로 보이면 그때 `FOR UPDATE SKIP LOCKED`를 넣는다 | 만료는 DB 전이뿐이라 조건부 UPDATE가 한 번만 성공시킨다. 원격 호출을 하는 결과 확인 스케줄러는 여기에 들지 않는다(3장) | `BookingSchedulerIntegrationTest.concurrentExpiry`(두 실행이 같은 예약을 잠금 직전에 맞춰 함께 처리) |
| B16 | 결과 확인 스케줄러는 결제 중으로 기준 시간 이상 머문 예약만 고른다. 기준 시간은 그 예약의 승인 요청이 아직 처리 중일 수 있는 최대 시간보다 길다. Phase 1에서는 승인 호출의 connect와 read 대기의 합이고, Phase 2에서는 consumer 지연까지 더한다 | 기준 시간이 짧으면 처리 중인 승인을 또 보낸다 | `BookingSchedulerIntegrationTest.respectsThreshold`, `BookingSchedulerPropertiesTest` |
| B17 | 결제 중 예약 수와, 상한 시간을 넘겨 결제 중인 예약 수를 게이지로 남기고 경고 로그를 쓴다. 상태는 바꾸지 않는다 | 결제 중 예약은 만료하지 않는다(B14). 상한이 없으면 좌석이 묶인 채 아무도 모른다. 카운터로 세면 같은 예약을 실행마다 다시 세서 건수가 부풀므로 게이지로 지금 수를 보인다 | `BookingSchedulerIntegrationTest.reportsPaymentBacklog` |
| B18 | 스케줄러의 주기와 기준 시간은 `config-repo/reservation-service.yml`에만 두고 코드에 기본값을 두지 않는다. 한 번 실행은 건수와 시간 예산으로 끊는다. 예산이 다 되면 다음 원격 호출을 시작하지 않고, 이미 시작한 호출 하나만큼만 넘칠 수 있다. **공유**: 이 설정 파일 | 측정하면서 바꿀 값이라 한곳에 둔다. payment가 느리면 조회 한 건이 재시도까지 수십 초 걸린다. 건수로만 끊으면 한 번 실행이 주기를 크게 넘긴다 | `BookingSchedulerIntegrationTest.stopsAtTimeBudget`(걸린 시간), `BookingSchedulerPropertiesTest`, `BookingSchedulingConfigTest` |
| B25 | 결과 확인은 마지막으로 확인한 시각(`payment_checked_at`)이 오래된 예약부터 고른다. 확인한 예약은 다음 차례에서 뒤로 간다 | 결과가 오래 안 바뀌는 예약(F4, 모르는 주문)이 한 묶음을 채우면, `updated_at` 순으로만 고를 때 새로 결과를 모르게 된 예약은 영영 차례가 오지 않는다 | `BookingSchedulerIntegrationTest.doesNotStarveOnUnchangedPayments` |
| B26 | 스케줄러는 booking 전용 스레드 두 개로 돌리고, 그 스레드 풀을 `TaskScheduler` 빈으로 내놓지 않는다 | Boot 기본 스케줄러는 스레드가 하나라 결과 확인이 payment를 기다리는 동안 만료가 멈춘다. 빈으로 내놓으면 다른 모듈의 `@Scheduled`가 이 스레드를 함께 쓴다 | `BookingSchedulingConfigTest` |

### 2.4 보상과 관측

| # | 결정 | 이유 | 지키는 검사 |
| --- | --- | --- | --- |
| B19 | "결제는 됐는데 표가 없는" 상태는 자동 환불로 끝낸다. MVP에서는 감지해서 알림과 지표만 남기고, 환불 요청은 트러블슈팅 기간에 붙인다 | 설계서 10장의 원칙이다. 환불 요청 키는 3장, P3를 따른다 | `ReservationPaymentIntegrationTest.staleAndDuplicateResults` |
| B20 | 어긋남을 셀 지표를 둔다. 만료 처리, 승인 재요청, F6 감지는 카운터로, 결제 중 예약 수와 상한 초과 수는 게이지로 둔다 | 9장 측정의 "불일치 건수"와 B17 알림의 근거가 된다 | `BookingSchedulerIntegrationTest.expiresDueHolds`, `BookingSchedulerIntegrationTest.resendsWhenNotReceived`, `BookingSchedulerIntegrationTest.reportsPaymentBacklog`, `ReservationPaymentIntegrationTest.staleAndDuplicateResults` |
| B29 | 예약 취소는 환불을 요청하기 전에 `CONFIRMED → CANCELING`을 기록한다. payment가 환불 요청을 받으면 `CANCELED`와 좌석 반환, 명시적으로 거절하면 `CONFIRMED`로 되돌린다. 결과를 모르면 `CANCELING`으로 두고 취소 확인 스케줄러가 마무리한다. `CANCELING` 동안 좌석은 `SOLD` 그대로이고, 1인 매수 제한에서 유효한 예약으로 센다. 취소 상태 철자는 ERD와 같은 `CANCELING`, `CANCELED`다 | 2026-10-26 팀원 제안이다. 환불을 먼저 부르고 결과를 확인한 뒤 취소하던 이전 설계는, 결과를 모르면 예약이 `CONFIRMED`로 남아 사용자가 다시 취소하지 않는 한 "돈은 돌려받는데 티켓은 유효"가 남았다. 결제 승인의 `PAYMENT_IN_PROGRESS`와 같은 방식으로 표시를 먼저 남기면 스케줄러가 고칠 수 있다. 환불 완료가 아니라 접수까지만 기다리는 것은 P3의 B안 원칙(환불은 payment 책임)을 따른다 | 취소 구현 때 만든다. 지금은 `ReservationStatus`의 `CANCELED`와 DDL CHECK만 맞췄다 |

---

## 3. 다른 담당과 맞물리는 곳의 내 쪽 원칙

| 항목 | 내 쪽 원칙 | 기다리는 답 |
| --- | --- | --- |
| 예약과 좌석을 함께 바꾸는 트랜잭션 | booking 유스케이스는 좌석 연산을 요구 포트로 부르고 같은 트랜잭션에 묶는다. 필요한 연산은 잠금, 판매, 반환 세 가지다. 판매와 반환은 예약 ID를 함께 넘겨, seat가 이 예약이 잡은 좌석만 바꾸게 한다. 만료(B14)도 건별 트랜잭션 안에서 좌석 잠금, 조건부 UPDATE, 좌석 반환 순서로 한다. 테스트는 가짜 구현으로 한다 | S3 (시그니처) |
| 잠금 순서 | booking 유스케이스는 좌석을 id 오름차순으로 먼저 잠그고 예약을 나중에 바꾼다 | S6 (seat도 같은 순서인지) |
| 예약 생성 | seat가 선점 트랜잭션 안에서 `BookingFacade.hold`를 부르고, booking이 HOLD 예약과 좌석마다 티켓(좌석 id, 선점 시점 가격)을 저장한다. 바깥 트랜잭션 없이 부르면 막는다(`Propagation.MANDATORY`). 예약을 만드는 경로가 없으면 나머지 흐름을 시험할 수 없어 이 원칙대로 먼저 구현했다 | S2 (D12) |
| `BookingFacade.expire` | `HOLD`이고 시간이 지났을 때만 `EXPIRED`로 바꾸고 true를 돌려준다. 그 밖에는 false다 | S4 (누가 부를지) |
| 결제 금액 | 기본 금액은 티켓 가격(선점 시점 스냅샷)의 합계로 계산해 준비 요청에 담는다. 할인 후 금액은 payment가 정하고, 준비 응답의 값을 저장해 승인 요청에 쓴다 | S3, D3 (스냅샷), P1 (승인 `amount`) |
| 결제 준비 | `orderId`를 만들고, 트랜잭션 밖에서 부르고, 처음 읽은 `order_id`와 같을 때만 새 값을 기록한다 | P1 (응답 필드, 쿠폰) |
| 결제 승인 | 트랜잭션 1, 트랜잭션 밖 승인, 트랜잭션 2. 같은 승인이 다시 오면 지금 상태를 돌려준다 | P1, P2 (결과 값) |
| `PaymentPort`의 결과 종류 | 승인은 성공, 실패, 모름이다. Phase 2 승인은 접수만 돌려준다. 조회는 끝남, 받지 못함, 진행 중, 모르는 주문이다 | P1, P2 |
| 결과 확인 스케줄러가 다시 보내는 조건 | payment가 "승인 요청을 받지 못했다"고 답할 때만 같은 `orderId`로 다시 보낸다. Phase 2에서는 다시 발행한다 | P2 (그 상태의 이름과 형태) |
| 같은 승인이 동시에 두 번 가는 경우 | 인스턴스가 여러 개이거나 Phase 2 재발행이 consumer 지연과 겹치면 같은 `orderId`가 동시에 간다. payment의 `orderId` 멱등에 맡긴다 | P1 (처리 중인 `orderId`가 다시 오면 무엇을 돌려주는지), P4 |
| 결제 호출 read 대기 | 15초다. payment의 PG 대기(10초)보다 길고 Gateway 응답 대기(60초)보다 짧다. 명령과 조회 두 클라이언트 모두 설정한다. 한쪽만 두면 다른 쪽은 기본값 20초를 쓴다 | P6 (payment가 PG를 재시도하는지) |
| Phase 2 발행 | 커밋 뒤에 발행한다 | P4 (토픽, payload) |
| F5, F6 환불 요청 | 결제가 성공한 시도의 `orderId`로 요청한다 | P3 (환불 키) |
| 외부 API 경로 | `/api/reservations/{reservationId}` 아래에 `payments/checkout`, `payments/confirm`, `cancel`, 조회를 둔다 | S3 (seat 경로와 겹침) |
| application의 Feign·Kafka 의존 | booking의 application 계층은 `feign..`, `org.springframework.cloud.openfeign..`, `org.springframework.kafka..`, `org.apache.kafka..`에 의존하지 않는다. 서비스 전체에 걸면 seat의 코드까지 묶이므로 booking 경로에만 건다. 공통 규칙으로 올릴지는 플랫폼 변경이라 별도 이슈로 제안한다 | 없음. S1이 합의돼 경로(`reservation.booking.application..`)가 정해졌으므로 다음 작업에서 ArchUnit 규칙으로 넣는다 |

---

## 4. 넣지 않은 것

다른 담당의 답에 따라 booking의 상태 전이나 테이블이 바뀌는 항목이다. 팀 문서와 다르게 가자는 제안도 여기에 둔다. 답이 나오면 2장이나 3장으로 옮긴다.

| 항목 | 기다리는 답 | 정해지면 |
| --- | --- | --- |
| `hold_expires_at` 위치 (예약 또는 좌석) | S4 (D13) | 지금은 예약 테이블에 두었다(`booking.sql`). 좌석에 두기로 하면 시간 판정(B6)이 읽는 컬럼과 만료 후보 쿼리를 바꾼다 |
| 결제 중 좌석의 선택 가능 판정 | S5 (D13) | seat 쪽 규칙이다. B14의 이유가 완전해진다 |
| 1인 매수 제한 집계 | S3, D5 | 3장에 집계 쿼리의 내 쪽 원칙을 더한다 |
| 예약 취소 구현 | P3 (D14, 접수·거절 응답), 취소 가능 기간 D6 | 흐름은 B29로 정했다. 합의되면 `CANCELING` 전이와 취소 확인 스케줄러를 구현하고 검사를 단다 |
| 결제 승인 응답 형식 | T5 (D16) | 3장에 넣는다 |
| 이벤트 DTO 위치 | T2 (D17) | 3장 "Phase 2 발행"에 더한다 |
| 토픽, payload, 파티션 키 | P4 | 3장 "Phase 2 발행"에 더한다 |
| 트러블슈팅 기간의 Outbox, 멱등 소비, DLT | 담당 확인, T2 | 설계서 1.1은 booking에 Outbox만, payment에 멱등 소비, 결과 불명 복구, DLT를 둔다. orchestration.md 1장은 멱등 소비와 결과 불명 복구도 booking에 두어 서로 다르다. reservation 쪽 consumer의 중복 제거와 DLT를 누가 맡을지 정한다 |
| 회복 장치의 범위와 기준선 측정 조건 | 팀 (설계서 6.2, 6.3) | 제안: Timeout은 Feign 설정으로, 조회 재시도는 `IdempotentRetryConfig`로 채우고 Resilience4j는 Circuit Breaker에만 쓴다. Phase 1 기준선은 Circuit Breaker를 끈 상태로 잰다. 일찍 끊으면 측정하려는 장애 전파가 가려지기 때문이다. 설계서 6.2는 Resilience4j로 Timeout, Retry, Circuit Breaker를 모두 하도록 적었으므로 팀이 정해야 한다. **공유**: `build.gradle` |
| 취소표 즉시 재판매 | T1 (D7), queue, seat | 트러블슈팅 이슈에서 정한다 |

---

## 5. 이슈별로 만들 검사

orchestration.md 10장의 이슈 후보마다 완료 조건으로 넣는다.

| 이슈 후보 | 만들 검사 |
| --- | --- |
| 예약 상태 모델 | B1, B2, B5, B6, B24, B27, B28, B30 |
| 선점 만료 스케줄러 | B14, B15, B18 |
| 결제 준비·승인과 결과 반영 Phase 1 | B3, B4, B7, B8, B9, B10, B11, B12, B13, B21, B22, B23 |
| 결과 확인 스케줄러 | B16, B17, B19, B20, B25, B26 |
