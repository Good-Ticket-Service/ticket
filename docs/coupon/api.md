# 쿠폰 API 명세

- 상태: 작성 중
- 담당:
- 출처: Notion "도메인별 API 명세"의 쿠폰 항목 (2026-10-26 기준). 담당자가 검토하며 고친다.

외부 API(`/api/v1/**`)만 둔다. 공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다. 쿠폰 모듈은 reservation-service에 있고(제안), gateway가 `/api/v1/coupons/**`를 reservation-service로 보낸다. 예매와의 모듈 계약은 [shared/modules/coupon-usage.md](../shared/modules/coupon-usage.md)다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| POST | `/api/v1/coupons` | MASTER | 201 | 쿠폰 생성 |
| PATCH | `/api/v1/coupons/{couponId}` | MASTER | 200 | 쿠폰 수정 |
| POST | `/api/v1/coupons/{couponId}/close` | MASTER | 200 | 쿠폰 발급 중단 |
| DELETE | `/api/v1/coupons/{couponId}` | MASTER | 204 | 쿠폰 삭제 (아무도 받지 않은 쿠폰만) |
| GET | `/api/v1/coupons` | 로그인 | 200 | 쿠폰 목록 조회 |
| GET | `/api/v1/coupons/{couponId}` | 로그인 | 200 | 쿠폰 상세 조회 |
| POST | `/api/v1/coupons/{couponId}/issue` | USER | 201 | 쿠폰 선착순 발급 |
| GET | `/api/v1/coupons/me` | 로그인 | 200 | 내 쿠폰 목록 조회 |

- 관리자 역할은 MASTER로 통일한다(팀 결정). ADMIN이라는 역할 이름은 쓰지 않는다.
- 「로그인」은 역할과 상관없이 로그인한 회원(USER, SELLER, MASTER)을 뜻한다. `@RequireRole`을 붙이지 않는다.
- 쿠폰 목록·상세는 SELLER에게도 공개한다. 할인액·수량·기간은 민감한 정보가 아니고, 판매자도 자기 공연이 어떤 할인으로 팔리는지 알 수 있다. SELLER가 조회하면 `issuedByMe`는 항상 false다.
- 발급만 USER로 제한한다(`@RequireRole(Role.USER)`). `RequireRole`은 역할이 정확히 같은지만 보므로 MASTER·SELLER는 403 `FORBIDDEN`을 받는다.

