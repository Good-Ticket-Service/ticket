# 예매 API 명세

- 상태: 작성 중
- 담당:
- 출처: Notion "도메인별 API 명세"의 예매 항목과 "예매 API 공통 계약" (2026-10-26 기준). 담당자가 검토하며 고친다.
- 설계 근거와 합의 항목 번호(S·P·T·D): [orchestration.md](orchestration.md)

구현 상태: 예매 상태 조회, 결제 준비, 결제 승인, 두 스케줄러는 이슈 #15에서 구현했고 develop 머지 전이다. 결제·좌석 연동은 계약 전이라 아직 없다.

## 1. 공통 규칙

- 경로는 `/api/v1/reservations/**`다. Gateway가 reservation-service로 보낸다.
- 인증은 `Authorization: Bearer {accessToken}`이다. Gateway가 토큰을 검증하고 `X-User-Id`, `X-Username`, `X-User-Role`을 넣는다. 클라이언트가 보낸 `X-User-*`는 Gateway가 지운다.
- 회원(`USER`)은 본인 예매만 다룬다. 관리자(`MASTER`)는 모든 예매를 조회할 수 있다. 본인 확인은 서비스가 한다.
- 예매·회차·좌석·쿠폰 ID는 BIGINT 정수, 회원 ID는 UUID 문자열, 금액은 원 단위 정수, 시각은 오프셋 없는 KST(`2026-11-01T20:10:00`)다.
- 성공 응답은 `data`로 감싼다. 회원 도메인과 같은 형식이다.

```json
{ "status": 200, "message": "SUCCESS", "data": { } }
```

실패 응답은 아래 형식이다. 검증 오류(400 VALIDATION_ERROR)에는 `errors`에 `{ field, reason }` 목록이 들어간다.

```json
{
  "status": 409,
  "code": "HOLD_EXPIRED",
  "message": "좌석 선점 시간이 지났습니다.",
  "path": "/api/v1/reservations/3001/payments/confirm",
  "timestamp": "2026-11-01T20:10:01"
}
```

목록 응답의 `data`는 `{ content, page, size, totalElements, totalPages, sort }`다.

### 공통 오류

| 코드 | 상황 |
| --- | --- |
| 400 VALIDATION_ERROR | 형식 오류, 필수 값 누락 |
| 401 UNAUTHORIZED | 인증 없음 (Gateway) |
| 403 FORBIDDEN | 본인 예매가 아님 |
| 404 RESERVATION_NOT_FOUND | 없는 예매 |
| 409 INVALID_STATUS_TRANSITION | 지금 상태에서 할 수 없는 요청 |
| 409 HOLD_EXPIRED | 선점 시간이 지남 |
| 503 SERVICE_UNAVAILABLE | 좌석·payment 연동 실패 |

### 예매 상태

| 상태 | 뜻 | 바뀌는 때 |
| --- | --- | --- |
| HOLD | 좌석을 선점했고 결제 승인 전이다 | 좌석 선점, 결제 실패 뒤 선점 시간이 남았을 때 |
| PAYMENT_IN_PROGRESS | 결제 승인을 요청하고 결과를 기다린다. 이 동안에는 만료되지 않는다 | 결제 승인 요청 |
| CONFIRMED | 예매 확정 | 결제 성공 |
| EXPIRED | 선점 시간 안에 결제를 마치지 못했다 | 선점 만료 스케줄러, 선점 시간이 지난 뒤 결제 실패 |
| CANCELLED | 확정 뒤 취소 | 예매 취소 (제안) |

### 결제 흐름

```txt
① 준비  POST /api/v1/reservations/{id}/payments/checkout  → orderId, 결제 금액, 결제창 URL
② 인증  사용자가 PG 결제창에서 인증한다. 예매 서비스는 관여하지 않는다
③ 승인  POST /api/v1/reservations/{id}/payments/confirm   → 예매 상태
④ 폴링  ③의 응답이 PAYMENT_IN_PROGRESS면 GET /api/v1/reservations/{id}로 결과를 기다린다
```

좌석 선점(`POST /api/schedules/{scheduleId}/holds`)은 [좌석 도메인](../seat/api.md)에 있다. 선점이 성공하면 같은 트랜잭션에서 예매가 HOLD로 만들어진다.

## 2. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 | 구현 |
|---|---|---|---|---|---|
| GET | `/api/v1/reservations/{reservationId}` | 본인 회원(USER), 관리자(MASTER) | 200 | 예매 상태 조회 | 구현 (develop 머지 전) |
| POST | `/api/v1/reservations/{reservationId}/payments/checkout` | 본인 회원(USER) | 200 | 결제 준비 | 구현 (develop 머지 전) |
| POST | `/api/v1/reservations/{reservationId}/payments/confirm` | 본인 회원(USER) | 200 | 결제 승인 | 구현 (develop 머지 전) |
| GET | `/api/v1/reservations` | 회원(USER) | 200 | 내 예매 목록 | 제안 |
| POST | `/api/v1/reservations/{reservationId}/cancel` | 본인 회원(USER) | 200 | 예매 취소 | 제안 |

### GET /api/v1/reservations/{reservationId} — 예매 상태 조회

- 권한·허용 호출자: 본인 회원(USER), 관리자(MASTER)
- 필수 헤더: Authorization
- 성공 HTTP: 200

예매 상태·좌석·선점 만료 시각·마지막 결제 준비 값을 조회한다. 결제 승인 응답이 PAYMENT_IN_PROGRESS면 이 API로 폴링한다.

요청: Path `reservationId: integer`, 본문 없음

응답 `ReservationResponse`

| 필드 | 타입 |
|---|---|
| reservationId | integer |
| scheduleId | integer |
| seatIds | integer[] |
| status | string |
| holdExpiresAt | datetime |
| orderId | string 또는 null |
| paymentAmount | integer 또는 null |

