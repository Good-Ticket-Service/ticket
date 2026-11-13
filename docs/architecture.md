# 시스템 아키텍처

- Notion 시스템 구성도를 옮긴 뒤 저장소의 후속 합의에 맞춰 본문을 정리했다. **이 문서가 현재 설계 기준**이며, Notion 원본은 수정하지 않았다.
- 출처: 팀 Notion [시스템 구성도](https://app.notion.com/p/404b3ff41d3980e89bb9e17ce9f7106f)의 기존 전사본과 develop 7a48c5e(#54 포함). 이번 작업에서 실시간 Notion·실제 배포 상태는 검증하지 않았다.
- 합의된 설계와 구현 완료는 다르다. API별 상태는 [api.md](api.md), 현행 DDL과 계획은 [tables.md](tables.md), 공통 표현은 [conventions.md](conventions.md)를 따른다. 배치는 [infra.md](infra.md)에 있다.
- 결정 번호의 뜻은 13절, 담당 확인·미합의·구현 차이는 14절에 모은다. 한 담당 내부의 상세 구현·실험·작업 계획은 이슈 또는 담당 로컬 문서에 둔다.

## 1. 전체 구성도

```mermaid
flowchart LR
    C[클라이언트] -->|HTTPS| GW["gateway :8080<br/>JWT·사용자 헤더·입장 토큰"]
    GW --> U["user :8081<br/>회원·인증"]
    GW --> P["performance :8082<br/>공연·회차·정적 좌석 배치"]
    GW --> R
    GW --> WR["waiting-room :8085<br/>대기열·입장 토큰"]
    GW -->|사용자 재확인| U
    GW -->|보호 목록| WR
    subgraph R["reservation :8083"]
        RS["seat<br/>선점·좌석 조회·잔여석"]
        RB["booking<br/>예매·티켓·Saga 조정"]
        RC["coupon<br/>이전 예정·#125 합의"]
    end
    RB ==>|"준비·조회 Feign 동기<br/>승인 요청·환불 명령 Kafka 합의"| PP["payment :8084<br/>결제·환불·할인액 검증"]
    PP -.->|"결과·환불 접수 Kafka 합의"| RB
    RB -->|로컬 CouponUsage #125| RC
    P -->|"회차 좌석 준비 Feign 이슈 #175"| RS
    RS -->|판매 검증| P
    PP -->|RestClient HTTP| FPG["fake-pg :8090<br/>토스·카카오 형식"]
```

- 실선은 동기 HTTP 또는 서비스 안 모듈 호출이다. 굵은 선은 예매가 조정하는 결제·환불 흐름이다.
- 점선은 결제가 예매에 보내는 메시지다. 승인 요청·결과·환불 메시지는 #318·#319로 합의했고 아직 구현 전이다. 토픽과 필드는 6절과 api.md 「메시지 — Kafka」에 있다.
- 업무 서비스는 Eureka·Config를 사용하고 Prometheus가 지표를 수집한다. fake-pg는 독립 PG 모의 서버이므로 Config·Eureka·공통 업무 모듈을 사용하지 않는다.

## 2. 서비스와 책임

| 서비스·모듈 | 포트 | 책임·데이터 | 담당 |
|---|---|---|---|
| gateway-service | 8080 | 라우팅·JWT·검증된 사용자 헤더, 내부 경로 차단, 좌석 조회·선점의 입장 토큰 검사(#228, 대기열 보호 회차만 #299). 요청 제한은 구현 상태 별도 확인 | 태현·윤범 |
| user-service | 8081 | 회원·인증·역할, user_schema | 태현 |
| performance-service | 8082 | 공연장·고정 좌석 등급/배치·공연 가격·회차·공개·판매 검증, performance_schema | 의연 |
| reservation-service / seat | 8083 | 회차 좌석 스냅샷·선점·실시간 좌석/잔여석 조회, reservation_schema | 정민 |
| reservation-service / booking | 8083 | 예매·티켓·만료·결제 준비/승인·취소/보상 조정, reservation_schema | 한얼 |
| reservation-service / coupon | 8083 | 쿠폰 정책·동기 발급·점유/사용/복원. reservation_schema로 이전 예정, CouponUsage #125 합의 | 윤범 |
| payment-service | 8084 | 결제·환불·PG 실행·결과 확인, payment_schema. 쿠폰 ID/상태는 처리하지 않음 | 현민 |
| waiting-room-service | 8085 | 대기열(room)·순번·입장 토큰, Redis만 사용(DB 없음). 예매 서버와 자원을 나눠 쓰지 않게 분리(#298) | 태현 |
| fake-pg | 8090 | 토스·카카오 호환 거래·장애 주입·결과 조회, fake_pg_schema | 현민 |

- 서비스별 계정·스키마를 격리하고 다른 서비스 데이터의 직접 조회·물리 FK를 두지 않는다. common-core는 비웹 계약, common은 MVC 공통이다.
- seat·booking은 같은 로컬 트랜잭션에서 좌석·예매를 맞춘다. 쿠폰 이전의 목적도 같은 원자성 확보이며, 상세 연결은 #125에서 합의했고 구현했다. 모듈을 별도 서비스로 나누면 기존 원자성을 자동 보존할 수 없다.
- 판매자 정산·수수료·부분 환불은 현행 확장 범위이며 합의 없이 추가하지 않는다.

## 3. 서비스 간 통신

| 흐름 | 현재 기준 | 상태 |
|---|---|---|
| 클라이언트 → 서비스 | Gateway → Eureka lb 라우팅. 실 라우트 차이는 infra 10절 | 기반 구현됨 |
| gateway → user | WebClient GET /internal/users/{userId}, 일반 요청 캐시60초. 결제 관리자 요청은 캐시 우회·MASTER 검사 | 일반 인증 구현됨. 관리자 예외는 #88 브랜치 구현 |
| gateway → waiting-room | 기동 즉시와 10초마다 WebClient GET /internal/waiting-rooms/scopes로 대기열 보호 목록(scope·closeAt)을 받아 메모리에 둔다. 실패는 마지막 목록 유지, 한 번도 못 받으면 좌석 조회·선점 503. 입장 토큰 검사는 토큰 scopes와 URL 회차로만 하고 공연 서비스를 조회하지 않는다 | 구현됨(#299, #228의 gateway → performance 회차 조회 대체). 대상 서비스는 #298로 waiting-room-service |
| 서비스 → 서비스 | Feign이 검증된 사용자 헤더 전달. 서비스 인증은 별도 계약 | 기반 구현됨 |
| performance → seat | 판매자 회차 등록·구성 변경 커밋 후 Feign으로 준비/재준비. POST /internal/schedules/{scheduleId}/seat-setups (api 「좌석 내부 API」). 성능 측정 뒤 Kafka 전환 | 합의(이슈 #175). 공연 쪽 호출 구현됨(이슈 #131), 좌석 쪽 엔드포인트 구현됨(이슈 #190) |
| seat → performance | POST /internal/schedules/{scheduleId}/sale-validations, 선점 트랜잭션 밖 호출 | 기존 합의·DTO 상세 초안 구분 |
| booking → coupon | 로컬 CouponUsage, reservationId 점유 | #125 합의, coupon 구현됨(#159), 예매 연결됨 |
| booking → payment 준비·조회 | Feign 동기 | 구현됨 |
| booking → payment 승인(현행) | Feign 동기. 승인 응답 DONE/FAILED/IN_PROGRESS | 구현됨 |
| booking → payment 승인(목표) | Outbox로 `payment.confirm.requested`를 보내고 사용자에게 바로 PAYMENT_IN_PROGRESS를 준다(#318) | 합의, 구현 전 |
| booking → payment 환불(현행) | HTTP 동기. 200과 해석 가능한 refundStatus면 인수(#127) | 구현됨 |
| booking → payment 환불(목표) | Outbox로 `payment.refund.requested`를 보내고 `payment.refund.result`로 접수 결과를 받는다(#319). HTTP 환불 API는 재요청 경로로 남는다 | 합의, 구현 전 |
| payment → 예매 결과(현행) | 승인 동기 응답과 예매의 결과 확인 조회 | 구현됨 |
| payment → 예매 결과(목표) | 결제가 결과를 정하면 Outbox로 `payment.result`를 보낸다(#319). 결과 확인 조회는 메시지가 빠졌을 때의 안전망으로 남는다 | 합의, 구현 전 |
| payment → fake-pg | RestClient + JDK HttpClient HTTP/1.1. 연결2초·읽기10초(D17) | PG 어댑터 구현됨 |
| 잔여석 조회 | seat가 GET /api/seats/schedules/{scheduleId}/remaining 제공. 공연 카운터 갱신 이벤트는 보류 | 좌석 API 초안 |
| 쿠폰 발급 | POST /api/coupons/{couponId}/issue 동기201 | 구현됨(동기201). Kafka202 전환 미합의 |

- /internal 경로는 Gateway 외부 접근을 차단한다. 내부 API 명세는 api.md에 유지하되 공개 Swagger 노출 여부는 따로 점검한다.
- 사용자 헤더는 서비스 인증의 대체물이 아니다. 서비스 포트 외부 차단은 [infra.md](infra.md) 2-1절을 따른다.
- 입장 토큰은 좌석 조회·선점만 검사한다. 결제·예매 요청에는 적용하지 않는다.
- 예매의 Feign 읽기15초와 결제의20초 제안은 담당 조율 대기다. Gateway 현행 응답 타임아웃은60초다.

## 4. 예매 흐름

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant S as reservation/seat
    participant B as reservation/booking
    participant P as payment
    participant PG as fake-pg
    U->>S: 입장 토큰으로 좌석 선점
    S->>B: 같은 트랜잭션에서 HOLD·티켓 생성
    S-->>U: 예매ID·선점 만료
    U->>B: 결제 준비·쿠폰 선택
    B->>B: 서버 가격·쿠폰에서 할인액 확정
    B->>P: Feign 준비(orderId,totalPrice,discountAmount,expiresAt)
    P-->>B: 금액·상태·checkoutUrl
    B-->>U: 결제창 정보
    U->>PG: 결제창 사용자 인증
    PG-->>U: paymentKey 또는 pg_token
    U->>B: 승인(orderId,pgAuthToken)
    B->>B: PAYMENT_IN_PROGRESS 커밋
    B->>P: Feign 승인(저장 금액)
    P->>PG: HTTP 승인
    PG-->>P: 성공·거절·결과 불명
    P-->>B: DONE·FAILED·IN_PROGRESS
    Note over P,PG: 승인 호출 후60초의 결과 확정 기준은9절
    B->>B: 현재 시도 결과 반영·좌석/쿠폰 원자 변경
    B-->>U: 예매 상태
    U->>B: 결과 불명이면 예매 조회
```

준비 실패 상태를 성공 URL로 매핑하지 않는다. 준비 순서·오래된 요청 역전·HTTP200+FAILED 매핑은 14절에서 조율한다. 위 그림은 현행 동기 승인이다. 메시지 승인(#318 합의)에서는 예매가 PAYMENT_IN_PROGRESS와 승인 요청 메시지를 한 트랜잭션에 커밋하고 바로 답하며, 결과는 `payment.result`로 받는다(6절).

## 5. 예매 상태

```mermaid
stateDiagram-v2
    [*] --> HOLD
    HOLD --> PAYMENT_IN_PROGRESS: 유효한 현재 시도 승인
    HOLD --> EXPIRED: 선점 만료
    HOLD --> RELEASED: 사용자 선점 해제
    PAYMENT_IN_PROGRESS --> CONFIRMED: 현재 시도 결제 성공
    PAYMENT_IN_PROGRESS --> HOLD: 결제 실패·시간 남음
    PAYMENT_IN_PROGRESS --> EXPIRED: 결제 실패·시간 지남
    CONFIRMED --> CANCELING: 취소 의도 저장
    CANCELING --> CANCELED: 환불 요청 인수
    EXPIRED --> [*]
    RELEASED --> [*]
    CANCELED --> [*]
```

- HOLD는 좌석을 선점한 상태다. 준비는 상태를 바꾸지 않으며 실패 후 HOLD 복귀 때 쿠폰은 유지한다.
- PAYMENT_IN_PROGRESS는 선점 만료 정리 대상이 아니다.60초는 결제의 판정 기준이고 잡 지연·서버 중단 중 완료 시한 보장은 아니다(9절).
- CONFIRMED는 좌석 판매·티켓 코드·쿠폰 사용이 함께 확정된 상태다. EXPIRED/RELEASED는 좌석·예약 쿠폰을 반환한다.
- CANCELING은 외부 환불 전 저장하는 의도, CANCELED는 결제의 환불 요청 인수 상태다. PG 취소 완료와 같지 않다.
- facade enum ReservationState와 활성 부분 UNIQUE에는 CANCELING이 있다. domain ReservationStatus·booking.sql CHECK·countActiveSeats 집계에도 더했다(#126).
- 상태 전이는 조건부 UPDATE로 한 번만 반영한다. CANCELING → CONFIRMED 복귀 경로는 두지 않는다(#127). 영구 확정 실패의 PAYMENT_IN_PROGRESS/EXPIRED → CANCELING 전이는 자동 추가하지 않는다(14절).

## 6. Kafka 메시징 범위

예매 ↔ 결제 메시지는 #318(승인 요청)과 #319(결과·환불)로 합의했다. 필드와 소비 처리는 api.md 「메시지 — Kafka」에 있다. 그 밖의 토픽은 아직 제안이다.

| 토픽 | 발행 → 소비 | 상태 |
|---|---|---|
| payment.confirm.requested | 예매 → 결제. 승인 요청과 PG 인증값 | 합의(#318), 구현 전 |
| payment.result | 결제 → 예매. 한 orderId의 최종 결과 DONE 또는 FAILED | 합의(#319). 예매 소비는 이슈 #320에서 구현 중, 결제 발행은 구현 전 |
| payment.refund.requested | 예매 → 결제. 환불 명령 | 합의(#319), 구현 전 |
| payment.refund.result | 결제 → 예매. 환불 접수 결과 | 합의(#319), 구현 전 |
| coupon.issue.requested | 쿠폰 비동기 발급. 후보 식별자 couponId·userId | 미합의. 현재 계약은 동기201 |
| seat.status.changed | seat → 공연 잔여석 반영. 후보 식별자 scheduleId·venueSeatGradeId | 보류. 현재 조회 소유자는 seat |

공통 규칙(합의한 예매 ↔ 결제 토픽)

- 키는 reservationId 10진수 문자열이다. 같은 예약의 요청과 결과가 같은 파티션 번호에 모이고, 본문이 깨져도 DLT에서 키로 예약을 찾을 수 있다.
- 파티션은 12로 시작한다. 결제는 PG 대기를 리스너 스레드에서 하지 않아 동시 처리 수가 파티션 수에 묶이지 않게 한다.
- 소비자 그룹은 토픽마다 따로 둔다. 한 그룹에 구독 토픽이 다른 컨테이너가 있으면 한쪽이 켜지고 꺼질 때마다 다른 쪽도 리밸런스되기 때문이다.
- 발행은 업무 상태와 같은 트랜잭션에 Outbox로 저장한 뒤 발행기가 보낸다. 커밋 뒤 직접 발행은 쓰지 않는다. 적어도 한 번 전달이므로 중복이 올 수 있다.
- orderId로 업무 중복을 막는다. eventId는 소비자가 메시지 중복을 따로 걸러야 할 때 쓰고(예매의 payment.result 소비), 그렇지 않으면 추적용이다(결제의 payment.confirm.requested 소비). 파티션 안의 순서만으로 다른 시도의 늦은 결과를 막지 못한다.
- 소비는 DB에 반영한 뒤 offset을 커밋한다. 해석할 수 없는 메시지만 `<토픽>.DLT`로 보낸다. DLT 이동은 업무 완료가 아니며 수동 재처리 대상이다.
- 메시지 본문을 로그에 통째로 남기지 않는다. 승인 요청에 PG 인증값이 있기 때문이다.

승인 요청의 불변 조건(#318)

- 예매는 선점이 유효할 때만 승인 요청을 받는다. 받는 일은 `HOLD → PAYMENT_IN_PROGRESS` 조건부 UPDATE이고 조건에 선점 만료 전이 들어 있다.
- confirmBefore는 예매가 승인 요청을 받은 시각 + 5분이다. 그래서 confirmBefore는 선점 만료 + 5분보다 이르다. 결제의 READY 결제 정리가 이 조건에 기대므로, 5분(예매 설정 `reservation.booking.payment-confirm.window`)을 바꾸려면 이 계약을 먼저 고친다.
- 결제는 confirmBefore가 지난 요청에 PG를 부르지 않고 FAILED로 끝낸다. 도착했을 때 이미 지났으면 IN_PROGRESS로 바꾸지 않고 바로 FAILED로 끝낸다.
- 결제는 IN_PROGRESS를 커밋한 상태에서 confirmBefore를 확인한 뒤에만 PG를 부른다. 확인한 뒤 IN_PROGRESS를 커밋하면, 그사이 결제가 멈춘 동안 예매가 READY를 보고 실패로 끝낸 뒤 결제가 PG를 불러 돈이 나갈 수 있다. 두 단계를 `status = 'READY' AND confirm_before > now()` 조건부 UPDATE 하나로 해도 된다.
- 예매의 결과 확인은 confirmBefore + 1분이 지난 결제 중 예약만 결제에 묻는다. 1분은 두 서버의 시계 차이 여유다. 결제가 READY(받지 못함)나 FAILED면 실패로 끝내고 HTTP로 승인을 다시 보내지 않는다.
- Kafka가 멈춰 confirmBefore 전에 발행하지 못한 승인 요청은 예매가 HTTP `confirm`에 같은 confirmBefore를 실어 대체 승인으로 보낼 수 있다(#422). 결제는 메시지 승인과 같은 시한·지문·PG별 자리로 처리하고, 자리·PG 허가가 없으면 READY로 둔 채 503 + `Retry-After`로 답한다. 위 불변 조건은 대체 승인에도 같다. 필드·오류는 api.md 결제 내부 절.
- 예매는 Outbox 맨 앞 요청이 일정 시간(`unsent-after`, 기본 5초) 넘게 나가지 못하면 그동안 들어오는 새 승인을 메시지 대신 지금의 HTTP 동기 승인(confirmBefore 없음)으로 부른다(#404). 결제 입장에서는 Kafka가 멈춘 동안 승인이 HTTP 동기 몫(Bulkhead 안의 나머지)으로 몰린다. 대체 승인은 그 전에 Outbox에 남은 요청에만 쓴다. 예매는 대체 승인을 보낸 뒤에도 Outbox 행을 지우지 않으므로, Kafka가 돌아오면 같은 내용의 메시지도 결제에 간다(결제는 같은 지문이면 ACK).

결과의 보장(#319)

- 결제는 한 orderId의 결과를 한 번 정하면 바꾸지 않는다. 결제 상태는 READY·IN_PROGRESS에서만 FAILED로, IN_PROGRESS에서만 DONE으로 바뀐다.
- FAILED로 정한 뒤 늦게 승인되면 결제는 상태를 FAILED로 둔 채 그 orderId만 `AUTO_VOID`로 환불하고, DONE을 발행하지 않는다.
- DONE 결제가 환불돼 CANCELED가 되는 것은 결과 변경이 아니므로 `payment.result`를 다시 보내지 않는다.
- 결과가 정해지는 모든 경로에서 발행한다. 승인 직후, 결과 확인 복구, 결과 확정 시한 FAILED, confirmBefore 초과 FAILED, 준비 만료가 모두 포함된다. 동기 응답으로 이미 알린 결과도 보낸다. 결제는 예매가 응답을 받았는지 알 수 없기 때문이다.

## 7. 실패와 보상

| 상황 | 처리·경계 |
|---|---|
| 선점 만료·미결제 | HOLD만 EXPIRED로 전이하고 좌석·쿠폰 해제 |
| 승인 실패 | 현재 시도의 결과만 반영. 시간 남으면 HOLD, 지났으면 EXPIRED |
| 승인 응답 유실·조회 장애 | PAYMENT_IN_PROGRESS 유지·폴링.60초 판정 기준은9절, 조회 불가만으로 좌석 반환 금지 |
| PG 호출 전 READY(현행 동기 승인) | 승인 접수 여부를 조회하고 같은 orderId·인증값으로 재전송. 세부 재전송 시각·404 분기는14절 |
| PG 호출 전 READY(메시지 승인, #318) | confirmBefore + 1분 뒤에도 READY면 결제가 요청을 받지 못한 것으로 보고 실패로 끝낸다. 결제는 confirmBefore가 지난 요청에 PG를 부르지 않으므로 다시 보내지 않는다 |
| FAILED 종료 후 늦은 과금 | 해당 orderId만 AUTO_VOID, 새 예매 시도·쿠폰은 보존. 결제 담당의 단계별 최소 Reconcile로 확인하며 최종 누락 부재의 증명은 아님. 실제 PG 취소와 전체 내역 대사는 별도 |
| 과금됐으나 확정 불가(F6) | 현재 시도의 성공을 좌석 때문에 확정하지 못하면 refund_required_at에 기록한다(커밋되므로 재시작 뒤에도 남는다). 결과 확인 스레드가 같은 orderId로 refund(CONFIRM_FAILED)를 요청하고, 200과 해석 가능한 refundStatus면 PAYMENT_IN_PROGRESS→CANCELED·쿠폰 반환. 결과 모름은 결과 확인 기준 시간 뒤 같은 orderId로 다시 요청하고, 404는 결제 중을 유지해 체류 알림으로 넘긴다(#155) |
| 환불 접수 응답 유실 | CANCELING 유지. 취소 확인 스케줄러가 조회 없이 CANCELING 진입 때 고정한 orderId로 재요청한다. 200과 해석 가능한 refundStatus면 CANCELED. 404는 알림·CANCELING 유지(#127) |
| 환불 PG 거절·장기 불명 | 금융 미완료는 결제가 관리. 인수 후 취소한 티켓을 되살리지 않음. 접수 전 정책상 거절과 구분 |
| 회차 취소 | 공연202 접수와 예매 전달·PG 완료는 구분. 전파·재시도·상태별 정리는14절 계약 대기 |

자동 환불이 목표여도 감지·로그만으로 보상 완료를 보고하지 않는다. 영속 의도·재시도·운영 인수까지 연결해야 하며, 구현 완료 여부를 담당 단독 설계와 혼동하지 않는다.

## 8. 플랫폼 구성 요소

| 구성 | 역할 |
|---|---|
| Eureka·Config Server | 서비스 등록/발견·외부 설정. fake-pg 제외 |
| PostgreSQL | 단일 인스턴스에 전용 스키마/계정. 물리 장애·자원은 공유 |
| Redis | 대기열·입장 토큰·캐시·쿠폰 발급 판단. 키/TTL 세부는 해당 계약 |
| Kafka |6절 고도화 명령·결과. 단일 브로커는 고가용성 아님 |
| Prometheus·Grafana | HTTP·JVM·DB 풀·PG 지연·미완료 거래, 고도화 후 lag/DB 작업 대기 |

포트·서버·배포·측정 환경은 infra에만 정의한다.

## 9. 예매 ↔ 결제 연동 규칙

1~7절에 없는, 예매와 결제가 함께 지켜야 하는 규칙이다. 결제 내부 동작(복구·대사·워커·검증 관문)은 결제 담당 문서에서 관리한다.

- **PG 인증값(현행 동기 승인):** 토스 paymentKey·카카오 pg_token은 미완료 승인을 재전송하는 동안만 예매가 보관하고, 끝나면 지운다. 응답·로그·이벤트·메시지에 남기지 않는다.
- **PG 인증값(메시지 승인, #318):** 승인 요청 메시지에 싣는다. 인증값만으로는 승인을 부를 수 없기 때문이다. 토스는 결제 서버에만 있는 시크릿 키가, 카카오는 cid·tid·가맹점 키가 함께 필요하다. 보관은 원 토픽 1일, DLT 7일이고 예매 발행·결제 소비만 접근한다. 예매는 재전송하지 않으므로 예약 행에 보관하지 않고, Outbox 행은 발행한 뒤 지운다. 응답·로그에는 남기지 않는다. 토스 paymentKey는 결제가 거래 ID(pgTransactionId)로 저장한다.
- **현재 시도만 반영:** 결과의 orderId가 예매의 현재 orderId와 같고 예약이 결제 중일 때만 반영한다. 지금 시도의 성공을 좌석 때문에 확정하지 못하면 7절 F6 행대로 환불한다.
- **지난 시도·끝난 예약의 성공:** 예매는 환불하지 않고 지표와 경고 로그만 남긴다(#319). 예매가 다른 시도로 넘어가거나 예약을 끝내는 것은 그 orderId가 FAILED로 정해진 뒤뿐이고, 결제는 FAILED를 DONE으로 바꾸지 않고 늦은 승인을 `AUTO_VOID`로 돌려주기 때문이다(6절 결과의 보장). 확정한 orderId의 DONE은 같은 결과를 다시 받은 것이다.
- **결과 확정 시한:** 결제는 PG 승인 호출 시각부터 60초가 지나도 결과를 모르면 FAILED(`PG_UNRESOLVED`)로 확정한다. 서버 중단·적체 중에도 정확히 60초에 끝난다는 보장은 아니고, 실제 과금이 없었다는 증명도 아니다. 그래서 늦은 승인은 결제가 그 orderId만 자동 환불(`AUTO_VOID`)하고, 새 시도의 예매·쿠폰·티켓은 건드리지 않는다.
- **조회가 안 될 때:** 결제 조회가 실패했다는 이유만으로 예매가 좌석·쿠폰을 풀지 않는다.
- **환불의 세 단계:** 환불 접수, PG 취소 성공, 고객 카드·계좌 반영은 각각 다른 시점에 끝난다. `NOT_REQUIRED`·`SKIPPED`의 의미는 api.md 결제 계약을 따른다.
- **취소 정책:** 사용자는 회차 시작 전에만 정책에 따라 취소할 수 있다. 기한은 CONFIRMED → CANCELING 때 선점 시 저장한 회차 시작 시각으로 판정하고(`now < scheduleStartAt`), 받은 취소는 회차가 시작된 뒤에도 마무리한다(#126·#127). 회차 취소와 확정 실패 보상은 사용자 취소 기한의 예외다. 부분 환불·취소 수수료 차감은 지원하지 않는다.
- **환불 실패와 예매:** 환불이 FAILED·차단돼도 취소한 예매·티켓을 되살리지 않는다. 환불 완료는 결제가 끝까지 책임진다.

## 10. 공연·좌석 준비

- 물리 좌석/등급은 공연장 고정, 가격·제외 정책은 공연 단위로 모든 회차가 공유. 회차별 점유만 seat 소유. 공개 후 배치/가격/제외 불변, 표시 정보 수정 범위는 API 계약.
- DRAFT이며 삭제 회차 포함 등록 이력이 없을 때만 venue 변경. 가격/제외 초기화와 공연 버전 갱신을 한 트랜잭션에 수행.
- 공개 공연에도 새 회차 추가 가능. 부모 공연 잠금으로 공개/회차 구성 변경 직렬화. performance.version과 schedule.version은 독립, 회차 시간 변경으로 performance.version 증가 안 함.
- 회차 PENDING → 준비 성공 READY / 실패 FAILED. 재전송·자동 재시도는 같은 requestId. **#54 최신 기준: configVersion 변경 시에만 새 requestId**. 결과의 현재 requestId/configVersion 일치 확인. 이전 회의의 '확정 실패 후 무조건 새 requestId'보다 이 계약이 최신이다.
- 같은 버전 재처리는 좌석 중복 생성 없이 이번 요청에 맞는 결과 재현. 낮은 버전 무시, 높은 버전 교체는 기존 모든 좌석 AVAILABLE일 때만. 만료 미정리 HOLD는 AVAILABLE 아님. 회차 시간 변경은 좌석 스냅샷에 없으므로 재준비 불필요.
- 공개 조건에 READY를 강제하지 않지만 실제 판매는 READY 필요. 결과 불명은 아래 복구 기준을 따른다. 영구 구성 오류·미완성 공개 회차의 경보·운영 대응은 별도 조율한다.
- 일시 준비 장애(연결/timeout/5xx)는 PENDING 유지·같은 requestId 자동 재시도, 최대 횟수 초과/4xx 검증 오류는 FAILED+안전한 오류 요약. 가격/제외 구성 변경 시 configVersion 증가·새 requestId로 재준비. 판매자 전용 재시도 API 없음.
- 결과 불명 FAILED는 복구 의도·시도·임대를 DB에 남겨 같은 요청으로 제한적으로 재확인한다(이슈 #252). 현재 요청·버전·복구 시도 번호·부모 공연과 회차의 유효성을 재검사한 뒤 결과를 반영한다. 다른 서비스 호출 중 DB 트랜잭션을 유지하지 않는다.
- 확정 오류·IN_USE 한도 초과·미분류 과거 FAILED는 결과 불명 자동 복구에서 제외한다. 복구 한도 소진 뒤에는 운영자 명령으로 감사 기록과 함께 재접수하며, 관리자·판매자 HTTP API는 만들지 않는다.
- 이미 공개된 공연의 publication 재요청은 If-Match 불일치에도 현재 결과200·최초 publishedAt 보존, DRAFT 복귀 없음. 공연이 참조한 물리 배치는 변경 금지(VENUE_LAYOUT_IN_USE), 미삭제 좌석이 쓰는 등급 삭제 금지(VENUE_GRADE_IN_USE). 일괄 좌석 중복은 전체 롤백.
- 판매 검증은 신규 선점 정책/가격 확인이며 좌석 확보 보장 아님. 검증 직후 취소 경쟁은 전파/보상으로 처리. 판매 중지는 신규 선점 차단, 기존 유효 HOLD 결제는 원래 만료까지 허용.

## 11. 선점·예매 로컬 원자성

- 선점10분, MVP 회원·회차당1장. seat가 공유 Clock(Asia/Seoul)으로 holdExpiresAt을 한 번 계산, 좌석/예매에 동일 저장. 결제 중 연장 안 함. 과거 DB now() 초안은 #34 공유 Clock으로 대체.
- 잠금 순서 **좌석 ID 오름차순 → 예약**. 선점·만료·확정·해제·취소 모두 동일. 모듈 facade 모든 메서드 MANDATORY. CouponUsage는 REQUIRED로 예매 트랜잭션에 참여한다(#125).
- 매수 제한 사전 count + DB 부분 UNIQUE로 최종 방어. 유니크 위반은 전체 선점 롤백, 제약 이름이 맞을 때만 트랜잭션 밖409 변환. 본인 만료 HOLD는 옛 좌석 잠금→예약 EXPIRED→좌석 반환 뒤 재선점.
- 같은 회원/좌석의 만료 전 HOLD 재요청은 기존 예약200. 가격은 seat.base_price→ticket.price로 복사, 결제 totalPrice는 티켓 합. 클라이언트 가격 신뢰 금지.
- 만료 `HOLD AND expires<=:now`, 결제 시작 `HOLD AND expires>:now`. PAYMENT_IN_PROGRESS는 만료 정리 대상 아님. 상태 변경0행은 재조회/분기, 성공 가정 금지.
- 선택 가능(좌석 배치도) = 판매 가능 AVAILABLE + 만료됐고 예약이 PAYMENT_IN_PROGRESS가 아닌 HOLD. 만료 HOLD의 예약ID를 모아 findPaymentInProgress 한 번 호출, 다른 모듈 테이블 JOIN 안 함. 실제 재선점은 expireIfDue 조건부 변경으로 최종 방어.
- 좌석 배치도는 회차별 판정 결과를 서버 메모리에 캐시한다(이슈 #270). 1초(설정값)가 지나면 기존 결과를 주면서 전용 스레드 2개가 뒤에서 다시 만들고, 5초(설정값)가 지나면 만료한다(이슈 #311). 요청 스레드가 로딩을 기다리며 회차 키 잠금에 묶이지 않게 하고, 다시 만들 때 쓰는 DB 연결 수를 전용 스레드 수로 묶는다. 캐시에서 답할 때는 트랜잭션과 DB 연결을 쓰지 않는다. 기존 조회와 layout·status(이슈 #271)는 같은 스냅샷에서 만든다. 늦은 결과는 409 SEAT_ALREADY_TAKEN 증가로만 나타나고, 이중 선점은 선점 API의 잠금과 재판정이 막는다.
- 잔여석 = 판매 가능 AVAILABLE만 등급별로 센다. 만료됐지만 정리 전인 HOLD는 booking에 묻지 않고 제외한다(안내용, 좌석이 적게 보이는 쪽으로만 차이). 서버 메모리 캐시 TTL 3초(이슈 #241).
- SeatFacade.sell은 HOLD+reservation_id 일치, release도 reservation_id 일치. 갱신 행 수가 요청 수와 다르면 확정 성공으로 처리하지 않는다. 가격 재조회(priceSnapshot) 없음.
- release 결과 RELEASED/EXPIRED/ALREADY_CLOSED만 좌석 반환. NOT_RELEASABLE은409, PAYMENT_IN_PROGRESS/CONFIRMED/CANCELING/CANCELED 좌석은 해제하지 않음.

## 12. 예매 ↔ 쿠폰 (CouponUsage #125 합의)

메서드와 타입은 api.md 「서비스 내부 모듈 계약」에 있다.

- reservationId로 점유하고, 발급 시점의 할인액을 쓴다. reserve 때 유효기간을 확인하고, 이후 RESERVED는 만료 배치가 풀지 않는다. 유효기간이 지나도 결제가 확정되면 USED다.
- 결제 FAILED로 HOLD에 돌아가면 쿠폰을 유지하고, EXPIRED·RELEASED면 해제한다. CANCELED면 USED를 복원한다(원래 만료됐으면 EXPIRED).
- 쿠폰을 바꾸면 기존 쿠폰의 release를 먼저 하고 새 reserve를 같은 트랜잭션에서 한다.
- 취소한 예매의 쿠폰은 취소 확정(결제가 환불 요청을 받아 예매가 CANCELED가 될 때) 때 복원한다. 환불 완료를 기다리지 않는다. 환불이 끝내 실패해도 취소한 예매를 되살리지 않으므로 쿠폰도 복원한 채로 둔다.
- 할인액을 최소 결제 금액에 맞춰 자르는 일은 예매가 한다. 쿠폰은 발급 시점 할인액을 그대로 주고, 결제는 보정 없이 검증한다.

## 13. 결정 번호

root 문서에 나오는 번호의 뜻이다. 여기에 없는 번호는 추측하지 말고 Git 이력·이슈에서 찾거나 담당에게 묻는다.

| 번호 | 뜻 | 상태 |
|---|---|---|
| D16 | (결제) 예매 → 결제 준비·조회는 Feign 동기다. 승인은 #318로 바꿔 메시지로 요청하고 PG 인증값을 싣는다. 처음에는 승인도 동기로 두고 인증값을 메시지에 싣지 않기로 했다 | #318로 변경 합의(결제 담당 확인). 구현 전 |
| D17 | (결제) PG는 Feign이 아니라 RestClient + JDK HttpClient로 부른다. 공통 Feign의 `X-User-*` 헤더·오류 변환이 외부 PG에 맞지 않는다 | 구현됨 (#45) |
| D18 | (결제) 확정할 수 없는 결제는 보상 의도를 영속 저장해 재시작 뒤에도 이어서 처리한다. 로그·지표만으로 완료로 보지 않는다 | 결제 결정. 예매는 refund_required_at 기록과 CONFIRM_FAILED 환불로 반영(#155) |
| B19 | (예매) "결제는 됐는데 표가 없는" 상태(F6)는 자동 환불로 끝낸다. MVP부터 같은 orderId로 CONFIRM_FAILED 환불을 요청하고, 결제가 받으면 예매를 CANCELED로 끝낸다 | 예매 결정. 구현 #155 |
| F6 | (예매 실패 유형) 지금 시도의 결제는 성공했는데 좌석 때문에 확정하지 못한 경우. 그 orderId로 CONFIRM_FAILED 환불을 요청한다. 지난 시도·끝난 예약에 온 성공은 #319에 따라 환불하지 않는다 | 예매 설계 |
| #22 | 결제는 쿠폰을 처리하지 않는다. 예매가 확정한 정가·할인액만 받아 검증·저장한다 | 합의 (#25에서 예매 담당 승인) |
| #27 | CouponUsage를 예매 기준(reservationId 점유)으로 바꾸는 계약 | #125로 이어서 합의 |
| #125 | CouponUsage의 메서드·반환 타입, USED 복원, 취소 확정 때 복원, 할인액은 예매가 자름 | 합의 (쿠폰·결제 담당 확인) |
| #126 | 선점 때 HoldTarget으로 회차 시작 시각·공연명을 넘겨 예약에 스냅샷으로 저장하고, CANCELING을 domain/CHECK/집계에 반영한다 | 합의 (좌석 담당 확인) |
| #127 | 예약 취소 API, NOT_CANCELLABLE 409, 환불 200 응답의 인수 해석, 같은 orderId 재요청, CANCELING → CONFIRMED 복귀 없음 | 합의 (결제·좌석 담당 확인) |
| #34 | seat ↔ booking은 facade 패키지의 메서드·타입으로만 서로 부르고, 공유 Clock으로 선점 만료를 계산한다 | 합의 |
| #86 | 결제 준비의 sellerId·performanceId는 판매 검증(P21) 응답에서 받아 선점 때 예약에 저장한 값을 쓴다 | 합의 |
| #54 | 공연 명세의 최신 원본. 회차 좌석 준비는 configVersion이 바뀔 때만 새 requestId를 쓴다 | 합의 |
| #175 | 회차 좌석 준비는 MVP Feign(`POST /internal/schedules/{scheduleId}/seat-setups`)으로 하고 성능 측정 뒤 Kafka로 바꾼다. 좌석은 (scheduleId, configVersion)으로 멱등 판단하고 응답에 requestId를 돌려준다 | 합의 (좌석·공연 담당 확인) |
| P3·P4 | (Notion) 환불(P3)·승인(P4) Phase 2 전환 안건 | P4는 D16으로 철회했다가 #318로 다시 합의, P3는 #319로 합의 |
| #318 | 승인을 `payment.confirm.requested` 메시지로 요청하고 PG 인증값을 싣는다. confirmBefore·처리 순서 불변 조건 | 합의 (결제 담당 확인) |
| #319 | 결과(`payment.result`)·환불 명령·환불 접수 결과 메시지. 결과는 한 번 정하면 바꾸지 않음 | 합의 (결제 담당 확인) |
| #422 | HTTP `confirm`의 선택 항목 confirmBefore로 메시지 대체 승인(메시지와 같은 시한·지문·PG별 자리, 자리 없으면 503 + Retry-After). #403에서 출발 | 합의(예매·결제 담당 확인). 결제·예매 구현됨(예매 #404, 예매 대체 승인 스위치 기본 꺼짐) |
| T3 | (Notion) 취소 상태 이름을 CANCELING·CANCELED로 정한 결정 | 확정 |
| #25 | (PR) #22 할인액 전달·쿠폰 책임 분리에 예매 담당이 동의한 리뷰 | 합의 근거 |
| #30 | (PR) fake-pg의 카카오 호환 API 추가 | 구현됨 |
| #45 | (PR) 토스·카카오 PG 어댑터와 RestClient 기반 처리 | 구현됨 |
| #48 | (PR) event-service를 performance-service로 변경 | 구현됨 |
| #57 | (PR 리뷰) 좌석 DDL을 seat.sql로 분리, schema.sql 수정하지 않음 | 담당 합의. #66에서 seat.sql 반영 |
| #66 | 좌석 상태별 예약ID/만료시각 일관성 CHECK(`ck_p_schedule_seats_holder`) 추가 | 예매 담당 리뷰로 합의 |
| B21·B22 | 예매 실패 후 orderId/금액 정리, 중복 승인/만료 경계 처리 | 현행 예매 동작 |
| B27·B28 | booking.sql 분리, 선점 때 티켓·가격 저장 | 현행 예매 동작 |
| B31~B33 | 활성 예매 UNIQUE, facade 경계, 공유 Clock | #34 및 현행 구현 기준 |
| I1~I8 | 인프라 운영·배치의 결정 필요 항목 | infra 8절 참조 |

api의 P1~P21은 공연 응답 DTO 식별자이며 Notion 전환 안건 P3/P4와 다르다. 구현 상태는 API 절의 표시를 따른다.

## 14. 미합의·구현 차이 — 여기서만 관리

확정 정책은 다시 협의 대상으로 돌리지 않는다. 아래는 구현 연결·이행 또는 남은 충돌의 담당 확인 목록이다. 일부 코드가 존재해도 서비스 연동 전체가 구현됐다는 뜻은 아니다.

| 항목 | 현재 근거·필요한 결정/후속 작업 | 관련 담당 |
|---|---|---|
| 준비 순서 | 같은 예매 준비의 직렬화/영속 소유 방식, 오래된 원격 시도 차단, READY_FAILED 매핑. 타임아웃 재전송은 같은 orderId 유지 제안 | 예매·결제 |
| CouponUsage 구현 | 계약은 #125로 합의했고 coupon 모듈의 CouponUsage(#159)와 예매의 호출 연결(CouponUsageAdapter)을 구현했다. 할인액 자르기와 예매 쿠폰 DDL은 #151에서 반영했다 | 예매 |
| 취소·보상 | 사용자 취소는 #126·#127대로 구현했고, 취소 확정 때 쿠폰도 돌려준다. F6 자동 환불은 #155(CONFIRM_FAILED, PAYMENT_IN_PROGRESS→CANCELED). 결제 쪽 응답 규칙이 USER_CANCEL과 같은지 결제 확인 | 예매·좌석·결제 |
| 회차 취소 | 공연202만 확정. 전달 URI/payload/영속 재시도 주체·결제중 보호·신규 판매 차단·PG 환불 조회 합의. reservation-summary만으로 PG 환불 완료 판단 금지 | 공연·예매·좌석·결제 |
| 삭제 전 조회 | 공연/회차 삭제의 예매 존재 확인·오류·의존 조회 불가 정책 계약 | 공연·예매 |
| 연령·탈퇴 | 미달 차단에 필요한 생년월일/기준시각/ageRating 전달, 진행 거래·공연 보유 회원 탈퇴 정책 | 회원·공연·예매 |
| 시각·페이징 | +09:00 Offset 공통 목표의 DTO별 이행, Page DTO/size 통일. 감사 주체 정책과 직렬화 형식은 구분 | 전 도메인 |
| 입장·캐시 | TTL10분·좌석조회/선점 적용은 확정. 토큰 범위는 공연 대신 대기열(room)의 회차 scope이고, Gateway는 URL 회차가 토큰 scopes에 있는지로 검사해 회차→공연 조회가 없다(#299, 대기열 단독 변경). 발급/전달 응답과 오픈 전 진입(409 WAITING_ROOM_NOT_OPEN)은 api 대기열 절. 잔여석 캐시는 서버 메모리 TTL 3초로 확정(이슈 #241, 부하 측정 뒤 조정) | 대기열·좌석·Gateway |
| 만료 직전 요청 | payment.expiresAt=holdExpiresAt 경계·전송 중 만료 처리·grace 합의 | 예매·결제 |
| Feign timeout | 현행 예매15초와 결제20초 제안의 실행 예산 조율 | 예매·결제 |
| 조회404 | 진행 중 결제 조회404는 알림·상태 유지. 준비 재시도 안내와 구분. ORDER_ID_MISMATCH는 현행409. NOT_CANCELLABLE은 409로 합의(#127) | 예매·결제 |
| 금액 타입 | payment_amount 및 원본 discount_amount BIGINT에서 공통 INT 목표로 DDL·DTO 이행 범위·시점 | 예매·결제 |
| 내부 보안·운영 | 결제 관리자 JWT·MASTER·캐시 우회는 #88 브랜치 구현(api/infra 참조). 서비스 인증 전반·직접 접근 통제·공통 감사 주체·미완료 경보는 별도 확인 | 플랫폼·각 도메인 |
| 추가 API | 티켓 조회/관리자 예매 목록은 현행 계약 없음. 전체 공연 취소 API는 제공하지 않고 회차별 취소 | 예매·공연 |
| 좌석 준비 통신 | MVP Feign 계약은 이슈 #175로 합의했다(api 「좌석 내부 API」). read 타임아웃 5초는 유지한다(좌석 직접 호출 측정: 2만 석 최악 0.53초, 이슈 #175). 좌석 쪽 잠금 대기 상한 3초는 잠금마다 걸려 전체 대기가 5초를 넘을 수 있지만, 넘으면 같은 requestId 재시도로 같은 결과를 받는다. 공연 → 좌석 연동을 local에서 확인했다(회차 등록 응답 1,000석 p95 약 48ms, 이슈 #190). 결과 불명 복구는 이슈 #252에서 제한된 자동 확인·운영자 명령으로 구현했다. 남은 것: Kafka 전환 일정·토픽·스키마 | 공연·좌석 |
| 공연 복원 검토 | 등급/좌석 미존재 오류, 공개 정적배치 권한, 취소 목록 포함, 공개검증, salesStatus/period 대상 회차 확인. 공개 wrapper/숫자ID/UNAUTHORIZED는 공통 기준 유지 | 공연·좌석 |
| 판매 검증 경계 | 중지 후 기존 유효 HOLD 결제 허용은 기존 정책. 취소 경합·판매 마감 이후 확정 경계·필요한 재검증 확인 | 공연·예매·좌석 |
| 결제 메시지 전환 | 계약은 #318·#319로 합의했다. 남은 것은 결제의 Outbox·발행기·승인 요청 소비자, 예매의 메시지 승인(이슈 #321)·환불 명령 구현이다. 메시지 승인은 예매 인스턴스가 모두 새 버전이 된 뒤 켠다. HTTP 승인 API를 지우는 시점은 전환 뒤 정한다. Kafka 장애 때의 HTTP 대체 승인은 #422로 합의했고 결제·예매 모두 구현했다(예매 #404). 예매 대체 승인은 기본 꺼짐이며 #422가 들어간 결제를 배포한 뒤 켠다 | 예매·결제 |
| 쿠폰 발급 방식 | 현재 동기201 유지. Kafka202 전환 대상·접수 보장·시점 미합의 | 쿠폰·플랫폼 |
| 잔여석 이벤트 | 소유는 seat. performance 카운터·seat.status.changed 도입 여부와 조회 구현 연결 확인 | 좌석·공연 |
| Facade 조회 경계 | 모든 메서드 MANDATORY. getReservation은 호출자 트랜잭션에서 비잠금 사전 조회, 좌석→예약 변경 순서 유지 | 좌석·예매 |
| 실제 Gateway 경로 | 공통 계약 경로와 현행 하위 라우팅의 이행 확인. HOLD_EXPIRED는 합의·현행409 유지, 이전410을 재적용하지 않음 | 공연·좌석·예매·Gateway |
| fake-pg 결제창 경로 | 브라우저 접근 경로는 미구현. Gateway 라우트가 없고 서비스 포트를 외부에 열지 않는 배포 기준과 연결 필요 | 플랫폼·결제 |
