# 공연 API 명세

- 상태: 작성 중
- 담당:
- 출처: Notion "도메인별 API 명세"의 공연 항목 (2026-10-26 기준). 담당자가 검토하며 고친다.

Notion 원본의 경로(`/api/...`)와 오류 형식을 그대로 옮겼다. 프로젝트 규칙(`/api/v1/**`, `ApiResponse`)과 맞추는 일은 담당자 검토에서 정한다.

## 1. 공통 계약

설계 근거·ERD·테이블은 Notion 공연 설계 문서와 [tables.md](tables.md)에 있다.

공연 서비스 소유 API만 이 문서에 둔다. 예매 서비스 소유 회차 좌석 API는 아래 "예매 서비스 소유 연관 API"에 경로만 남기며 공연 소유 API로 분류하지 않는다.

### 1.1 API 공통 계약

아래 경로·DTO·응답 코드는 구현 제안이다. 좌석 위치·등급은 공연장에 고정한다. 가격은 공연별 등급 가격이며 동일 공연의 모든 회차가 공유한다. 선점·판매 상태만 예매 서비스에서 회차별로 관리한다. API도 이 책임 단위와 공개 정책을 따른다. schedule이라는 기존 명칭을 일관되게 사용한다.

- 기본 경로는 `/api`다. JSON은 camelCase다. ID는 BIGINT의 브라우저 정밀도 손실을 막기 위해 JSON에서 문자열로 전달한다.
- 일반 조회는 공개·미삭제 데이터만 반환하며 초안 ID 직접 조회는 404다. 취소된 공개 공연은 취소 안내를 위해 상세 조회할 수 있다.
- SELLER는 본인 소유 리소스만 변경한다. ADMIN은 공연장 관리·모든 공연 조회·회차 취소를 수행한다. 관리자 예외 수정은 별도 운영 절차이며 공개 후 불변 규칙을 우회하지 않는다.
- 인증·권한은 서버가 검증한다. sellerId를 요청 본문으로 받지 않는다. 내부 API는 서비스 인증이 필요하다.
- 수정·공개·삭제·판매 중지에는 `If-Match: "현재 version"` 헤더를 사용한다. 미전달은 428, 불일치는 409 `VERSION_CONFLICT`다. 자식 변경에는 부모 performance의 version을 사용한다. 관리 명령에는 대상 schedule의 version도 본문 `scheduleVersion`으로 전달한다.
- 목록은 page가 0부터, size가 기본 20·최대 100이다. 응답은 items, page, size, totalElements다. 필터 날짜는 KST 공연 시작일 기준이다.
- 생성 응답은 201 + Location, 단순 수정은 200, 삭제는 204, 비동기 접수는 202다. 좌석 생성 완료를 201/202로 보장하지 않는다.
- POST 공연·회차 생성과 좌석 재시도·취소는 `Idempotency-Key`를 권장하는 계약안이다. 같은 키·같은 요청은 기존 결과, 같은 키·다른 요청은 409다. 키 보존 기간·범위는 협의한다.
- 알 수 없는 수정 필드 및 공개 후 금지 필드는 조용히 무시하지 않고 400/409로 거절한다.

오류 응답 예시

```json
{
  "code": "SEAT_SETUP_NOT_READY",
  "message": "회차 좌석 준비가 완료되지 않았습니다.",
  "traceId": "trace-123",
  "fieldErrors": []
}
```

### 1.2 주요 오류 코드

| HTTP | 코드 | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` / `INVALID_TIME_RANGE` | 필드·열거값·시각 조건 오류 |
| 401 / 403 | `UNAUTHENTICATED` / `FORBIDDEN` | 인증 실패·역할/소유권 없음 |
| 404 | `PERFORMANCE_NOT_FOUND` / `SCHEDULE_NOT_FOUND` / `VENUE_NOT_FOUND` | 미존재·삭제·일반 조회에서 비공개 |
| 409 | `VERSION_CONFLICT` | 동시 수정 충돌 |
| 409 | `PUBLICATION_REQUIREMENTS_NOT_MET` | 공개 필수 정보 누락 |
| 409 | `PERFORMANCE_CONFIGURATION_LOCKED` | 공개 후 금지된 구성 변경 |
| 409 | `VENUE_LAYOUT_IN_USE` / `DUPLICATE_SCHEDULE` / `DUPLICATE_VENUE_SEAT` | 배치 사용 중 수정 금지·중복 제약 위반 |
| 409 | `DELETE_NOT_ALLOWED` / `RESERVATION_EXISTS` | 삭제 정책 불충족 |
| 409 | `SEAT_CONFIG_VERSION_MISMATCH` | 좌석 구성 버전 불일치 |
| 428 | `PRECONDITION_REQUIRED` | If-Match 누락 |
| 503 | `DEPENDENCY_UNAVAILABLE` | 예매 서비스 확인 불가. 삭제·판매 허용을 추정하지 않음 |
| 400 | `VENUE_GRADE_MISMATCH` / `GRADE_PRICE_MISSING` | 다른 공연장 등급 참조 또는 판매 대상 등급 가격 누락 |
| 409 | `DUPLICATE_VENUE_GRADE` | 공연장 내 등급명 중복 |

### 1.3 DTO 공통 규칙

각 API의 Request DTO / Response DTO 표가 필드 계약이다. 공통 계약 페이지의 상세 예시는 설명용 축약 예시이며, 필수 필드·중첩 구조·null 허용 여부는 각 API의 DTO 표를 기준으로 한다. 구체화한 DTO는 구현 전 계약 초안이며 기존 고정 등급·공연별 가격 정책은 유지한다.

- ID: JSON string이다. BIGINT를 숫자로 직렬화하지 않는다.
- integer: 버전·수량·페이지·원화 금액이다. 금액은 0 이상이며 가격 미입력과 0원을 구분한다.
- datetime: ISO 8601 문자열이다. 예: `2026-12-01T19:00:00+09:00`.
- date: `YYYY-MM-DD`, KST 날짜 기준이다.
- 필수 여부: 필드가 존재해야 하는지다. nullable 여부는 타입의 `|null`로 별도 표시한다.
- PATCH: 생략은 유지다. 명시적 null은 nullable 필드만 제거할 수 있다. 최소 하나의 수정 필드가 필요하며 공개 필수 조건을 깨는 변경은 거절한다.
- 목록 응답: items, page, size, totalElements다. 빈 목록은 `items=[]`이며 배열을 null로 반환하지 않는다.
- 성공 응답: 공통 data 래퍼 없이 개별 DTO를 직접 반환하는 초안이다.
- 204: 응답 본문이 없다. `{}` 또는 null JSON을 반환하지 않는다.
- DELETE 회차: 기존 명세에 맞춰 JSON 본문에 scheduleVersion을 전달한다. If-Match에는 부모 공연 버전을 전달한다.
- If-Match: 공연장 API는 venue.version, 공연·회차 API는 부모 performance.version을 큰따옴표로 감싸 전달한다. 회차 변경 명령은 scheduleVersion도 전달한다.
- 재시도: 요청 본문이 없다. 최신 configVersion과 requestId는 서버가 관리한다.
- 관리 목록 필터: 판매자 목록의 status는 DRAFT/PUBLISHED 공개 상태 필터다. 관리자 목록은 publicationStatus와 operationStatus를 분리한다.
- 공개/관리 응답: 공개 응답에는 sellerId·좌석 준비 내부 오류·판매 제외 내부 사유를 넣지 않는다.
- 등급/가격: venueSeatGradeId는 공연장 고정 등급이다. gradePrices는 공연 공통 가격이며 회차마다 재정의하지 않는다.
- 판매 제외: 정적 배치의 basePrice는 제외된 등급에 가격이 없으면 null일 수 있다. 실제 판매 대상 가격과 검증 성공 응답 가격은 정수 필수다.
- Content-Type: `application/json`은 JSON 요청 본문이 있을 때 사용한다. 본문이 없는 요청에 빈 JSON 객체를 필수로 보내지 않는다.

### 1.4 공통 오류 Response DTO

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| code | string | 필수 | 업무 오류 코드. |
| message | string | 필수 | 사용자에게 전달할 안전한 메시지. |
| traceId | string | 필수 | 로그 추적 ID. |
| fieldErrors | object[] | 필수 | 필드 오류가 없으면 빈 배열. |
| fieldErrors[].field | string | 필수 | 문제 필드 경로. 예: `gradePrices[0].price`. |
| fieldErrors[].message | string | 필수 | 검증 실패 사유. |

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "traceId": "trace-123",
  "fieldErrors": [
    {"field": "요청 필드 경로", "message": "검증 실패 사유"}
  ]
}
```