### POST /api/v1/reservations/{reservationId}/payments/checkout — 결제 준비

- 권한·허용 호출자: 본인 회원(USER)
- 필수 헤더: Authorization, `Content-Type: application/json`
- 성공 HTTP: 200

결제 시도마다 새 orderId를 발급하고 payment에 결제 준비를 요청한다. 금액은 서버가 정한다. 쿠폰을 고르면 예매에 쿠폰을 잡고 할인액을 계산한다. 쿠폰을 쓸 수 없으면 400 `COUPON_NOT_AVAILABLE`이다 (제안). 예매는 HOLD 그대로다. payment·좌석 연동 전이라 지금은 503이다.

요청: Path `reservationId: integer`

| 필드 | 타입 |
|---|---|
| userCouponId | integer 또는 null. 내 쿠폰 목록의 userCouponId (제안: couponId에서 이름을 바꾼다) |
| pgProvider | string 또는 null |

응답 `CheckoutResponse`

| 필드 | 타입 |
|---|---|
| orderId | string |
| amount | integer |
| paymentWindowUrl | string 또는 null |

### POST /api/v1/reservations/{reservationId}/payments/confirm — 결제 승인

- 권한·허용 호출자: 본인 회원(USER)
- 필수 헤더: Authorization, `Content-Type: application/json`
- 성공 HTTP: 200

결제창 인증 뒤 승인을 요청한다. 예매를 PAYMENT_IN_PROGRESS로 바꾸고 트랜잭션 밖에서 payment를 부른 뒤 결과를 반영한다. 응답은 예매 상태다.

요청: Path `reservationId: integer`

| 필드 | 타입 |
|---|---|
| orderId | string (64자 이하) |
| pgAuthToken | string (255자 이하) |

응답: `ReservationResponse` (예매 상태 조회와 같음). status는 CONFIRMED / HOLD / EXPIRED / PAYMENT_IN_PROGRESS.

### GET /api/v1/reservations — 내 예매 목록

- 권한·허용 호출자: 회원(USER)
- 필수 헤더: Authorization
- 성공 HTTP: 200

본인 예매를 최근 순으로 조회한다. (제안, 미구현) 공연 제목·회차 시각·좌석 위치를 넣을지는 합의가 필요하다.

요청 Query

| 파라미터 | 규칙 |
|---|---|
| status | string 또는 null |
| page | integer (기본 0) |
| size | integer (10·30·50, 기본 10) |
| sort | string (기본 createdAt,DESC) |

응답 `PageResponse`: `content: ReservationSummary[]`, `page`, `size`, `totalElements`, `totalPages`, `sort`

### POST /api/v1/reservations/{reservationId}/cancel — 예매 취소

- 권한·허용 호출자: 본인 회원(USER)
- 필수 헤더: Authorization, `Content-Type: application/json`
- 성공 HTTP: 200

확정된 예매를 취소한다. payment가 환불 요청을 받은 뒤 CANCELLED로 바꾸고 좌석을 반환한다. (제안, 미구현) 환불 API(P3)와 취소 기간·수수료(D6)가 정해진 뒤 구현한다.

요청: Path `reservationId: integer`, 본문 `{ reason: string 또는 null }`

응답: `ReservationResponse` (예매 상태 조회와 같음). status는 CANCELLED.

## 3. 내부 작업 (API 없음)

| 기능 | 실행 | 설명 |
|---|---|---|
| 선점 만료 | 예매 서비스 스케줄러, 10초 | 선점 시간이 지난 HOLD 예매를 EXPIRED로 바꾸고 좌석을 반환한다 |
| 결제 결과 확인 | 예매 서비스 스케줄러, 30초 | 결제 승인 응답을 받지 못해 결제 중으로 남은 예매의 결과를 payment에 조회해 마무리한다 |

## 4. 사용하는 내부 API

- 결제: [shared/internal-api/payment.md](../shared/internal-api/payment.md) (초안). Phase 2에서는 승인 결과를 Kafka 이벤트로 받는다 (P4).

## 5. 합의할 항목

| 항목 | 지금 예매 구현 | 관련 |
| --- | --- | --- |
| 선점 해제(`DELETE /api/reservations/{id}/hold`) 담당 | 공연 공통 계약 5.12는 예매 서비스 소유로 적었다. 예매를 RELEASED로 바꾸므로 좌석 담당과 나눌 몫을 정한다 | S3 |
| 예매당 좌석 수 | `seatIds` 목록을 다룬다. 좌석 선점은 1장이다 | S3 |
| 상태 이름 | `CANCELLED`, `RELEASED` 없음. ERD 명세서는 `CANCELED`, `RELEASED`, 공연 공통 계약 5.12 선점 응답은 `HELD` | T3 |
| 선점 만료 에러 | 409 HOLD_EXPIRED. 공연 공통 계약 5.12는 410 | 팀 전체 |
| 결제 승인 응답 | 실패·지연도 200과 예매 상태로 답한다 | T5 |
| 결제 준비·승인 요청 필드 | `userCouponId`(제안), `pgProvider`, `orderId`, `pgAuthToken` | P1, #27 |
| 응답 형식 | `/api/v1`, `data` 래퍼, 숫자로 보내는 BIGINT ID, 오프셋 없는 시각. 공연 공통 계약은 `/api`, 래퍼 없음, 문자열 BIGINT, `+09:00`, `traceId` 오류 형식 | 팀 전체 |
| 공연 정보 표시 | 목록에 공연 제목·회차 시각·좌석 위치를 넣으려면 공연 서비스 조회나 선점 때 스냅샷이 필요하다 | 공연 담당 |