### 공통 실패 응답

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "...",
  "path": "/api/v1/coupons",
  "timestamp": "2026-11-01T10:00:00",
  "errors": [ { "field": "name", "reason": "..." } ]
}
```

`errors`는 `VALIDATION_ERROR`일 때만 있다. 시각은 `yyyy-MM-ddTHH:mm:ss`다.

### POST /api/v1/coupons — 쿠폰 생성

- 권한·허용 호출자: MASTER
- 필수 헤더: `Authorization: Bearer {accessToken}`, `Content-Type: application/json`
- 성공 HTTP: 201

관리자가 정액 할인 쿠폰을 만든다. 발급 기간, 총수량, 발급 후 사용 가능 일수를 정한다. 검증에 실패하면 400 `VALIDATION_ERROR`를 돌려준다.

요청

```json
{
  "name": "오픈 기념 5,000원 할인",
  "discountAmount": 5000,
  "totalQuantity": 1000,
  "issueStartAt": "2026-11-01T10:00:00",
  "issueEndAt": "2026-11-07T23:59:59",
  "validDays": 7
}
```

| 필드 | 규칙 |
|---|---|
| name | 필수, 1~100자 |
| discountAmount | 필수, 1 이상 |
| totalQuantity | 필수, 1 이상 |
| issueStartAt | 필수 |
| issueEndAt | 필수, issueStartAt 이후이면서 현재 이후 |
| validDays | 필수, 1 이상 |

응답 `data` (메시지 "쿠폰이 생성되었습니다.")

| 필드 | 타입 |
|---|---|
| couponId | Long |
| name | String |
| discountAmount | Integer |
| totalQuantity | Integer |
| remainingQuantity | Integer |
| issueStartAt, issueEndAt | String (yyyy-MM-ddTHH:mm:ss) |
| validDays | Integer |
| issueStatus | String (UPCOMING / ISSUING) |

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | VALIDATION_ERROR | 필수 값 누락, 길이·범위 위반, 발급 기간 순서가 틀림 |
| 401 | UNAUTHORIZED | 토큰이 없거나 유효하지 않음 |
| 403 | FORBIDDEN | MASTER 역할이 아님 |

### PATCH /api/v1/coupons/{couponId} — 쿠폰 수정

- 권한·허용 호출자: MASTER
- 필수 헤더: `Authorization: Bearer {accessToken}`, `Content-Type: application/json`
- 성공 HTTP: 200

쿠폰 정보를 부분 수정한다. 발급이 시작된 뒤에는 할인액과 유효 일수를 바꿀 수 없다. 먼저 받은 사람과 나중에 받은 사람의 조건이 달라지지 않게 하기 위해서다.

요청 (바꿀 필드만)

```json
{
  "name": "오픈 기념 5,000원 할인 (연장)",
  "discountAmount": 5000,
  "totalQuantity": 1500,
  "issueStartAt": "2026-11-01T10:00:00",
  "issueEndAt": "2026-11-14T23:59:59",
  "validDays": 7
}
```

- 발급 시작 후에는 name, issueEndAt, totalQuantity만 바꿀 수 있다.
- issueEndAt: 현재 이후, issueStartAt 이후
- totalQuantity: 이미 발급한 수량 이상

응답 `data`: 쿠폰 생성 응답과 같다. issueStatus는 UPCOMING / ISSUING / ENDED.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | VALIDATION_ERROR | 길이·범위 위반, 발급 기간 순서가 틀림 |
| 401 | UNAUTHORIZED | 토큰이 없거나 유효하지 않음 |
| 403 | FORBIDDEN | MASTER 역할이 아님 |
| 404 | COUPON_NOT_FOUND | 쿠폰이 없음 |
| 409 | COUPON_NOT_MODIFIABLE | 발급 시작 후 바꿀 수 없는 필드를 보냄 |
| 409 | COUPON_QUANTITY_BELOW_ISSUED | totalQuantity가 이미 발급한 수량보다 적음 |

### POST /api/v1/coupons/{couponId}/close — 쿠폰 발급 중단

- 권한·허용 호출자: MASTER
- 필수 헤더: `Authorization: Bearer {accessToken}`
- 성공 HTTP: 200

발급 종료 시각(issueEndAt)을 현재 시각으로 당겨 발급을 멈춘다. 이미 발급된 쿠폰은 계속 쓸 수 있다.

응답 `data`: 쿠폰 생성 응답과 같다. issueStatus는 ENDED.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 401 | UNAUTHORIZED | 토큰이 없거나 유효하지 않음 |
| 403 | FORBIDDEN | MASTER 역할이 아님 |
| 404 | COUPON_NOT_FOUND | 쿠폰이 없음 |
| 409 | COUPON_ALREADY_CLOSED | 이미 발급이 끝난 쿠폰 |

### DELETE /api/v1/coupons/{couponId} — 쿠폰 삭제

- 권한·허용 호출자: MASTER
- 필수 헤더: `Authorization: Bearer {accessToken}`
- 성공 HTTP: 204 (응답 본문 없음)

잘못 만든 쿠폰을 지운다. 아직 한 장도 발급되지 않은 쿠폰(`issued_quantity = 0`)만 지울 수 있다. 발급 기간이 시작됐어도 받은 사람이 없으면 지울 수 있다. 행을 지우지 않고 `deleted_at`, `deleted_by`를 기록하는 논리 삭제이며, 삭제된 쿠폰은 모든 조회와 발급에서 빠진다(404 `COUPON_NOT_FOUND`).

한 명이라도 받은 쿠폰은 지울 수 없다. 지우면 `@SQLRestriction` 때문에 회원 쿠폰에서 쿠폰 이름을 찾지 못한다. 이런 쿠폰은 발급 중단으로 처리하고, 할인액 같은 조건이 틀렸다면 올바른 쿠폰을 새로 만든다. 이미 발급된 회원 쿠폰은 발급 시점의 할인액을 복사해 두었으므로 그대로 쓸 수 있다.

| 쿠폰 상태 | 잘못 만들었을 때 |
|---|---|
| 아무도 받지 않음 | 값만 틀렸으면 PATCH로 고친다. 필요 없는 쿠폰이면 삭제한다 |
| 누군가 받음 | 삭제할 수 없다. 발급 중단하고, 필요하면 올바른 쿠폰을 새로 만든다 |

삭제는 조건부 UPDATE 한 번으로 한다. 발급의 수량 UPDATE와 같은 행 잠금을 차례로 잡으므로, 삭제와 첫 발급이 동시에 들어와도 둘 중 하나만 성공한다.

```sql
UPDATE p_coupons SET deleted_at = now(), deleted_by = ?
WHERE coupon_id = ? AND issued_quantity = 0 AND deleted_at IS NULL
```

0행이면 이미 발급된 쿠폰이므로 409를 돌려준다. 쿠폰이 없거나 이미 삭제된 경우는 먼저 조회해 404로 구분한다.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 401 | UNAUTHORIZED | 토큰이 없거나 유효하지 않음 |
| 403 | FORBIDDEN | MASTER 역할이 아님 |
| 404 | COUPON_NOT_FOUND | 쿠폰이 없거나 이미 삭제됨 |
| 409 | COUPON_NOT_DELETABLE | 이미 한 장 이상 발급된 쿠폰 |

### GET /api/v1/coupons — 쿠폰 목록 조회

- 권한·허용 호출자: 로그인
- 필수 헤더: `Authorization: Bearer {accessToken}`
- 성공 HTTP: 200

발급 상태별로 쿠폰 목록을 조회한다. issueStatus는 발급 시작·종료 시각과 현재 시각을 비교해 계산한다. 수량이 다 떨어진 쿠폰도 ISSUING으로 나오고 remainingQuantity가 0이다. issuedByMe는 현재 사용자가 이 쿠폰을 이미 받았는지를 나타낸다.

요청: `?issueStatus=ISSUING&page=0&size=10&sort=createdAt,DESC`

| 파라미터 | 규칙 |
|---|---|
| issueStatus | UPCOMING / ISSUING / ENDED (선택, 기본 ISSUING) |
| page | 0 이상 (선택, 기본 0) |
| size | 10 / 30 / 50 (선택, 기본 10) |
| sort | createdAt / issueStartAt / issueEndAt + ,ASC / ,DESC (선택, 기본 createdAt,DESC) |

응답 `data`: `content`(쿠폰 상세 조회 항목 목록), `page`, `size`, `totalElements`, `totalPages`, `sort`

오류: 400 `VALIDATION_ERROR`(issueStatus가 허용 값이 아님, sort 필드가 허용 목록에 없음), 401 `UNAUTHORIZED`

### GET /api/v1/coupons/{couponId} — 쿠폰 상세 조회

- 권한·허용 호출자: 로그인
- 필수 헤더: `Authorization: Bearer {accessToken}`
- 성공 HTTP: 200

쿠폰 하나의 상세 정보를 조회한다.

응답 `data`: 쿠폰 생성 응답 필드에 `issuedByMe`(Boolean)가 더해진다. issueStatus는 UPCOMING / ISSUING / ENDED.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | VALIDATION_ERROR | couponId가 숫자가 아님 |
| 401 | UNAUTHORIZED | 토큰이 없거나 유효하지 않음 |
| 404 | COUPON_NOT_FOUND | 쿠폰이 없음 |

### POST /api/v1/coupons/{couponId}/issue — 쿠폰 선착순 발급

- 권한·허용 호출자: USER
- 필수 헤더: `Authorization: Bearer {accessToken}`
- 성공 HTTP: 201

쿠폰을 선착순으로 1장 발급한다. 수량은 조건부 UPDATE로 줄여 초과 발급을 막고, 같은 사용자의 중복 발급은 유니크 제약으로 막는다. expiresAt은 발급 시각에 validDays를 더한 값이다.

응답 `data` (메시지 "쿠폰이 발급되었습니다.")

| 필드 | 타입 |
|---|---|
| userCouponId | Long |
| couponId | Long |
| name | String |
| discountAmount | Integer |
| status | String (AVAILABLE) |
| issuedAt, expiresAt | String (yyyy-MM-ddTHH:mm:ss) |

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | COUPON_ISSUE_PERIOD_CLOSED | 발급 기간 전이거나 지남 |
| 401 | UNAUTHORIZED | 토큰이 없거나 유효하지 않음 |
| 403 | FORBIDDEN | USER 역할이 아님 |
| 404 | COUPON_NOT_FOUND | 쿠폰이 없음 |
| 409 | COUPON_SOLD_OUT | 수량이 다 떨어짐 |
| 409 | COUPON_ALREADY_ISSUED | 이미 받은 쿠폰 |

### GET /api/v1/coupons/me — 내 쿠폰 목록 조회

- 권한·허용 호출자: 로그인
- 필수 헤더: `Authorization: Bearer {accessToken}`
- 성공 HTTP: 200

내가 발급받은 쿠폰을 상태별로 조회한다. 유효 기간이 지났지만 만료 배치가 아직 처리하지 않은 쿠폰은 EXPIRED로 보여 주고 AVAILABLE 조회에서 뺀다. 예매 결제 준비(`POST /api/v1/reservations/{reservationId}/payments/checkout`)에 넣는 값은 이 응답의 userCouponId다.

요청: `?status=AVAILABLE&page=0&size=10&sort=createdAt,DESC`

| 파라미터 | 규칙 |
|---|---|
| status | AVAILABLE / RESERVED / USED / EXPIRED (선택, 없으면 전부) |
| page | 0 이상 (선택, 기본 0) |
| size | 10 / 30 / 50 (선택, 기본 10) |
| sort | createdAt / expiresAt + ,ASC / ,DESC (선택, 기본 createdAt,DESC) |

응답 `data.content[]`

| 필드 | 타입 |
|---|---|
| userCouponId | Long |
| couponId | Long |
| name | String |
| discountAmount | Integer |
| status | String (AVAILABLE / RESERVED / USED / EXPIRED) |
| issuedAt, expiresAt | String (yyyy-MM-ddTHH:mm:ss) |
| usedAt | String 또는 null |

페이지 필드: `page`, `size`, `totalElements`, `totalPages`, `sort`

오류: 400 `VALIDATION_ERROR`(status가 허용 값이 아님, sort 필드가 허용 목록에 없음), 401 `UNAUTHORIZED`