### 1.5 모든 API에 공통인 처리·검증 규칙

Notion의 API별 페이지마다 반복된 규칙이다. 아래 각 API의 처리·검증 규칙에는 이 규칙을 다시 적지 않는다.

- 알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
- 인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
- If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.
- 생성 201 응답은 Location 헤더를 포함한다.
- 취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

### 1.6 예매 서비스 소유 연관 API

아래 API는 구현·데이터 변경 주체가 예매 서비스다. 공연 서비스는 좌석 원본·고정 등급·공연별 가격과 회차 판매 정책을 제공한다. 상세 계약은 좌석·예매 도메인 문서를 본다.

| Method | URI | 권한 | 역할 |
|---|---|---|---|
| GET | `/api/schedules/{id}/seats` | 대기열 입장 회원 제안 | 회차별 선택 가능 좌석 조회 |
| GET | `/api/schedules/{id}/remaining` | 누구나 | 공연장 등급별 잔여석 |
| POST | `/api/schedules/{id}/holds` | 입장한 본인 회원 | 예매 생성·좌석 선점. 등급·가격 입력 불가 |
| DELETE | `/api/reservations/{id}/hold` | 본인 회원 | 본인 선점 해제 |

그 밖에 좌석 준비 이벤트 소비(공연 → 예매 내부 계약), 예매 확정 내부 처리, 선점 만료 내부 배치도 예매 서비스가 맡는다.

## 2. 외부 API

| Method | URI | 권한·허용 호출자 | 성공 HTTP | 기능 |
|---|---|---|---|---|
| POST | `/api/admin/venues` | ADMIN | 201 | 공연장 등록 |
| POST | `/api/admin/venues/{venueId}/seat-grades` | ADMIN | 201 | 공연장 좌석 등급 등록 |
| GET | `/api/venues/{venueId}/seat-grades` | SELLER / ADMIN | 200 | 공연장 좌석 등급 조회 |
| PATCH | `/api/admin/venues/{venueId}/seat-grades/{gradeId}` | ADMIN | 200 | 공연장 좌석 등급명 수정 |
| POST | `/api/admin/venues/{venueId}/seats` | ADMIN | 201 | 공연장 좌석 등록 |
| GET | `/api/venues/{venueId}/seats` | SELLER / ADMIN | 200 | 공연장 좌석 조회 |
| PATCH | `/api/admin/venues/{venueId}/seats/{seatId}` | ADMIN | 200 | 공연장 좌석 정보 수정 |
| GET | `/api/venues` | SELLER / ADMIN | 200 | 공연장 목록 조회 |
| POST | `/api/seller/performances` | SELLER | 201 | 공연 초안 등록 |
| GET | `/api/seller/performances` | SELLER | 200 | 내 공연 목록 조회 |
| GET | `/api/seller/performances/{id}` | 소유 SELLER | 200 | 내 공연 상세 조회 |
| PATCH | `/api/seller/performances/{id}` | 소유 SELLER | 200 | 공연 정보 수정 |
| PUT | `/api/seller/performances/{id}/grade-prices` | 소유 SELLER | 200 | 공연별 등급 가격 설정 |
| PUT | `/api/seller/performances/{id}/seat-exclusions` | 소유 SELLER | 200 | 공연 공통 판매 제외 설정 |
| PUT | `/api/seller/performances/{id}/publication` | 소유 SELLER | 200 | 공연 공개 |
| DELETE | `/api/seller/performances/{id}` | 소유 SELLER | 204 | 공연 삭제 |
| POST | `/api/seller/performances/{id}/schedules` | 소유 SELLER | 201 | 공연 회차 등록 |
| PATCH | `/api/seller/schedules/{id}` | 소유 SELLER | 200 | 공연 회차 수정 |
| DELETE | `/api/seller/schedules/{id}` | 소유 SELLER | 204 | 공연 회차 삭제 |
| POST | `/api/seller/schedules/{id}/seat-setup/retry` | 소유 SELLER | 202 | 회차 좌석 준비 재시도 |
| GET | `/api/admin/performances` | ADMIN | 200 | 전체 공연 목록 조회 |
| GET | `/api/admin/performances/{id}` | ADMIN | 200 | 관리자 공연 상세 조회 |
| POST | `/api/admin/schedules/{id}/cancellation` | ADMIN | 202 | 공연 회차 취소 |
| PUT | `/api/admin/schedules/{id}/sales-suspension` | ADMIN | 200 | 회차 판매 일시 중지·재개 |
| GET | `/api/performances` | 누구나 | 200 | 공개 공연 목록 조회 |
| GET | `/api/performances/{id}` | 누구나 | 200 | 공개 공연 상세 조회 |
| GET | `/api/performances/{id}/schedules` | 누구나 | 200 | 공연 회차 목록 조회 |
| GET | `/api/performances/{id}/seat-layout` | 누구나 | 200 | 공연 좌석 배치 조회 |
| GET | `/api/schedules/{id}` | 누구나 | 200 | 공연 회차 상세 조회 |

### 공연장

등록 순서는 공연장 → 공연장 좌석 등급 → 등급이 지정된 물리 좌석이다. 공연장 등급은 ADMIN만 생성·관리한다. 등급 가격은 이 단계에서 받지 않는다. 구역별 일괄 입력은 UI 또는 업로드가 개별 좌석 입력으로 변환하며 등급 원본은 공연장 한 곳이다.

### POST /api/admin/venues — 공연장 등록

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization
- 성공 HTTP: 201
- Notion 상태: 시작 전

Request DTO

Path·Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| name | string | 필수 | 공연장명. 1~100자. |
| address | string | 필수 | 주소. 1~255자. |
| region | string | 필수 | 지역. 1~20자. |

```json
{
  "name": "서울 공연장",
  "address": "서울특별시 중구 예시로 10",
  "region": "서울"
}
```

Response DTO (HTTP 201)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연장 ID. BIGINT를 문자열로 표현. |
| name | string | 필수 | 공연장명. 1~100자. |
| address | string | 필수 | 주소. 1~255자. |
| region | string | 필수 | 지역. 1~20자. |
| version | integer | 필수 | 공연장 수정 버전. 0 이상. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |

```json
{
  "id": "10",
  "name": "서울 공연장",
  "address": "서울특별시 중구 예시로 10",
  "region": "서울",
  "version": 0,
  "createdAt": "2026-10-26T10:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00"
}
```

처리·검증 규칙

이름·주소·지역은 필수다.

### POST /api/admin/venues/{venueId}/seat-grades — 공연장 좌석 등급 등록

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization, If-Match (현재 venue.version)
- 성공 HTTP: 201
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| grades | GradeInput[] | 필수 | 1개 이상. 공연장 내 동일 이름 중복 불가. |
| grades[].name | string | 필수 | 등급명. 1~20자, 공백 불가. |

```json
{
  "grades": [
    {
      "name": "VIP"
    }
  ]
}
```

Response DTO (HTTP 201)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| grades | Grade[] | 필수 | 공연장 고정 등급 목록. |
| grades[].id | string | 필수 | 공연장 고정 등급 ID. |
| grades[].name | string | 필수 | 공연장 내 유일한 등급명. 1~20자. |
| venueVersion | integer | 필수 | 공연장 최신 버전. |

```json
{
  "grades": [
    {
      "id": "101",
      "name": "VIP"
    }
  ],
  "venueVersion": 1
}
```

처리·검증 규칙

가격 입력은 없다. 공연에서 사용하기 전에만 등록할 수 있다.

동일 공연장 내 이름 중복은 409 `DUPLICATE_VENUE_GRADE`다. 공연이 해당 배치를 참조한 이후에는 409 `VENUE_LAYOUT_IN_USE`로 등급 추가를 차단한다.

### GET /api/venues/{venueId}/seat-grades — 공연장 좌석 등급 조회

- 권한·허용 호출자: SELLER / ADMIN
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| grades | Grade[] | 필수 | 공연장 고정 등급 목록. |
| grades[].id | string | 필수 | 공연장 고정 등급 ID. |
| grades[].name | string | 필수 | 공연장 내 유일한 등급명. 1~20자. |
| venueVersion | integer | 필수 | 공연장 최신 버전. |

```json
{
  "grades": [
    {
      "id": "101",
      "name": "VIP"
    }
  ],
  "venueVersion": 1
}
```

처리·검증 규칙

공연장 고정 등급 목록이다. 공연별 가격은 포함하지 않는다.

### PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId} — 공연장 좌석 등급명 수정

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization, If-Match (현재 venue.version)
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |
| gradeId | string | 필수 | 공연장 좌석 등급 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| name | string | 필수 | 새 등급명. 1~20자, 공연장 내 유일. |

```json
{
  "name": "VIP"
}
```

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| grade | Grade | 필수 | 수정된 등급. |
| grade.id | string | 필수 | 공연장 고정 등급 ID. |
| grade.name | string | 필수 | 공연장 내 유일한 등급명. 1~20자. |
| venueVersion | integer | 필수 | 최신 공연장 버전. |

```json
{
  "grade": {
    "id": "101",
    "name": "VIP"
  },
  "venueVersion": 2
}
```

처리·검증 규칙

공연에서 사용하기 전에만 변경할 수 있다. 가격이나 좌석 배정 변경 요청은 받지 않는다.

초기 등록 단계의 오류 수정용이다. 공연이 해당 배치를 참조한 이후에는 409 `VENUE_LAYOUT_IN_USE`로 차단한다. 다른 공연의 VIP 가격을 바꾸기 위해 공연장 등급을 수정해서는 안 된다.

### POST /api/admin/venues/{venueId}/seats — 공연장 좌석 등록

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization, If-Match (현재 venue.version)
- 성공 HTTP: 201
- Notion 상태: 검토 중

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| seats | SeatInput[] | 필수 | 1개 이상. 동일 구역·열·번호 중복 불가. |
| seats[].section | string | 필수 | 구역. 1~20자. |
| seats[].rowLabel | string | 필수 | 열. 1~10자. |
| seats[].seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seats[].venueSeatGradeId | string | 필수 | 동일 공연장의 고정 등급 ID. |

```json
{
  "seats": [
    {
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": "101"
    }
  ]
}
```

Response DTO (HTTP 201)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| createdCount | integer | 필수 | 생성한 좌석 수. |
| venueVersion | integer | 필수 | 최신 공연장 버전. |

```json
{
  "createdCount": 1,
  "venueVersion": 2
}
```

처리·검증 규칙

모든 등급은 같은 공연장 소속이어야 한다. 다른 공연장 등급이면 400 `VENUE_GRADE_MISMATCH`다. 공연이 참조한 배치에는 추가할 수 없다(409 `VENUE_LAYOUT_IN_USE`).

중복 좌석이면 409 `DUPLICATE_VENUE_SEAT`이며 전체 롤백한다.

### GET /api/venues/{venueId}/seats — 공연장 좌석 조회

- 권한·허용 호출자: SELLER / ADMIN
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |
| venueVersion | integer | 필수 | 공연장 버전. |
| seats | Seat[] | 필수 | 물리 좌석과 고정 등급. 판매 상태 없음. |
| seats[].id | string | 필수 | 물리 좌석 ID. |
| seats[].section | string | 필수 | 구역. 1~20자. |
| seats[].rowLabel | string | 필수 | 열. 1~10자. |
| seats[].seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seats[].venueSeatGradeId | string | 필수 | 동일 공연장의 고정 등급 ID. |
| seats[].gradeName | string | 필수 | 고정 등급명. |

```json
{
  "venueId": "10",
  "venueVersion": 2,
  "seats": [
    {
      "id": "1001",
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": "101",
      "gradeName": "VIP"
    }
  ]
}
```

처리·검증 규칙

선점·판매 상태는 포함하지 않는다.

### PATCH /api/admin/venues/{venueId}/seats/{seatId} — 공연장 좌석 정보 수정

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization, If-Match (현재 venue.version)
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 공연장 ID. |
| seatId | string | 필수 | 공연장 물리 좌석 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| section | string | 선택 | 구역. 1~20자. |
| rowLabel | string | 선택 | 열. 1~10자. |
| seatNumber | integer | 선택 | 좌석 번호. 1 이상. |
| venueSeatGradeId | string | 선택 | 동일 공연장의 고정 등급 ID. |

```json
{
  "section": "A",
  "rowLabel": "1",
  "seatNumber": 1,
  "venueSeatGradeId": "101"
}
```

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| seat | Seat | 필수 | 수정한 물리 좌석. |
| seat.id | string | 필수 | 물리 좌석 ID. |
| seat.section | string | 필수 | 구역. 1~20자. |
| seat.rowLabel | string | 필수 | 열. 1~10자. |
| seat.seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seat.venueSeatGradeId | string | 필수 | 동일 공연장의 고정 등급 ID. |
| seat.gradeName | string | 필수 | 고정 등급명. |
| venueVersion | integer | 필수 | 최신 공연장 버전. |

```json
{
  "seat": {
    "id": "1001",
    "section": "A",
    "rowLabel": "1",
    "seatNumber": 1,
    "venueSeatGradeId": "101",
    "gradeName": "VIP"
  },
  "venueVersion": 3
}
```

처리·검증 규칙

최소 1개 필드가 필요하다. 공연에서 사용하기 전에만 수정할 수 있다. 같은 공연장 등급만 지정할 수 있다.

초기 등록 단계의 오류 수정용이다. 공연이 해당 배치를 참조한 이후에는 409 `VENUE_LAYOUT_IN_USE`로 좌석 변경을 차단한다.

### GET /api/venues — 공연장 목록 조회

- 권한·허용 호출자: SELLER / ADMIN
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path 파라미터 없음. 요청 본문 없음.

Query

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| region | string | 선택 | 지역. 생략 시 전체. |
| keyword | string | 선택 | 검색어. 생략 시 필터 없음. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시: `/api/venues?region=%EC%84%9C%EC%9A%B8&keyword=%EA%B3%B5%EC%97%B0&page=0&size=20`

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| items | Venue[] | 필수 | 현재 페이지 공연장. |
| items[].id | string | 필수 | 공연장 ID. BIGINT를 문자열로 표현. |
| items[].name | string | 필수 | 공연장명. 1~100자. |
| items[].address | string | 필수 | 주소. 1~255자. |
| items[].region | string | 필수 | 지역. 1~20자. |
| items[].version | integer | 필수 | 공연장 수정 버전. 0 이상. |
| items[].createdAt | datetime | 필수 | 생성 시각. |
| items[].updatedAt | datetime | 필수 | 최종 변경 시각. |
| page | integer | 필수 | 0부터 시작. |
| size | integer | 필수 | 요청 페이지 크기. 1~100. |
| totalElements | integer | 필수 | 필터에 맞는 전체 건수. |

```json
{
  "items": [
    {
      "id": "10",
      "name": "서울 공연장",
      "address": "서울특별시 중구 예시로 10",
      "region": "서울",
      "version": 0,
      "createdAt": "2026-10-26T10:00:00+09:00",
      "updatedAt": "2026-10-26T10:00:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

처리·검증 규칙

공연 등록 시 공연장 선택용 조회다.

### 공연 (판매자)

공연 생성·수정·조회 DTO의 공통 규칙이다. venueId, title, genre, ageRating, runningTime은 생성 필수다. 나머지는 DRAFT에서 생략할 수 있으며 공개 필수 조건은 별도로 검증한다. PATCH는 전달 필드만 변경한다. 공개 후에는 description, posterUrl, castInfo만 허용한다.

Performance 응답의 기간(period)은 활성 회차가 없으면 null이다. 관리 상세에는 gradePrices, excludedSeats, schedules를 포함하고, public 응답에는 내부 오류·requestId·판매자 개인정보를 넣지 않는다.

### POST /api/seller/performances — 공연 초안 등록

- 권한·허용 호출자: SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 201
- Notion 상태: 검토 중

Request DTO

Path·Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 필수 | 미삭제 공연장 ID. |
| title | string | 필수 | 공연명. 1~200자. |
| genre | string | 필수 | CONCERT / MUSICAL / PLAY / SPORTS. |
| ageRating | string | 필수 | ALL / 12 / 15 / 19. |
| runningTime | integer | 필수 | 공연 시간(분). 1 이상. |
| castInfo | string\|null | 선택 | 최대 500자. 생략하면 null. |
| description | string\|null | 선택 | 초안에서는 생략 가능. 공개 조건은 별도 검증. |
| posterUrl | string\|null | 선택 | 최대 500자 HTTPS URL. 초안에서는 생략 가능. |

```json
{
  "venueId": "10",
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg"
}
```

Response DTO (HTTP 201) — Performance

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | string | 필수 | 공연장 ID. BIGINT를 문자열로 표현. |
| venue.name | string | 필수 | 공연장명. 1~100자. |
| venue.address | string | 필수 | 주소. 1~255자. |
| venue.region | string | 필수 | 지역. 1~20자. |
| title | string | 필수 | 공연명. 1~200자. |
| genre | string | 필수 | CONCERT / MUSICAL / PLAY / SPORTS. |
| ageRating | string | 필수 | ALL / 12 / 15 / 19. |
| runningTime | integer | 필수 | 공연 시간(분). 1 이상. |
| castInfo | string\|null | 필수 | 출연진. 최대 500자. 미등록이면 null. |
| description | string\|null | 필수 | 상세 설명. 초안 미등록 시 null. |
| posterUrl | string\|null | 필수 | HTTPS 이미지 URL. 최대 500자. 초안 미등록 시 null. |
| publicationStatus | string | 필수 | DRAFT / PUBLISHED. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| publishedAt | datetime\|null | 필수 | 최초 공개 시각. 초안이면 null. |
| period | Period\|null | 필수 | 활성 회차 집계 기간. 회차가 없으면 null. |
| period.from | date | 필수 | 활성 회차의 첫 공연 날짜. |
| period.to | date | 필수 | 활성 회차의 마지막 공연 날짜. |
| version | integer | 필수 | 공연 및 자식 구성 변경 버전. If-Match 기준. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| sellerId | string | 필수 | 인증 주체의 판매자 ID. 클라이언트 입력 금지. |

```json
{
  "id": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg",
  "publicationStatus": "DRAFT",
  "operationStatus": "ACTIVE",
  "publishedAt": null,
  "period": null,
  "version": 0,
  "createdAt": "2026-10-26T09:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00",
  "sellerId": "seller-uuid"
}
```

처리·검증 규칙

sellerId는 인증 주체에서 설정한다. 공연장은 좌석·등급 등록이 끝난 곳을 선택한다.

공통 계약은 공연 생성 POST에 `Idempotency-Key`를 권장한다.

### GET /api/seller/performances — 내 공연 목록 조회

- 권한·허용 호출자: SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path 파라미터 없음. 요청 본문 없음.

Query

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| status | string | 선택 | 공개 상태 필터 DRAFT / PUBLISHED. 생략 시 전체. |
| keyword | string | 선택 | 검색어. 생략 시 필터 없음. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시: `/api/seller/performances?status=DRAFT&keyword=%EA%B3%B5%EC%97%B0&page=0&size=20`

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| items | PerformanceManageSummary[] | 필수 | 관리용 공연 목록. |
| items[].id | string | 필수 | 공연 상품 ID. |
| items[].venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| items[].venue.id | string | 필수 | 공연장 ID. BIGINT를 문자열로 표현. |
| items[].venue.name | string | 필수 | 공연장명. 1~100자. |
| items[].venue.address | string | 필수 | 주소. 1~255자. |
| items[].venue.region | string | 필수 | 지역. 1~20자. |
| items[].title | string | 필수 | 공연명. 1~200자. |
| items[].genre | string | 필수 | CONCERT / MUSICAL / PLAY / SPORTS. |
| items[].posterUrl | string\|null | 필수 | HTTPS 이미지 URL. 최대 500자. 초안 미등록 시 null. |
| items[].publicationStatus | string | 필수 | DRAFT / PUBLISHED. |
| items[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| items[].period | Period\|null | 필수 | 활성 회차 집계 기간. 회차가 없으면 null. |
| items[].period.from | date | 필수 | 활성 회차의 첫 공연 날짜. |
| items[].period.to | date | 필수 | 활성 회차의 마지막 공연 날짜. |
| items[].version | integer | 필수 | 공연 및 자식 구성 변경 버전. If-Match 기준. |
| items[].sellerId | string | 필수 | 판매자 ID. |
| page | integer | 필수 | 0부터 시작. |
| size | integer | 필수 | 1~100. |
| totalElements | integer | 필수 | 전체 건수. |

```json
{
  "items": [
    {
      "id": "20",
      "venue": {
        "id": "10",
        "name": "서울 공연장",
        "address": "서울특별시 중구 예시로 10",
        "region": "서울"
      },
      "title": "공연 A 서울",
      "genre": "MUSICAL",
      "posterUrl": "https://example.com/poster.jpg",
      "publicationStatus": "PUBLISHED",
      "operationStatus": "ACTIVE",
      "period": {
        "from": "2026-12-01",
        "to": "2026-12-02"
      },
      "version": 3,
      "sellerId": "seller-uuid"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

처리·검증 규칙

인증된 판매자 본인 공연만 조회한다.

### GET /api/seller/performances/{id} — 내 공연 상세 조회

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

Performance 필드(공연 초안 등록 응답 표의 id ~ sellerId) 전체에 아래 필드를 더한다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| gradePrices | GradePrice[] | 필수 | 공연별 등급 가격. |
| gradePrices[].venueSeatGradeId | string | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 이상. |
| excludedSeats | ExcludedSeat[] | 필수 | 공연 공통 판매 제외 좌석. |
| excludedSeats[].venueSeatId | string | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |
| schedules | ScheduleManage[] | 필수 | 회차별 관리 정보. |
| schedules[].id | string | 필수 | 회차 ID. |
| schedules[].performanceId | string | 필수 | 공연 ID. |
| schedules[].startAt | datetime | 필수 | 회차 시작 시각. |
| schedules[].bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| schedules[].bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| schedules[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| schedules[].seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| schedules[].salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| schedules[].salesStatus | string | 필수 | CANCELED / SUSPENDED / PREPARING / SETUP_FAILED / BEFORE_OPEN / CLOSED / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| schedules[].canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| schedules[].seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| schedules[].seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| schedules[].seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| schedules[].suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| schedules[].cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| schedules[].version | integer | 필수 | 회차 버전. scheduleVersion 입력 기준. |
| schedules[].performanceVersion | integer | 필수 | 부모 공연 최신 버전. If-Match 입력 기준. |

```json
{
  "id": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg",
  "publicationStatus": "PUBLISHED",
  "operationStatus": "ACTIVE",
  "publishedAt": "2026-10-26T10:00:00+09:00",
  "period": {
    "from": "2026-12-01",
    "to": "2026-12-02"
  },
  "version": 3,
  "createdAt": "2026-10-26T09:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00",
  "sellerId": "seller-uuid",
  "gradePrices": [
    {
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "price": 150000
    }
  ],
  "excludedSeats": [
    {
      "venueSeatId": "1002",
      "reason": "운영석"
    }
  ],
  "schedules": [
    {
      "id": "201",
      "performanceId": "20",
      "startAt": "2026-12-01T19:00:00+09:00",
      "bookingOpenAt": "2026-11-01T14:00:00+09:00",
      "bookingCloseAt": "2026-12-01T18:00:00+09:00",
      "operationStatus": "ACTIVE",
      "seatSetupStatus": "READY",
      "salesSuspended": false,
      "salesStatus": "BEFORE_OPEN",
      "canceledAt": null,
      "seatConfigVersion": 1,
      "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
      "seatSetupError": null,
      "suspensionReason": null,
      "cancelReason": null,
      "version": 0,
      "performanceVersion": 3
    }
  ]
}
```

처리·검증 규칙

판매자 본인 데이터만 조회하며 초안·관리 상태를 포함한다.

### PATCH /api/seller/performances/{id} — 공연 정보 수정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (현재 performance.version)
- 성공 HTTP: 200
- Notion 상태: 검토 중

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| venueId | string | 선택 | 미삭제 공연장 ID. DRAFT에서 변경 가능. |
| title | string | 선택 | 공연명. 1~200자. DRAFT에서 변경 가능. |
| genre | string | 선택 | CONCERT / MUSICAL / PLAY / SPORTS. DRAFT에서 변경 가능. |
| ageRating | string | 선택 | ALL / 12 / 15 / 19. DRAFT에서 변경 가능. |
| runningTime | integer | 선택 | 공연 시간(분). 1 이상. DRAFT에서 변경 가능. |
| castInfo | string\|null | 선택 | 최대 500자. 생략하면 null. DRAFT에서 변경 가능. |
| description | string\|null | 선택 | 초안에서는 생략 가능. 공개 조건은 별도 검증. DRAFT에서 변경 가능. |
| posterUrl | string\|null | 선택 | 최대 500자 HTTPS URL. 초안에서는 생략 가능. DRAFT에서 변경 가능. |

```json
{
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg"
}
```

Response DTO (HTTP 200)

공연 초안 등록 응답과 같은 Performance DTO다.

```json
{
  "id": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg",
  "publicationStatus": "PUBLISHED",
  "operationStatus": "ACTIVE",
  "publishedAt": "2026-10-26T10:00:00+09:00",
  "period": {
    "from": "2026-12-01",
    "to": "2026-12-02"
  },
  "version": 3,
  "createdAt": "2026-10-26T09:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00",
  "sellerId": "seller-uuid"
}
```

처리·검증 규칙

최소 1개 필드가 필요하다. 공개 후에는 description, posterUrl, castInfo만 허용한다. 필드 생략은 유지, nullable 필드의 명시적 null은 제거 의미다. 공개 필수 요건을 깨는 null 수정은 거절한다.

공개 후 금지 필드는 조용히 무시하지 않고 400/409로 거절한다.

### PUT /api/seller/performances/{id}/grade-prices — 공연별 등급 가격 설정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (현재 performance.version)
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| gradePrices | PriceInput[] | 필수 | 전체 가격 집합. 등급 ID 중복 불가. |
| gradePrices[].venueSeatGradeId | string | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 이상. |

```json
{
  "gradePrices": [
    {
      "venueSeatGradeId": "101",
      "price": 150000
    }
  ]
}
```

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| gradePrices | GradePrice[] | 필수 | 가격 집합. |
| gradePrices[].venueSeatGradeId | string | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 이상. |
| performanceVersion | integer | 필수 | 최신 부모 공연 버전. |

```json
{
  "gradePrices": [
    {
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "price": 150000
    }
  ],
  "performanceVersion": 4
}
```

처리·검증 규칙

DRAFT에서만 가능하다. 판매 대상 등급의 가격은 모두 필요하다. 회차별 가격·좌석별 가격은 받지 않는다.

공통 계약 5.4의 추가 규칙이다. 기존 `/grades` API를 대체하며 등급 생성·이름 변경 기능은 없다. 전체 공연의 가격 집합을 교체한다. 같은 venueSeatGradeId 중복은 거절한다. 해당 공연장에 속한 등급만 허용한다. 가격은 0 이상 정수다. 판매 대상 좌석에 사용되는 등급은 모두 가격이 있어야 하며 누락 시 400 `GRADE_PRICE_MISSING`이다. 모든 좌석이 제외된 등급에는 가격을 생략할 수 있으나, 이후 판매 대상으로 되돌릴 때는 가격이 필요하다. 가격 변경 시 해당 공연 회차들의 configVersion을 올리고 PENDING으로 재준비한다. 공개 후에는 409 `PERFORMANCE_CONFIGURATION_LOCKED`다.

### PUT /api/seller/performances/{id}/seat-exclusions — 공연 공통 판매 제외 설정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (현재 performance.version)
- 성공 HTTP: 200
- Notion 상태: 시작 전

공통 계약에서 구현 제안으로 표시한 기능이다. 회차별 판매 제외 대신 공연 공통 판매 제외 좌석 전체를 설정한다.

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| excludedSeats | ExcludedSeat[] | 필수 | 공연 공통 전체 제외 집합. 빈 배열은 제외 없음. |
| excludedSeats[].venueSeatId | string | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |

```json
{
  "excludedSeats": [
    {
      "venueSeatId": "1002",
      "reason": "운영석"
    }
  ]
}
```

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| excludedSeats | ExcludedSeat[] | 필수 | 제외 좌석 집합. 없으면 빈 배열. |
| excludedSeats[].venueSeatId | string | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |
| performanceVersion | integer | 필수 | 최신 공연 버전. |

```json
{
  "excludedSeats": [
    {
      "venueSeatId": "1002",
      "reason": "운영석"
    }
  ],
  "performanceVersion": 4
}
```

처리·검증 규칙

DRAFT에서만 변경한다. 모든 회차에 동일하게 적용한다. 등급·가격 덮어쓰기는 금지한다.

공통 계약 5.4의 추가 규칙이다. 좌석 ID 중복 및 다른 공연장 좌석을 거절한다. 판매 대상으로 남는 좌석 등급의 가격이 모두 있어야 한다. 변경하면 모든 회차를 최신 구성으로 재준비한다. 등급과 가격을 좌석별로 덮어쓰는 필드는 받지 않는다.

### PUT /api/seller/performances/{id}/publication — 공연 공개

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (현재 performance.version)
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| status | string | 필수 | PUBLISHED만 허용. DRAFT 복귀 불가. |

```json
{
  "status": "PUBLISHED"
}
```

요청 예시

```http
PUT /api/seller/performances/20/publication
If-Match: "3"
Content-Type: application/json
```

Response DTO (HTTP 200)

공연 초안 등록 응답과 같은 Performance DTO다.

```json
{
  "id": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg",
  "publicationStatus": "PUBLISHED",
  "operationStatus": "ACTIVE",
  "publishedAt": "2026-10-26T10:00:00+09:00",
  "period": {
    "from": "2026-12-01",
    "to": "2026-12-02"
  },
  "version": 3,
  "createdAt": "2026-10-26T09:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00",
  "sellerId": "seller-uuid"
}
```

처리·검증 규칙

필수 공연 정보·활성 회차·판매 대상 등급 가격을 검증한다. 좌석 준비 중이어도 공개할 수 있다.

공통 계약 5.6의 추가 규칙이다. 공개 검증 실패 시 409 `PUBLICATION_REQUIREMENTS_NOT_MET`와 누락 항목을 반환한다. 좌석 준비 PENDING/FAILED는 공개 실패 조건이 아니다. 같은 요청 반복은 이미 공개된 결과를 반환하되 최초 publishedAt을 보존한다. DRAFT 복귀 요청은 409 `PUBLICATION_TRANSITION_NOT_ALLOWED`다. 공개 트랜잭션은 필수 정보 및 자식 구성 동시 변경을 방지한다.

### DELETE /api/seller/performances/{id} — 공연 삭제

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (현재 performance.version)
- 성공 HTTP: 204
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 204)

응답 본문 없음. 204에서는 `{}` 또는 null JSON을 반환하지 않는다.

처리·검증 규칙

DRAFT·오픈 전·선점 및 예매 0건·예매 측 정리 가능을 확인한 뒤 소프트 삭제한다. 응답 본문은 없다.

삭제 정책을 충족하지 않으면 409 `DELETE_NOT_ALLOWED` / `RESERVATION_EXISTS`다. 예매 서비스를 확인할 수 없으면 503 `DEPENDENCY_UNAVAILABLE`이며 삭제 가능을 추정하지 않는다.

### 회차

회차 등록·수정에는 좌석 배정 입력이 없다. 서버가 공연의 공연장·고정 좌석 등급·공연별 등급 가격·공연 공통 제외 정책을 조회한다. seatAssignments, seatGradeId, venueSeatGradeId, 좌석별 가격 입력은 회차 API에서 허용하지 않는다. 회차마다 다른 등급 배정은 지원하지 않는다. 동일 공연의 모든 회차는 동일한 좌석 등급·가격·제외 정책을 사용하며 좌석별 선점·판매 상태만 회차별로 다르다.

아래 Schedule 응답 DTO는 회차 등록·수정이 같이 쓴다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 회차 ID. |
| performanceId | string | 필수 | 공연 ID. |
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| salesStatus | string | 필수 | CANCELED / SUSPENDED / PREPARING / SETUP_FAILED / BEFORE_OPEN / CLOSED / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| version | integer | 필수 | 회차 버전. scheduleVersion 입력 기준. |
| performanceVersion | integer | 필수 | 부모 공연 최신 버전. If-Match 입력 기준. |

### POST /api/seller/performances/{id}/schedules — 공연 회차 등록

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (부모 performance.version)
- 성공 HTTP: 201
- Notion 상태: 검토 중

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. (Notion 원문 그대로다. 경로상 공연 ID다.) |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |

```json
{
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00"
}
```

Response DTO (HTTP 201) — Schedule

```json
{
  "id": "201",
  "performanceId": "20",
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00",
  "operationStatus": "ACTIVE",
  "seatSetupStatus": "PENDING",
  "salesSuspended": false,
  "salesStatus": "PREPARING",
  "canceledAt": null,
  "seatConfigVersion": 1,
  "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
  "seatSetupError": null,
  "suspensionReason": null,
  "cancelReason": null,
  "version": 0,
  "performanceVersion": 3
}
```

처리·검증 규칙

DRAFT에서만 가능하다. 시각 순서는 오픈 < 마감 <= 시작이다. 등급·가격·좌석배정은 서버가 공연장 원본과 공연 공통 설정에서 가져온다.

회차 중복은 409 `DUPLICATE_SCHEDULE`이다. 공통 계약은 회차 생성 POST에 `Idempotency-Key`를 권장한다.

### PATCH /api/seller/schedules/{id} — 공연 회차 수정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (부모 performance.version)
- 성공 HTTP: 200
- Notion 상태: 검토 중

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| startAt | datetime | 선택 | 회차 시작 시각. |
| bookingOpenAt | datetime | 선택 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 선택 | 예매 마감 시각. |
| scheduleVersion | integer | 필수 | 현재 회차 version. 0 이상. |

```json
{
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00",
  "scheduleVersion": 0
}
```

Response DTO (HTTP 200) — Schedule

```json
{
  "id": "201",
  "performanceId": "20",
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00",
  "operationStatus": "ACTIVE",
  "seatSetupStatus": "PENDING",
  "salesSuspended": false,
  "salesStatus": "PREPARING",
  "canceledAt": null,
  "seatConfigVersion": 1,
  "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
  "seatSetupError": null,
  "suspensionReason": null,
  "cancelReason": null,
  "version": 0,
  "performanceVersion": 3
}
```

처리·검증 규칙

DRAFT에서만 가능하다. scheduleVersion 외에 시간 필드가 최소 1개 필요하다. If-Match는 공연 버전, scheduleVersion은 회차 버전이다.

공통 계약 5.5의 추가 규칙이다. 회차 수정은 시간 필드와 scheduleVersion만 받는다. 공연장·등급·가격은 부모 구성에서 가져온다. 시간 변경도 예매 측 스냅샷에 영향을 주면 재준비한다.

### DELETE /api/seller/schedules/{id} — 공연 회차 삭제

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization, If-Match (부모 performance.version)
- 성공 HTTP: 204
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scheduleVersion | integer | 필수 | 대상 회차 현재 version. 0 이상. |

```json
{
  "scheduleVersion": 0
}
```

Response DTO (HTTP 204)

응답 본문 없음. 204에서는 `{}` 또는 null JSON을 반환하지 않는다.

처리·검증 규칙

기존 명세에 따라 DELETE 요청 JSON 본문에 scheduleVersion을 전달한다. If-Match는 부모 공연 버전이다. 삭제 조건은 공연 삭제와 동일하며 응답 본문은 없다.

### POST /api/seller/schedules/{id}/seat-setup/retry — 회차 좌석 준비 재시도

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 202
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 202)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scheduleId | string | 필수 | 회차 ID. |
| seatSetupStatus | string | 필수 | PENDING. 기존 준비 중 요청도 PENDING 반환. |
| configVersion | integer | 필수 | 최신 좌석 구성 버전. |
| requestId | string | 필수 | 서버가 발급한 현재 준비 요청 ID. |

```json
{
  "scheduleId": "201",
  "seatSetupStatus": "PENDING",
  "configVersion": 1,
  "requestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46"
}
```

처리·검증 규칙

본문이 없다. 최신 구성과 새 requestId는 서버가 결정한다. FAILED는 재시도하고, PENDING은 기존 작업을 반환하고, READY는 재시도를 거절한다.

공통 계약 5.7의 추가 규칙이다. FAILED인 최신 구성을 동일 configVersion으로 다시 요청한다. 클라이언트가 등급·가격·configVersion·requestId를 임의로 지정하지 않는다. 새 requestId를 발급하고 PENDING으로 바꾸며 기존 오류를 비운다. PENDING에는 중복 작업을 만들지 않고 현재 작업을 반환한다. READY 재시도는 409 `SEAT_SETUP_ALREADY_READY`다. 공개된 회차도 구성 변경 없이 재시도할 수 있다. 공통 계약은 좌석 재시도에 `Idempotency-Key`를 권장한다.

### 관리자

### GET /api/admin/performances — 전체 공연 목록 조회

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path 파라미터 없음. 요청 본문 없음.

Query

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| sellerId | string | 선택 | 판매자 필터. |
| publicationStatus | string | 선택 | DRAFT / PUBLISHED. |
| operationStatus | string | 선택 | ACTIVE / CANCELED. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시: `/api/admin/performances?sellerId=seller-uuid&publicationStatus=DRAFT&operationStatus=ACTIVE&page=0&size=20`

Response DTO (HTTP 200)

내 공연 목록 조회 응답과 같은 구조다(items는 PerformanceManageSummary[], page, size, totalElements).

```json
{
  "items": [
    {
      "id": "20",
      "venue": {
        "id": "10",
        "name": "서울 공연장",
        "address": "서울특별시 중구 예시로 10",
        "region": "서울"
      },
      "title": "공연 A 서울",
      "genre": "MUSICAL",
      "posterUrl": "https://example.com/poster.jpg",
      "publicationStatus": "PUBLISHED",
      "operationStatus": "ACTIVE",
      "period": {
        "from": "2026-12-01",
        "to": "2026-12-02"
      },
      "version": 3,
      "sellerId": "seller-uuid"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

처리·검증 규칙

관리자는 전체 판매자 공연을 조회할 수 있다. 관리자 목록은 publicationStatus와 operationStatus 필터를 분리한다.

### GET /api/admin/performances/{id} — 관리자 공연 상세 조회

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

내 공연 상세 조회 응답과 같은 구조다(Performance + gradePrices, excludedSeats, schedules).

```json
{
  "id": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg",
  "publicationStatus": "PUBLISHED",
  "operationStatus": "ACTIVE",
  "publishedAt": "2026-10-26T10:00:00+09:00",
  "period": {
    "from": "2026-12-01",
    "to": "2026-12-02"
  },
  "version": 3,
  "createdAt": "2026-10-26T09:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00",
  "sellerId": "seller-uuid",
  "gradePrices": [
    {
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "price": 150000
    }
  ],
  "excludedSeats": [
    {
      "venueSeatId": "1002",
      "reason": "운영석"
    }
  ],
  "schedules": [
    {
      "id": "201",
      "performanceId": "20",
      "startAt": "2026-12-01T19:00:00+09:00",
      "bookingOpenAt": "2026-11-01T14:00:00+09:00",
      "bookingCloseAt": "2026-12-01T18:00:00+09:00",
      "operationStatus": "ACTIVE",
      "seatSetupStatus": "READY",
      "salesSuspended": false,
      "salesStatus": "BEFORE_OPEN",
      "canceledAt": null,
      "seatConfigVersion": 1,
      "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
      "seatSetupError": null,
      "suspensionReason": null,
      "cancelReason": null,
      "version": 0,
      "performanceVersion": 3
    }
  ]
}
```

처리·검증 규칙

관리 응답에만 판매자 ID·내부 준비 상태·제외 사유를 포함한다.

### POST /api/admin/schedules/{id}/cancellation — 공연 회차 취소

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization, If-Match (부모 performance.version)
- 성공 HTTP: 202
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| reason | string | 필수 | 취소 사유. 1~500자. |
| scheduleVersion | integer | 필수 | 대상 회차 현재 버전. |

```json
{
  "reason": "주최 측 사정으로 취소",
  "scheduleVersion": 0
}
```

Response DTO (HTTP 202)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scheduleId | string | 필수 | 취소 회차 ID. |
| operationStatus | string | 필수 | CANCELED 고정. |
| canceledAt | datetime | 필수 | 회차 취소 확정 시각. |
| refundProcessingStatus | string | 필수 | PENDING: 환불 완료가 아닌 후속 처리 접수 상태. |
| version | integer | 필수 | 회차 최신 버전. |
| performanceVersion | integer | 필수 | 부모 공연 최신 버전. |

```json
{
  "scheduleId": "201",
  "operationStatus": "CANCELED",
  "canceledAt": "2026-10-26T11:00:00+09:00",
  "refundProcessingStatus": "PENDING",
  "version": 1,
  "performanceVersion": 4
}
```

처리·검증 규칙

202는 회차 취소·후속 환불 처리 접수를 뜻한다. 환불 완료를 의미하지 않는다.

공통 계약 5.8의 추가 규칙이다. 같은 회차를 반복 취소하면 새 환불을 중복 생성하지 않고 기존 취소 결과를 반환한다. 환불 진행 상태는 공연 테이블의 확정 상태가 아니라 예매·결제 측 추적이 필요한 계약이다. 공통 계약은 취소에 `Idempotency-Key`를 권장한다.

### PUT /api/admin/schedules/{id}/sales-suspension — 회차 판매 일시 중지·재개

- 권한·허용 호출자: ADMIN
- 필수 헤더: Authorization, If-Match (부모 performance.version)
- 성공 HTTP: 200
- Notion 상태: 시작 전

공통 계약 API 목록에서 협의안으로 표시한 기능이다.

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. |

Query 파라미터 없음.

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| suspended | boolean | 필수 | true 중지, false 재개. |
| reason | string\|null | 선택 | 중지 시 필수 1~500자. 재개 시 생략 또는 null. |
| scheduleVersion | integer | 필수 | 회차 현재 버전. |

```json
{
  "suspended": true,
  "reason": "판매 설정 점검",
  "scheduleVersion": 0
}
```

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| scheduleId | string | 필수 | 회차 ID. |
| salesSuspended | boolean | 필수 | 설정 결과. |
| suspensionReason | string\|null | 필수 | 재개 시 null. |
| version | integer | 필수 | 회차 버전. |
| performanceVersion | integer | 필수 | 공연 버전. |

```json
{
  "scheduleId": "201",
  "salesSuspended": true,
  "suspensionReason": "판매 설정 점검",
  "version": 1,
  "performanceVersion": 4
}
```

처리·검증 규칙

suspended=true이면 reason이 필수다. 재개 시 저장된 중지 사유를 null로 정리한다. 취소·마감·미준비 상태를 우회하지 못한다.

공통 계약 5.8의 추가 규칙이다. 해제해도 오픈 전·마감 후·좌석 미준비·취소 조건을 우회하지 않는다. 취소 회차 재개는 409다. 기존 선점의 결제를 허용할지는 미확정이며, 신규 선점 검증과 결제 확정 검증을 같은 규칙으로 처리하지 않도록 계약을 분리해야 한다.

### 공개 조회

### GET /api/performances — 공개 공연 목록 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path 파라미터 없음. 요청 본문 없음.

Query

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| genre | string | 선택 | CONCERT / MUSICAL / PLAY / SPORTS. |
| region | string | 선택 | 지역. 생략 시 전체. |
| from | date | 선택 | 공연 시작일 검색 하한. KST, 포함. |
| to | date | 선택 | 공연 시작일 검색 상한. KST, 포함. |
| keyword | string | 선택 | 검색어. 생략 시 필터 없음. |
| sort | string | 선택 | LATEST / BOOKING_OPEN_ASC. 기본 LATEST. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시: `/api/performances?genre=MUSICAL&region=%EC%84%9C%EC%9A%B8&from=2026-12-01&to=2026-12-31&keyword=%EA%B3%B5%EC%97%B0&sort=LATEST&page=0&size=20`

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| items | PerformanceSummary[] | 필수 | 공개 공연 목록. |
| items[].id | string | 필수 | 공연 상품 ID. |
| items[].venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| items[].venue.id | string | 필수 | 공연장 ID. BIGINT를 문자열로 표현. |
| items[].venue.name | string | 필수 | 공연장명. 1~100자. |
| items[].venue.address | string | 필수 | 주소. 1~255자. |
| items[].venue.region | string | 필수 | 지역. 1~20자. |
| items[].title | string | 필수 | 공연명. 1~200자. |
| items[].genre | string | 필수 | CONCERT / MUSICAL / PLAY / SPORTS. |
| items[].posterUrl | string\|null | 필수 | HTTPS 이미지 URL. 최대 500자. 초안 미등록 시 null. |
| items[].publicationStatus | string | 필수 | DRAFT / PUBLISHED. |
| items[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| items[].period | Period\|null | 필수 | 활성 회차 집계 기간. 회차가 없으면 null. |
| items[].period.from | date | 필수 | 활성 회차의 첫 공연 날짜. |
| items[].period.to | date | 필수 | 활성 회차의 마지막 공연 날짜. |
| items[].version | integer | 필수 | 공연 및 자식 구성 변경 버전. If-Match 기준. |
| page | integer | 필수 | 0부터 시작. |
| size | integer | 필수 | 1~100. |
| totalElements | integer | 필수 | 전체 건수. |

```json
{
  "items": [
    {
      "id": "20",
      "venue": {
        "id": "10",
        "name": "서울 공연장",
        "address": "서울특별시 중구 예시로 10",
        "region": "서울"
      },
      "title": "공연 A 서울",
      "genre": "MUSICAL",
      "posterUrl": "https://example.com/poster.jpg",
      "publicationStatus": "PUBLISHED",
      "operationStatus": "ACTIVE",
      "period": {
        "from": "2026-12-01",
        "to": "2026-12-02"
      },
      "version": 3
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

처리·검증 규칙

공개·미삭제·정상 공연만 기본 조회한다. from과 to가 모두 있으면 from <= to여야 한다.

공통 계약 5.3의 추가 규칙이다. 취소 공연의 검색 포함 여부는 추후 확정하며 직접 상세에서는 취소 안내를 유지한다. BOOKING_OPEN_ASC는 판매 종료되지 않은 회차의 가장 이른 오픈 시각 기준이며, 해당 회차가 없으면 마지막에 배치한다.

### GET /api/performances/{id} — 공개 공연 상세 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

공연 초안 등록 응답의 Performance 필드에서 sellerId를 뺀 필드(id ~ updatedAt)에 아래 필드를 더한다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| gradePrices | GradePrice[] | 필수 | 해당 공연 가격. 모든 회차 공통. |
| gradePrices[].venueSeatGradeId | string | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 이상. |

```json
{
  "id": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg",
  "publicationStatus": "PUBLISHED",
  "operationStatus": "ACTIVE",
  "publishedAt": "2026-10-26T10:00:00+09:00",
  "period": {
    "from": "2026-12-01",
    "to": "2026-12-02"
  },
  "version": 3,
  "createdAt": "2026-10-26T09:00:00+09:00",
  "updatedAt": "2026-10-26T10:00:00+09:00",
  "gradePrices": [
    {
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "price": 150000
    }
  ]
}
```

처리·검증 규칙

초안·삭제는 404다. 공개된 취소 공연은 취소 안내 목적으로 상세 조회할 수 있다. 판매자 ID와 내부 오류는 반환하지 않는다.

### GET /api/performances/{id}/schedules — 공연 회차 목록 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. (Notion 원문 그대로다. 경로상 공연 ID다.) |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| performanceId | string | 필수 | 공연 ID. |
| schedules | SchedulePublic[] | 필수 | 공개 회차 목록. 없으면 빈 배열. |
| schedules[].id | string | 필수 | 회차 ID. |
| schedules[].performanceId | string | 필수 | 공연 ID. |
| schedules[].startAt | datetime | 필수 | 회차 시작 시각. |
| schedules[].bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| schedules[].bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| schedules[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| schedules[].seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| schedules[].salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| schedules[].salesStatus | string | 필수 | CANCELED / SUSPENDED / PREPARING / SETUP_FAILED / BEFORE_OPEN / CLOSED / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| schedules[].canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |

```json
{
  "performanceId": "20",
  "schedules": [
    {
      "id": "201",
      "performanceId": "20",
      "startAt": "2026-12-01T19:00:00+09:00",
      "bookingOpenAt": "2026-11-01T14:00:00+09:00",
      "bookingCloseAt": "2026-12-01T18:00:00+09:00",
      "operationStatus": "ACTIVE",
      "seatSetupStatus": "READY",
      "salesSuspended": false,
      "salesStatus": "BEFORE_OPEN",
      "canceledAt": null
    }
  ]
}
```

처리·검증 규칙

회차별 임의 등급·가격을 반환하지 않는다. 가격은 공연 상세의 공통 gradePrices를 사용한다.

### GET /api/performances/{id}/seat-layout — 공연 좌석 배치 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- Notion 상태: 시작 전

공통 계약 API 목록에서 권한 분리 협의안으로 표시한 API다. 공개 정책은 최종 합의가 필요하다.

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| performanceId | string | 필수 | 공연 ID. |
| venue | VenueSummary | 필수 | 공연장. |
| venue.id | string | 필수 | 공연장 ID. BIGINT를 문자열로 표현. |
| venue.name | string | 필수 | 공연장명. 1~100자. |
| venue.address | string | 필수 | 주소. 1~255자. |
| venue.region | string | 필수 | 지역. 1~20자. |
| seats | LayoutSeat[] | 필수 | 고정 등급·공연별 가격·공통 제외 여부. |
| seats[].id | string | 필수 | 물리 좌석 ID. |
| seats[].section | string | 필수 | 구역. 1~20자. |
| seats[].rowLabel | string | 필수 | 열. 1~10자. |
| seats[].seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seats[].venueSeatGradeId | string | 필수 | 동일 공연장의 고정 등급 ID. |
| seats[].gradeName | string | 필수 | 고정 등급명. |
| seats[].isSellable | boolean | 필수 | 공연 공통 제외 정책 반영. 실시간 점유 여부 아님. |
| seats[].basePrice | integer\|null | 필수 | 공연 등급 가격. 판매 제외 등급 가격 미설정 시 null. |

```json
{
  "performanceId": "20",
  "venue": {
    "id": "10",
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울"
  },
  "seats": [
    {
      "id": "1001",
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "isSellable": true,
      "basePrice": 150000
    }
  ]
}
```

처리·검증 규칙

정적 배치·공연장 등급·공연별 가격·공연 공통 제외 여부만 제공한다. 실시간 선택 가능 여부는 포함하지 않는다. 실시간 선택 가능 좌석은 예매 서비스 소유의 `GET /api/schedules/{id}/seats`가 맡는다.

### GET /api/schedules/{id} — 공연 회차 상세 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- Notion 상태: 시작 전

Request DTO

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 공연 회차 ID. |

Query 파라미터 없음. 요청 본문 없음.

Response DTO (HTTP 200)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | 필수 | 회차 ID. |
| performanceId | string | 필수 | 공연 ID. |
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| salesStatus | string | 필수 | CANCELED / SUSPENDED / PREPARING / SETUP_FAILED / BEFORE_OPEN / CLOSED / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |

```json
{
  "id": "201",
  "performanceId": "20",
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00",
  "operationStatus": "ACTIVE",
  "seatSetupStatus": "READY",
  "salesSuspended": false,
  "salesStatus": "BEFORE_OPEN",
  "canceledAt": null
}
```

처리·검증 규칙

판매 상태는 서버 시각·운영 상태·준비 상태로 계산한다. 좌석별 선점 상태는 예매 서비스 조회를 이용한다.

## 3. 내부 API

제공하는 내부 API는 [shared/internal-api/event.md](../shared/internal-api/event.md)에 둔다.
