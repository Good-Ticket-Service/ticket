# 공연 API 명세

- 상태: 작성 중
- 담당: 공연 담당
- 출처: Notion [API 명세](https://app.notion.com/p/404b3ff41d398040a969f8eaa2e007a8)의 공연 도메인 (2026-10-27 기준). 이후 원본은 이 문서이며, Notion은 담당자가 이 문서를 기준으로 갱신한다.
- 테이블과 ERD: [tables.md](tables.md)

## 1. 공통 계약

절 번호(5.x)는 Notion 원본 번호를 유지한다. 본문의 "5.8", "5.9" 같은 참조도 이 번호를 가리킨다.

결정 근거·전체 공통 계약: https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b

설계 개선 반영 기록(2026-10-27): https://app.notion.com/p/406b3ff41d398113abd8d34a1ccf3d97

공연 서비스 소유 API는 데이터베이스의 공연 도메인에 개별 등록한다.
예매 서비스 소유 회차 좌석 API의 세부 계약은 예매·좌석 담당 명세를 따르며, 이 페이지에는 공연 서비스와 맞닿는 계약만 남긴다.

### 5. API 공통 계약

아래는 현재 API 계약이며, 미확정 사항은 해당 절에 별도로 표시한다.
좌석 위치·등급은 공연장에 고정한다.
가격은 공연별 등급 가격이며 동일 공연의 모든 회차가 공유한다.
선점·판매 상태만 예매 서비스에서 회차별로 관리한다.
API도 이 책임 단위와 공개 정책을 따른다.
schedule이라는 기존 명칭을 일관되게 사용한다.

- 기본 경로 /api.
  JSON camelCase.
  ID는 BIGINT 기반 JSON 숫자로 전달한다. requestId·eventId·orderId는 문자열을 유지한다.
- 일반 사용자용 공개 조회는 공개·미삭제 데이터만 반환하며 초안 ID 직접 조회는 404다. 취소된 공개 공연은 취소 안내를 위해 상세 조회 가능하다. 판매자용 관리 회차 조회는 별도 권한 검증 후 DRAFT·취소 회차도 조회한다.
- SELLER는 본인 소유 리소스만 변경한다.
  MASTER는 공연장 관리·모든 공연 조회·회차 취소를 수행한다.
  관리자 예외 수정은 별도 운영 절차이며 공개 후 불변 규칙을 우회하지 않는다.
- 인증·권한은 서버 검증.
  sellerId를 요청 본문으로 받지 않는다.
  내부 API는 서비스 인증 필요.
- If-Match: "현재 version"은 요청 대상 리소스의 version이다.
  자체 version이 있는 venue·performance·schedule은 자기 version, version이 없는 하위 리소스(공연장 등급·좌석, 공연별 가격·판매 제외)는 소유 리소스의 version을 쓴다.
  미전달 428, 불일치 409 VERSION_CONFLICT.
  회차 등록은 If-Match를 받지 않고 서버가 부모 공연 행을 잠근다.
  회차 명령은 요청 본문에 버전 필드를 두지 않고 If-Match로 schedule.version을 전달한다.
  관리용 단건 조회는 ETag: "{version}"을 응답한다.
- prefix 없는 `/api/**`는 누구나 쓰는 공개 조회, `/api/seller/**`는 판매자용(공연장 조회는 MASTER도 허용), `/api/admin/**`는 MASTER 전용이다.
  경로 변수는 {performanceId}·{scheduleId}·{venueId}처럼 리소스 이름을 붙인다.
- 켜고 끄는 상태는 PUT 하위 리소스(sales-suspension), 되돌릴 수 없는 전환은 POST 명사 하위 리소스(publication, cancellation)로 한다.
- 목록: page는 0부터, size 기본 20·최대 100. 응답 items, page, size, totalElements.
  필터 날짜는 KST 공연 시작일 기준.
- 단건 생성은 201과 Location을 반환한다. Location은 생성한 리소스의 실제 GET 조회 URI이며 생성 호출자가 조회할 수 있는 경로·권한이어야 한다.
- 일괄 생성은 201을 유지하되 Location을 생략하고 생성된 리소스의 ID 목록을 응답 본문에 반환한다. 등급 등록은 grades[].id, 좌석 등록은 createdSeatIds를 사용한다.
- 단순 수정 200, 삭제 204, 비동기 접수 202를 사용한다. 좌석 생성 완료를 201/202로 보장하지 않는다.
- POST 공연·회차 생성과 회차 취소는 Idempotency-Key를 권장하는 계약안이다.
  같은 키·같은 요청은 기존 결과, 같은 키·다른 요청은 409. 키 보존 기간·범위는 협의한다.
- 알 수 없는 수정 필드 및 공개 후 금지 필드는 조용히 무시하지 않고 400/409로 거절한다.

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 형식을 확인해주세요.",
  "path": "/api/seller/performances/20",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "title",
      "reason": "공백일 수 없습니다."
    }
  ]
}
```

#### 5.1 API 목록

| 메서드·경로 | 권한 | 요청 요약 | 성공 응답 |
| --- | --- | --- | --- |
| POST /api/admin/venues | MASTER | name, address, region | 201 Venue |
| GET /api/seller/venues/{venueId} | SELLER / MASTER | venueId | 200 공연장 기본 정보·최신 version |
| POST /api/admin/venues/{venueId}/seats | MASTER | seats 배열(venueSeatGradeId 필수), If-Match | 201 createdCount·createdSeatIds·venueVersion. Location 생략 |
| GET /api/seller/venues | SELLER / MASTER | region, keyword, page, size | 200 VenuePage |
| GET /api/seller/venues/{venueId}/seats | SELLER / MASTER | venueId | 200 물리 좌석 목록 |
| POST /api/seller/performances | SELLER | 공연 기본 정보 | 201 Performance, DRAFT |
| PUT /api/seller/performances/{performanceId}/grade-prices | 소유 SELLER | gradePrices 배열, If-Match | 200 등급별 가격·performanceVersion |
| POST /api/seller/performances/{performanceId}/schedules | 소유 SELLER | 회차 시간<br>등급·좌석 배정 입력 없음 | 201 Schedule, PENDING |
| PATCH /api/seller/performances/{performanceId} | 소유 SELLER | 허용 기본 필드, If-Match | 200 Performance |
| GET /api/seller/schedules/{scheduleId} | 소유 SELLER | scheduleId | 200 관리용 회차 DTO·ETag |
| PATCH /api/seller/schedules/{scheduleId} | 소유 SELLER | 시간, If-Match(schedule.version) | 200 Schedule |
| POST /api/seller/performances/{performanceId}/publication | 소유 SELLER | 본문 없음, If-Match | 200 Performance |
| GET /api/seller/performances | SELLER | status, keyword, page, size | 200 본인 PerformancePage |
| GET /api/seller/performances/{performanceId} | 소유 SELLER | performanceId | 200 기본·등급·회차·좌석 준비 상태 |
| DELETE /api/seller/performances/{performanceId} | 소유 SELLER | If-Match | 204 |
| DELETE /api/seller/schedules/{scheduleId} | 소유 SELLER | If-Match(schedule.version) | 204 |
| GET /api/admin/performances | MASTER | sellerId, 상태, page, size | 200 전체 관리 목록 |
| GET /api/admin/performances/{performanceId} | MASTER | performanceId | 200 관리 상세 |
| POST /api/admin/schedules/{scheduleId}/cancellation | MASTER | reason, If-Match(schedule.version) | 202 회차 취소 및 환불 PENDING |
| PUT /api/admin/schedules/{scheduleId}/sales-suspension | MASTER | suspended, reason, If-Match(schedule.version) | 200 판매 중지 상태 |
| GET /api/performances | 누구나 | genre, region, from, to, keyword, sort, page, size | 200 공개 목록 |
| GET /api/performances/{performanceId} | 누구나 | performanceId | 200 공개 상세·등급별 가격 |
| GET /api/performances/{performanceId}/schedules | 누구나 | performanceId | 200 회차별 판매 상태·준비 여부 |
| GET /api/schedules/{scheduleId} | 누구나 | scheduleId | 200 회차 상세·판매 정책 |
| GET /api/performances/{performanceId}/seat-layout | 누구나 | performanceId | 200 정적 배치.<br>권한 분리 협의안 |
| POST /internal/schedules/{scheduleId}/sale-validations | 예매 서비스 | configVersion, 좌석 ID 목록 | 200 판매 허용·기본 가격 스냅샷 |
| POST /api/admin/venues/{venueId}/seat-grades | MASTER | grades 이름 목록, If-Match | 201 grades[].id·이름·venueVersion. Location 생략 |
| GET /api/seller/venues/{venueId}/seat-grades | SELLER / MASTER | venueId | 200 공연장 고정 등급 |
| PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId} | MASTER | name, If-Match<br>사용 전만 허용 | 200 등급·venueVersion |
| PATCH /api/admin/venues/{venueId}/seats/{seatId} | MASTER | 좌석 위치·venueSeatGradeId, If-Match<br>사용 전만 허용 | 200 좌석·venueVersion |
| DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId} | MASTER | If-Match<br>사용 전·좌석 미배정만 허용 | 204 |
| DELETE /api/admin/venues/{venueId}/seats/{seatId} | MASTER | If-Match<br>사용 전만 허용 | 204 |
| PUT /api/seller/performances/{performanceId}/seat-exclusions | 소유 SELLER | excludedSeats, If-Match | 200 제외 목록·performanceVersion<br>공연 공통 제외 기능 제안 |

회차 좌석 조회·잔여석·선점·선점 해제 API는 예매 서비스 소유이므로 이 목록에 넣지 않는다. 세부 계약은 **예매·좌석 담당자 확인 필요**.
GET /api/schedules/{scheduleId}(공개 회차 상세)는 공연 서비스 경로로 유지한다. 같은 접두사를 쓰는 예매·대기열 경로는 해당 도메인에서 변경해 Gateway 라우팅 충돌을 해소한다.

#### 5.2 공연장 등급·좌석 등록

**등록 순서: 공연장 → 공연장 좌석 등급 → 등급이 지정된 물리 좌석.**
공연장 등급은 MASTER만 생성·관리한다.
등급 가격은 이 단계에서 받지 않는다.

POST /api/admin/venues 요청 필수: name, address, region.
name은 1~100자, address는 1~255자, region은 1~20자다.
응답: id, name, address, region, version, createdAt, updatedAt.

**POST /api/admin/venues/{venueId}/seat-grades**
If-Match는 현재 venue.version을 전달한다.

```json
{
  "grades": [
    {
      "name": "VIP"
    },
    {
      "name": "R"
    }
  ]
}
```

동일 공연장 내 이름 중복은 409 DUPLICATE_VENUE_GRADE.
응답 201: grades[{id, name}], venueVersion. grades[].id로 이번에 생성한 등급을 식별하며 Location은 생략한다.
GET /api/seller/venues/{venueId}/seat-grades는 SELLER/MASTER에게 동일 등급 목록과 venueVersion을 제공한다.

**POST /api/admin/venues/{venueId}/seats**

```json
{
  "seats": [
    {
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": 101
    },
    {
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 2,
      "venueSeatGradeId": 101
    },
    {
      "section": "B",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": 102
    }
  ]
}
```

venueSeatGradeId는 필수이며 같은 공연장 등급만 허용한다.
다른 공연장 등급이면 400 VENUE_GRADE_MISMATCH.
중복 좌석이면 409 DUPLICATE_VENUE_SEAT이며 전체 롤백한다.
응답 201: createdCount, createdSeatIds(integer[]), venueVersion. createdSeatIds는 이번 요청으로 생성한 모든 좌석 ID이고 길이는 createdCount와 같다. 일괄 생성이므로 Location은 생략한다.
좌석 조회 응답에는 id, section, rowLabel, seatNumber, venueSeatGradeId, gradeName을 포함한다.

초기 등록 단계의 오류 수정용으로 PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId}와 PATCH /api/admin/venues/{venueId}/seats/{seatId}를 제공한다.
각각 name 또는 좌석 위치·venueSeatGradeId를 부분 변경하며 If-Match로 venue.version을 확인한다.
성공 시 200 및 변경 리소스·venueVersion을 반환한다.
잘못 등록한 등급·좌석은 DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId}와 DELETE /api/admin/venues/{venueId}/seats/{seatId}로 삭제한다. 요청 본문 없이 If-Match로 venue.version을 확인하고 소프트 삭제하며, 성공 시 204를 반환하고 venue.version을 1 증가시킨다.
두 삭제 API도 공연이 해당 배치를 참조한 이후에는 409 VENUE_LAYOUT_IN_USE다. 등급 삭제는 그 등급을 쓰는 미삭제 좌석이 있으면 409 VENUE_GRADE_IN_USE다.
공연에서 사용하기 전까지만 수정할 수 있다.
공연이 해당 배치를 참조한 이후에는 409 VENUE_LAYOUT_IN_USE로 등급 추가·수정 및 좌석 추가·변경을 차단한다.
다른 공연의 VIP 가격을 바꾸기 위해 공연장 등급을 수정해서는 안 된다.
구역별 일괄 입력은 UI 또는 업로드가 개별 좌석 입력으로 변환하며 등급 원본은 공연장 한 곳이다.

#### 5.3 공연 생성·수정·조회 DTO

```json
{
  "venueId": 10,
  "title": "공연 A",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg"
}
```

venueId, title, genre, ageRating, runningTime은 생성 필수.
나머지는 DRAFT에서 생략 가능하며 공개 필수 조건은 별도 검증한다.
PATCH는 전달 필드만 변경한다.
공개 후 description, posterUrl, castInfo만 허용한다.

**공개 공연 목록 응답**

items의 각 항목은 id, venue{id,name,address,region}, title, genre, posterUrl, operationStatus, period{from,to}를 반환한다.
version·publicationStatus·sellerId는 반환하지 않는다.
페이지 필드는 items, page, size, totalElements를 사용한다.

**공개 공연 상세 응답**

id, venue{id,name,address,region}, title, genre, ageRating, runningTime, castInfo, description, posterUrl, operationStatus, publishedAt, period{from,to}, createdAt, updatedAt, gradePrices를 반환한다.
version·publicationStatus·sellerId는 반환하지 않는다.
내부 오류·seatSetupRequestId·판매 제외 내부 사유도 공개하지 않는다.

**판매자·관리자용 공연 응답**

수정 충돌 확인과 공개 상태 관리를 위해 version·publicationStatus를 유지한다.
sellerId·gradePrices·excludedSeats·schedules 등 추가 필드의 제공 범위는 각 관리 API의 Response DTO를 따른다.
공개용 DTO를 관리용 DTO의 전체 필드로 설명하거나 그 반대로 해석하지 않는다.

**공통 표현**

BIGINT 기반 리소스 ID와 중첩 venue.id도 integer로 표현한다.
requestId·eventId·orderId 같은 UUID·문자열 식별자는 string을 유지한다.
공연 기간은 현재 명세의 계산 기준에서 대상 회차가 없으면 null이다.
외부 성공 응답은 위 DTO를 ApiResponse의 data에 담는다.

공개 목록은 PUBLISHED·미삭제·정상 공연을 기본 대상으로 한다.
취소 공연 검색 포함 여부는 추후 확정하고 직접 상세에서는 취소 안내를 유지한다.
sort는 LATEST 또는 BOOKING_OPEN_ASC 제안이며, 후자는 판매 종료되지 않은 회차의 가장 이른 오픈 시각 기준이다.
해당 회차가 없으면 마지막에 배치한다.

#### 5.4 공연 단위 등급 가격·판매 제외 설정

**PUT /api/seller/performances/{performanceId}/grade-prices**
소유 SELLER만 DRAFT에서 호출한다.
If-Match는 performance.version이다.
기존 /grades API를 대체하며 등급 생성·이름 변경 기능은 없다.

```json
{
  "gradePrices": [
    {
      "venueSeatGradeId": 101,
      "price": 150000
    },
    {
      "venueSeatGradeId": 102,
      "price": 100000
    }
  ]
}
```

전체 공연의 가격 집합을 교체한다.
같은 venueSeatGradeId 중복은 거절한다.
해당 공연장에 속한 등급만 허용한다.
가격은 0 초과 정수다.
판매 대상 좌석에 사용되는 등급은 모두 가격이 있어야 하며 누락 시 400 GRADE_PRICE_MISSING이다.
모든 좌석이 제외된 등급에는 가격을 생략할 수 있으나, 이후 판매 대상으로 되돌릴 때는 가격이 필요하다.
가격 변경 시 해당 공연 회차들의 configVersion을 올리고 PENDING으로 재준비한다.
응답 200: gradePrices[{venueSeatGradeId, gradeName, price}], performanceVersion.
공개 후 409 PERFORMANCE_CONFIGURATION_LOCKED.

**PUT /api/seller/performances/{performanceId}/seat-exclusions — 구현 제안**
회차별 판매 제외 대신 공연 공통 판매 제외 좌석 전체를 설정한다.

```json
{
  "excludedSeats": [
    {
      "venueSeatId": 1002,
      "reason": "운영석"
    }
  ]
}
```

빈 배열은 제외 좌석이 없다는 의미다.
소유 SELLER·DRAFT·If-Match 조건을 적용한다.
좌석 ID 중복 및 타 공연장 좌석을 거절한다.
판매 대상으로 남는 좌석 등급의 가격이 모두 있어야 한다.
변경하면 모든 회차를 최신 구성으로 재준비한다.
응답 200: excludedSeats, performanceVersion.
등급과 가격을 좌석별로 덮어쓰는 필드는 받지 않는다.

#### 5.5 회차 등록·수정 — 좌석 배정 입력 없음

```json
{
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00"
}
```

공연의 공연장·고정 좌석 등급·공연별 등급 가격·공연 공통 제외 정책을 서버에서 조회한다.
seatAssignments, seatGradeId, venueSeatGradeId, 좌석별 가격 입력은 회차 API에서 허용하지 않는다.
회차마다 다른 등급 배정은 지원하지 않는다.
동일 공연의 모든 회차는 동일한 좌석 등급·가격·제외 정책을 사용한다.
좌석별 선점·판매 상태만 회차별로 다르다.
회차 중복은 409 DUPLICATE_SCHEDULE이다.

응답:

```json
{
  "id": 201,
  "performanceId": 20,
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00",
  "operationStatus": "ACTIVE",
  "seatSetupStatus": "PENDING",
  "seatConfigVersion": 1,
  "seatSetupRequestId": "request-uuid",
  "salesSuspended": false,
  "version": 0
}
```

공개 후에도 취소되지 않은 공연에 새 회차를 추가할 수 있다. 기존 회차 수정은 DRAFT에서만 가능하다.
회차 수정은 시간 필드만 받고 If-Match로 schedule.version을 전달한다.
회차 등록은 If-Match를 받지 않고 같은 트랜잭션에서 부모 공연 행을 SELECT ... FOR UPDATE로 잠가 공개·구성 변경과의 경쟁을 막는다.
공연장·등급·가격은 부모 구성에서 가져온다.
시간만 변경할 때 좌석 재준비는 하지 않는다. schedule_seat에는 시간 스냅샷이 없다.

회차 관리 조회는 GET /api/seller/schedules/{scheduleId}로 제공하며 등록·수정과 같은 관리용 DTO를 반환한다.
본인 판매자는 DRAFT·취소 회차도 조회한다. 회차 또는 부모 공연의 삭제·미존재는 404 SCHEDULE_NOT_FOUND다.
공연장 단건 조회는 GET /api/seller/venues/{venueId}로 제공하며 SELLER / MASTER가 기본 정보와 최신 version을 조회한다. 삭제·미존재는 404 VENUE_NOT_FOUND다.
두 조회 API는 If-Match를 요구하지 않고 버전을 변경하지 않으며 ETag: "{version}"을 응답한다.
회차 등록 Location은 /api/seller/schedules/{scheduleId}, 공연장 등록 Location은 /api/seller/venues/{venueId}, 공연 초안 등록 Location은 기존 /api/seller/performances/{performanceId}다.
회차 수정 성공 시 schedule.version만 1 증가한다. 부모 performance.version은 회차 변경으로 바뀌지 않는다. 예: If-Match "0" → version=1.

#### 5.6 공연 공개

```txt
POST /api/seller/performances/20/publication

If-Match: "3"
```

공개 검증 실패 시 409 PUBLICATION_REQUIREMENTS_NOT_MET와 누락 항목을 반환한다.
PENDING/FAILED는 공개 실패 조건이 아니다.
요청 본문은 없다. 이미 공개된 공연에 다시 요청하면 If-Match 일치 여부와 관계없이 현재 공개 결과를 200으로 반환하고 최초 publishedAt을 보존한다.
DRAFT 복귀 API는 없다.
공개 트랜잭션은 필수 정보 및 자식 구성 동시 변경을 방지한다.

#### 5.7 좌석 준비 실패 처리

판매자용 좌석 준비 재시도 API는 제공하지 않는다.
회차 등록·구성 변경(등급 가격, 판매 제외, 공연장 변경)으로 회차가 PENDING이 되면 트랜잭션 커밋 후 공연 서비스가 Feign으로 예매 서비스의 좌석 준비 내부 API를 호출한다. 호출을 트랜잭션 안에서 하지 않는다.
성공하면 READY로 바꾼다.
일시적 실패(타임아웃·5xx·연결 실패)는 PENDING을 유지하고, 스케줄러가 seat_setup_next_attempt_at이 지난 PENDING 회차를 같은 requestId로 다시 보내며 seat_setup_attempts를 1 증가시킨다.
최대 재시도 횟수를 넘기면 FAILED로 바꾸고 운영 알림을 남긴다.
요청 데이터 오류(4xx 검증 실패)는 재시도하지 않고 바로 FAILED로 바꾸며 seat_setup_error에 안전한 요약을 남긴다.
DRAFT 공연의 FAILED 회차는 판매자가 기존 구성 변경 API(등급 가격 설정, 판매 제외 설정, 공연 정보 수정의 공연장 변경)로 원인을 고치면 configVersion이 올라가고 새 requestId로 PENDING이 되어 다시 준비한다.
판매자는 회차 조회 응답의 seatSetupStatus·seatSetupError로 상태를 확인한다.
재시도 횟수·간격·알림 방식과 공개 후 FAILED 회차의 운영 절차는 구현 시 결정한다.
예매 서비스가 같은 requestId 재처리로 좌석을 중복 생성하지 않는 멱등 처리는 **예매·좌석 담당자 합의 필요**다.

#### 5.8 회차 취소·판매 중지

취소 요청 reason은 필수 1~500자.
같은 회차 반복 취소는 새 환불을 중복 생성하지 않고 기존 취소 결과를 반환한다.
응답: scheduleId, operationStatus=CANCELED, canceledAt, refundProcessingStatus=PENDING.

**확정된 취소 범위**

공연 전체 취소 전용 API는 제공하지 않고 회차별 취소로 처리한다.
반복 취소 요청은 중복 환불을 생성하지 않도록 같은 회차의 기존 취소 결과를 사용한다.
202는 공연 서비스의 회차 취소·후속 처리 접수 응답이며 실제 환불 완료 응답이 아니다.

**후속 전파 계약 — 담당자 합의 필요**

검토 보고서 3-7의 내부 HTTP 호출 후 Kafka 전환은 예매 담당자의 제안이며 아직 최종 확정된 계약이 아니다.
공연 → 예매 전달 방식·경로·페이로드, 전달 실패 기록과 재시도 주체, 예매의 예약 상태별 정리 및 좌석 처리, 환불 진행 조회 계약을 공연·예매·좌석·결제 담당자가 함께 확정해야 한다.

refundProcessingStatus=PENDING은 후속 환불 처리 대기 상태다.
예매 서비스로 전달되었다는 보장이나 PG 환불 완료를 뜻하지 않는다.
예매의 CANCELED와 실제 PG 환불 완료는 구분한다.
reservation-summary의 상태별 예약 건수만으로 환불 완료를 판단하는 계약은 확정하지 않았다.

근거: https://app.notion.com/p/405b3ff41d3981f09dd2d702fba4ac54 3-7. 공연 ↔ 예매·결제.

판매 중지 요청:

```json
{
  "suspended": true,
  "reason": "판매 설정 점검"
}
```

중지 시 reason은 trim 후 1~500자 필수다. 재개 시 생략 또는 null을 허용하고 저장된 중지 사유를 비운다.
해제해도 오픈 전·마감 후·좌석 미준비·취소 조건을 우회하지 않는다.
취소 회차 재개는 409. 기존 유효 선점은 원래 만료 시각까지 결제를 허용하며 신규 선점 검증과 결제 확정 검증을 같은 규칙으로 처리하지 않도록 계약을 분리해야 한다.

#### 5.9 내부 판매 검증

```json
{
  "configVersion": 1,
  "venueSeatIds": [
    1001
  ]
}
```

응답:

```json
{
  "allowed": true,
  "scheduleId": 201,
  "configVersion": 1,
  "policyVersion": 5,
  "performanceTitle": "공연 A",
  "scheduleStartAt": "2026-12-01T19:00:00+09:00",
  "validatedAt": "2026-11-01T14:01:00+09:00",
  "seats": [
    {
      "venueSeatId": 1001,
      "venueSeatGradeId": 101,
      "gradeName": "VIP",
      "basePrice": 150000
    }
  ]
}
```

policyVersion은 부모 performance.version이다.
venueSeatGradeId와 gradeName은 venue_seat의 고정 등급을 기준으로 반환한다.
basePrice는 해당 공연의 performance_grade_price에서 가져오며 같은 공연의 모든 회차에 동일하다.
요청 좌석이 공연장 소속·판매 대상인지 확인하고 예매 측 스냅샷이 최신 configVersion과 일치해야 한다.
이 API는 신규 선점 시 판매 정책과 가격 검증용이며 좌석 점유 여부를 판단하지 않는다.
업무상 판매 거절은 HTTP 200, allowed=false, reasonCode로 반환한다. 공연 미공개·취소를 포함한 사유 목록은 개별 판매 검증 명세를 따른다.
존재하지 않는 리소스·권한·잘못된 형식은 일반 HTTP 오류를 사용한다.
좌석 확보는 MVP에서 예매 측 비관적 락(SELECT ... FOR UPDATE)과 상태·소유권 검증으로 처리한다.
검증 직후 취소되는 경쟁은 이 API만으로 막을 수 없으므로 취소 버전 전파·최종 확정·보상 규칙이 별도로 필요하다.

#### 5.10 좌석 조회의 소유 경계

**GET /api/performances/{performanceId}/seat-layout — 공연 서비스 소유**
정적 구역·열·번호와 공연장 고정 등급, 공연별 등급 가격을 제공한다.
실시간 선택 가능 여부는 포함하지 않는다.
공개 정책은 최종 합의가 필요하다.

실시간 선택 가능 좌석(GET /api/schedules/{scheduleId}/seats)과 등급별 잔여석(GET /api/schedules/{scheduleId}/remaining)은 예매 서비스 소유다.
경로·권한·입장 토큰·캐시·집계 기준 등 세부 계약은 **예매·좌석 담당자 확인 필요**.

#### 판매 거절 응답의 적용 위치

공연 내부 sale-validations는 업무상 거절을 HTTP 200, allowed=false, reasonCode로 반환한다.
예매 서비스의 외부 좌석 선점 API는 그 reasonCode를 오류 code로 전달하며 HTTP 400을 사용한다.
SEAT_NOT_SELLABLE·SEAT_CONFIG_VERSION_MISMATCH는 외부 선점 API에서 HTTP 409다.
삭제·미존재 리소스는 내부에서도 기존 HTTP 404 ErrorResponse를 사용한다.
SEAT_SETUP_NOT_READY를 공연 내부 API의 일반 HTTP 400 오류로 해석하지 않는다.

#### 미확정 계약의 범위

- 5.8의 회차 취소 후속 전파는 담당자 합의가 필요하다.
- 나이 미달 선점 차단 정책은 결정됐지만 생년월일 확보·연령 기준·검증 위치 및 ageRating 전달 계약은 미확정이다.
- sale-validations의 삭제·미존재는 기존 HTTP 404 오류로 처리한다. NOT_FOUND를 HTTP 200 reasonCode로 옮기는 응답 구조는 미확정이며 기존 오류 코드를 삭제·변경하지 않는다.
- 공연·회차 삭제 전 예매 존재 확인(RESERVATION_EXISTS·503 판단)과 예매 측 좌석 정리 방식은 추후 결정한다.
- 좌석 준비 Feign 호출의 경로·DTO·결과 전달 방식·타임아웃은 예매·좌석 담당자와 확정한다.
- 좌석 준비 자동 재시도의 횟수·간격·알림과 공개 후 FAILED 회차 운영 절차는 구현 시 결정한다.
- 공연장 등급·좌석 미존재 404 코드는 정해지지 않았다(PATCH·DELETE 공통).

#### 5.11 주요 오류 코드

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_TIME_RANGE | 필드·열거값·시각 조건 오류 |
| 401 / 403 | UNAUTHENTICATED / FORBIDDEN | 인증 실패·역할/소유권 없음 |
| 404 | PERFORMANCE_NOT_FOUND / SCHEDULE_NOT_FOUND / VENUE_NOT_FOUND | 미존재·삭제·일반 조회에서 비공개 |
| 409 | VERSION_CONFLICT | 동시 수정 충돌 |
| 409 | PUBLICATION_REQUIREMENTS_NOT_MET | 공개 필수 정보 누락 |
| 409 | PERFORMANCE_CONFIGURATION_LOCKED | 공개 후 금지된 구성 변경 |
| 409 | VENUE_LAYOUT_IN_USE / DUPLICATE_SCHEDULE / DUPLICATE_VENUE_SEAT | 배치 사용 중 수정 금지·중복 제약 위반 |
| 409 | DELETE_NOT_ALLOWED / RESERVATION_EXISTS | 삭제 정책 불충족 |
| 409 | SEAT_CONFIG_VERSION_MISMATCH | 좌석 구성 버전 불일치 |
| 428 | PRECONDITION_REQUIRED | If-Match 누락 |
| 503 | DEPENDENCY_UNAVAILABLE | 예매 서비스 확인 불가.<br>삭제·판매 허용을 추정하지 않음 |
| 400 | VENUE_GRADE_MISMATCH / GRADE_PRICE_MISSING | 다른 공연장 등급 참조 또는 판매 대상 등급 가격 누락 |
| 409 | DUPLICATE_VENUE_GRADE | 공연장 내 등급명 중복 |
| 409 | VENUE_GRADE_IN_USE | 미삭제 좌석이 배정된 공연장 등급 삭제 |

#### 5.12 예매 서비스 연관 계약 — 공연 서비스와 맞닿는 범위

회차 좌석(schedule_seat)·예매(reservation) 데이터와 선택 가능 좌석·잔여석·선점·선점 해제 API는 예매 서비스 소유다.
경로·권한·DTO·오류 코드·입장 토큰·잔여석 집계 등 세부 계약은 공연 명세에 작성하지 않으며 **예매·좌석 담당자 확인 필요**다.
공연 서비스는 좌석 원본·고정 등급·공연별 가격과 회차 판매 정책을 제공하고, 아래 좌석 준비 계약·5.9 판매 검증·「판매 거절 응답의 적용 위치」를 책임진다.

**좌석 준비 계약**
MVP에서는 공연 서비스가 Feign으로 예매 서비스의 좌석 준비 내부 API를 동기 호출한다. Kafka 도입 후 ScheduleSeatSetupRequested·Completed·Failed 이벤트 방식으로 전환하며, 두 방식을 중복 실행하지 않는다.
요청에는 requestId, scheduleId, configVersion과 좌석별 venueSeatId, venueSeatGradeId, gradeName, basePrice, isSellable, section, rowLabel, seatNumber를 담는다.
같은 요청 재전송(스케줄러 자동 재시도 포함)은 같은 requestId를 쓰고, 구성 변경으로 configVersion이 올라갈 때만 새 requestId를 쓴다. 예매 서비스는 같은 requestId 재처리로 좌석을 중복 생성하지 않는다.
결과는 현재 요청의 requestId·configVersion과 일치할 때만 반영하며, 성공하면 공연 서비스가 회차를 READY, 실패하면 FAILED로 바꾼다.
호출 경로·DTO·결과 전달 방식(동기 응답 또는 결과 조회)·타임아웃·재시도 값은 **예매·좌석 담당자 확인 필요**.

**일관성 경계**
schedule은 공연 서비스 소유를 유지한다.
schedule_seat과 reservation은 예매 서비스가 같은 로컬 트랜잭션으로 변경한다.
서비스 간 판매 정책 변경·취소 전파·결제 보상은 분산 연동 계약으로 처리한다.

### DTO 공통 규칙 및 정식 필드 계약

각 API 항목의 **Request DTO / Response DTO** 절과 데이터베이스의 request·response 속성에 필드 계약을 작성했다.
위의 기존 상세 예시는 설명용 축약 예시이며, 필수 필드·중첩 구조·null 허용 여부는 각 API 항목의 DTO 표를 기준으로 한다.
이번에 구체화한 DTO는 구현 전 계약 초안이며 기존 고정 등급·공연별 가격 정책은 유지한다.

- **ID:** JSON 숫자(integer). BIGINT를 숫자로 직렬화한다.
- **integer:** 버전·수량·페이지·원화 금액. 판매 가격은 0 초과이며, 미입력은 null로 구분한다.
- **datetime:** ISO 8601 문자열, 예: 2026-12-01T19:00:00+09:00.
- **date:** YYYY-MM-DD, KST 날짜 기준.
- **필수 여부:** 필드가 존재해야 하는지다.
  nullable 여부는 타입의 |null로 별도 표시한다.
- **PATCH:** 생략은 유지.
  명시적 null은 nullable 필드만 제거 가능.
  최소 하나의 수정 필드가 필요하며 공개 필수 조건을 깨는 변경은 거절한다.
- **목록 응답:** items, page, size, totalElements.
  빈 목록은 items=[]이며 배열을 null로 반환하지 않는다.
- **성공 응답:** 외부 API는 ApiResponse(status, message, data)로 감싼다. 내부 API는 DTO를 직접 반환한다.
- **204:** 응답 본문 없음.
  {} 또는 null JSON을 반환하지 않는다.
- **DELETE 회차:** 회차 삭제는 요청 본문이 없고 If-Match로 schedule.version을 전달한다.
- **If-Match:** 요청 대상 리소스의 version을 전달한다.
  공연장 API는 venue.version, 공연 API와 공연별 가격·판매 제외는 performance.version, 회차 명령은 schedule.version이다.
  회차 등록은 If-Match를 받지 않으며, 회차 명령의 요청 본문에는 버전 필드를 두지 않는다.
- **재시도:** 좌석 준비 재시도는 서버가 스케줄러로 처리하며 판매자 재시도 API는 없다.
  최신 configVersion과 requestId는 서버가 관리한다.
- **관리 목록 필터:** 판매자 목록의 status는 DRAFT/PUBLISHED 공개 상태 필터다.
  관리자 목록은 publicationStatus와 operationStatus를 분리한다.
- **공개/관리 응답:** 공개 응답에는 sellerId·좌석 준비 내부 오류·판매 제외 내부 사유를 넣지 않는다.
- **등급/가격:** venueSeatGradeId는 공연장 고정 등급이다.
  gradePrices는 공연 공통 가격이며 회차마다 재정의하지 않는다.
- **판매 제외:** 정적 배치의 basePrice는 제외된 등급에 가격이 없으면 null일 수 있다.
  실제 판매 대상 가격과 검증 성공 응답 가격은 정수 필수다.

#### 공통 오류 Response DTO

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| status | integer | 필수 | HTTP 상태 |
| code | string | 필수 | 업무 오류 코드 |
| message | string | 필수 | 안전한 메시지 |
| path | string | 필수 | 요청 경로 |
| timestamp | datetime | 필수 | +09:00 ISO 8601 |
| errors | object[] | 선택 | 검증 오류일 때만 포함. 각 항목은 field, reason |

내부 판매 검증은 업무상 거절이면 HTTP 200의 allowed=false와 reasonCode를 사용한다.
이때 seats는 빈 배열이며 가격 정보를 제공하지 않는다.
리소스 미존재·인증·요청 형식 오류는 일반 오류 응답을 사용한다.

### 보고서 결정 반영 요약

공연장 변경은 DRAFT이며 삭제된 회차를 포함한 회차 등록 이력이 없을 때만 허용하고 기존 가격·제외 설정을 초기화한다.

회차 시작 시각 중복은 미삭제 행만 검사하며 재등록은 새 ID다.

공개 응답은 version·publicationStatus를 제외하고 관리 응답은 유지한다.
salesStatus는 CANCELED → CLOSED → BEFORE_OPEN → UNAVAILABLE → ON_SALE 순서로 계산한다.

공연 전체 취소 API는 제공하지 않는다.

동일 요청 재전송(스케줄러 자동 재시도 포함)은 같은 requestId, 구성 변경으로 configVersion이 올라간 새 시도는 새 requestId다.

좌석 준비 요청·결과에는 현재 요청의 requestId·configVersion을 사용한다.

모든 좌석이 AVAILABLE일 때만 재생성하며 만료 HOLD도 예외로 간주하지 않는다.

선점의 가격은 준비 스냅샷 → 판매 검증 → tickets.price → 결제 totalPrice 순서로 확정한다.

판매 검증 코드와 선점 오류 매핑은 개별 판매 검증 API 명세를 따른다.

## 2. 외부 API

### 공연장

#### POST /api/admin/venues — 공연장 등록

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 201
- 호출 구분: 외부
- 기능 설명: 공연장 등록
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/admin/venues
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
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

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| Location | 필수 | `/api/seller/venues/{venueId}` |

```txt
Location: /api/seller/venues/10
```

생성된 공연장의 단건 GET /api/seller/venues/{venueId}를 가리킨다. 생성 권한 MASTER는 조회 권한 SELLER / MASTER에 포함된다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 201

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
| name | string | 필수 | 공연장명. 1~100자. |
| address | string | 필수 | 주소. 1~255자. |
| region | string | 필수 | 지역. 1~20자. |
| version | integer | 필수 | 공연장 수정 버전. 0 이상. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |

```json
{
  "status": 201,
  "message": "SUCCESS",
  "data": {
    "id": 10,
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울",
    "version": 0,
    "createdAt": "2026-10-26T10:00:00+09:00",
    "updatedAt": "2026-10-26T10:00:00+09:00"
  }
}
```

##### 처리·검증 규칙

이름·주소·지역은 필수다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.
Location 응답 헤더는 Response Headers 절의 공연장 단건 GET 경로를 가리킨다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/venues",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/seller/venues — 공연장 목록 조회

- 권한·허용 호출자: SELLER / MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연장 목록 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/seller/venues
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Query**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| region | string | 선택 | 지역. 생략 시 전체. |
| keyword | string | 선택 | 검색어. 생략 시 필터 없음. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시:

```txt
/api/seller/venues?region=%EC%84%9C%EC%9A%B8&keyword=%EA%B3%B5%EC%97%B0&page=0&size=20
```

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| items | Venue[] | 필수 | 현재 페이지 공연장. |
| items[].id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "items": [
      {
        "id": 10,
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
}
```

##### 처리·검증 규칙

공연 등록 시 공연장 선택용 조회다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/venues",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/seller/venues/{venueId} — 공연장 상세 조회

- 권한·허용 호출자: SELLER / MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연장 기본 정보와 최신 version 조회.
  삭제·미존재는 404 VENUE_NOT_FOUND.

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)

##### 메서드·경로

```txt
GET /api/seller/venues/{venueId}
```

##### 헤더

Authorization: Bearer 토큰

조회에 If-Match를 요구하지 않는다. 현재 version은 응답으로 제공한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. 좌석·등급 하위 조회 API와 같은 venueId이며 1 이상의 BIGINT 기반 숫자다. |

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| ETag | 필수 | `"{version}"` |

```txt
ETag: "0"
```

응답 헤더 ETag는 응답 본문 version과 같은 값을 큰따옴표로 감싼다. 이후 공연장 좌석·등급 변경 요청의 If-Match에 이 값을 그대로 사용한다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
| name | string | 필수 | 공연장명. 1~100자. |
| address | string | 필수 | 주소. 1~255자. |
| region | string | 필수 | 지역. 1~20자. |
| version | integer | 필수 | 공연장 수정 버전. 0 이상. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 10,
    "name": "서울 공연장",
    "address": "서울특별시 중구 예시로 10",
    "region": "서울",
    "version": 0,
    "createdAt": "2026-10-26T10:00:00+09:00",
    "updatedAt": "2026-10-26T10:00:00+09:00"
  }
}
```

##### 처리·권한 규칙

- SELLER 또는 MASTER만 조회할 수 있다.
- id·name·address·region·version·createdAt·updatedAt을 ApiResponse의 data에 담는다. 공연장 등록 응답과 같은 기본 정보 DTO다.
- 삭제됐거나 없는 공연장은 404 VENUE_NOT_FOUND로 처리한다.
- 최신 version은 이후 공연장 좌석·등급 변경 요청의 If-Match에 사용한다. 조회 자체는 버전을 변경하지 않는다.
- 관리용 단건 조회이므로 응답 헤더 ETag: "{version}"을 포함한다.
- 공연장 등록 응답의 Location과 이 GET 경로는 /api/seller/venues/{venueId}로 일치한다. 생성 권한 MASTER는 이 조회 권한에도 포함된다.

##### 오류 응답

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Path ID 형식·범위 오류 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 403 | FORBIDDEN | 공연장 조회 권한이 없습니다. |
| 404 | VENUE_NOT_FOUND | 공연장이 존재하지 않거나 삭제됨 |

```json
{
  "status": 403,
  "code": "FORBIDDEN",
  "message": "공연장 조회 권한이 없습니다.",
  "path": "/api/seller/venues/10",
  "timestamp": "2026-10-27T10:00:00+09:00"
}
```

위 예시는 기존 FORBIDDEN 코드를 재사용한다.
404는 공통 명세 5.11의 기존 VENUE_NOT_FOUND를 사용하며 새 코드를 만들지 않는다.
errors는 검증 오류일 때만 포함하고, 위 권한 오류 예시에는 포함하지 않는다.

#### POST /api/admin/venues/{venueId}/seat-grades — 공연장 좌석 등급 등록

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 201
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 등급 등록
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/admin/venues/{venueId}/seat-grades
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
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

일괄 생성에서는 Location을 보내지 않는다. 생성된 ID는 응답 본문으로 확인한다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 201

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| grades | Grade[] | 필수 | 공연장 고정 등급 목록. |
| grades[].id | integer | 필수 | 공연장 고정 등급 ID. |
| grades[].name | string | 필수 | 공연장 내 유일한 등급명. 1~20자. |
| venueVersion | integer | 필수 | 공연장 최신 버전. |

```json
{
  "status": 201,
  "message": "SUCCESS",
  "data": {
    "grades": [
      {
        "id": 101,
        "name": "VIP"
      }
    ],
    "venueVersion": 1
  }
}
```

##### 처리·검증 규칙

가격 입력 없음.
공연에서 사용하기 전만 등록 가능.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.
일괄 생성 결과는 응답 본문의 ID 목록으로 식별한다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/venues/10/seat-grades",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/seller/venues/{venueId}/seat-grades — 공연장 좌석 등급 조회

- 권한·허용 호출자: SELLER / MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 등급 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/seller/venues/{venueId}/seat-grades
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| grades | Grade[] | 필수 | 공연장 고정 등급 목록. |
| grades[].id | integer | 필수 | 공연장 고정 등급 ID. |
| grades[].name | string | 필수 | 공연장 내 유일한 등급명. 1~20자. |
| venueVersion | integer | 필수 | 공연장 최신 버전. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "grades": [
      {
        "id": 101,
        "name": "VIP"
      }
    ],
    "venueVersion": 1
  }
}
```

##### 처리·검증 규칙

공연장 고정 등급 목록.
공연별 가격은 포함하지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/venues/10/seat-grades",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId} — 공연장 좌석 등급명 수정

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 등급명 수정
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId}
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |
| gradeId | integer | 필수 | 공연장 좌석 등급 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| name | string | 필수 | 새 등급명. 1~20자, 공연장 내 유일. |

```json
{
  "name": "VIP"
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| grade | Grade | 필수 | 수정된 등급. |
| grade.id | integer | 필수 | 공연장 고정 등급 ID. |
| grade.name | string | 필수 | 공연장 내 유일한 등급명. 1~20자. |
| venueVersion | integer | 필수 | 최신 공연장 버전. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "grade": {
      "id": 101,
      "name": "VIP"
    },
    "venueVersion": 2
  }
}
```

##### 처리·검증 규칙

공연에서 사용하기 전만 변경 가능.
가격이나 좌석 배정 변경 요청은 받지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/venues/10/seat-grades/101",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId} — 공연장 좌석 등급 삭제

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 204
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 등급 소프트 삭제.
  공연에서 사용하기 전이고 그 등급을 쓰는 미삭제 좌석이 없을 때만 허용.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId}
```

##### 헤더

Authorization
If-Match
If-Match에는 현재 venue.version을 큰따옴표로 감싸 전달한다. 예: If-Match: "2"
요청 본문이 없으므로 Content-Type을 보내지 않는다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |
| gradeId | integer | 필수 | 공연장 좌석 등급 ID. |

###### **Body**

요청 본문 없음.

##### Response DTO

HTTP 204, 응답 본문 없음.
{} 또는 null JSON을 반환하지 않는다.

##### 처리·검증 규칙

MASTER만 호출할 수 있다.
소프트 삭제로 처리하며 등급 행에 deleted_at을 기록한다.
성공 시 venue.version을 1 증가시킨다.
공연이 해당 공연장을 참조한 뒤에는 409 VENUE_LAYOUT_IN_USE로 삭제를 차단한다.
그 등급을 쓰는 미삭제 좌석이 있으면 409 VENUE_GRADE_IN_USE로 거절한다. 좌석을 먼저 삭제하거나 다른 등급으로 바꾼 뒤 삭제한다.
공연장 또는 대상 등급이 없거나 삭제됐으면 404이며, 코드는 공연장 좌석 등급명 수정(PATCH) API와 같은 코드를 사용한다. 새 404 코드를 만들지 않는다.
If-Match 누락은 428 PRECONDITION_REQUIRED, 불일치는 409 VERSION_CONFLICT다.

##### 오류 응답 DTO

```json
{
  "status": 409,
  "code": "VENUE_GRADE_IN_USE",
  "message": "좌석에서 사용 중인 등급은 삭제할 수 없습니다.",
  "path": "/api/admin/venues/10/seat-grades/101",
  "timestamp": "2026-11-01T14:00:00+09:00"
}
```

errors는 검증 오류일 때만 포함하고, 위 업무 오류 예시에는 포함하지 않는다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### POST /api/admin/venues/{venueId}/seats — 공연장 좌석 등록

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 201
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 등록
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/admin/venues/{venueId}/seats
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| seats | SeatInput[] | 필수 | 1개 이상. 동일 구역·열·번호 중복 불가. |
| seats[].section | string | 필수 | 구역. 1~20자. |
| seats[].rowLabel | string | 필수 | 열. 1~10자. |
| seats[].seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seats[].venueSeatGradeId | integer | 필수 | 동일 공연장의 고정 등급 ID. |

```json
{
  "seats": [
    {
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": 101
    }
  ]
}
```

일괄 생성에서는 Location을 보내지 않는다. 생성된 ID는 응답 본문으로 확인한다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 201

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| createdCount | integer | 필수 | 생성한 좌석 수. |
| createdSeatIds | integer[] | 필수 | 이번 요청으로 생성한 물리 좌석 ID 목록. 중복 없이 모든 생성 ID를 반환하며 길이는 createdCount와 같다. |
| venueVersion | integer | 필수 | 최신 공연장 버전. |

```json
{
  "status": 201,
  "message": "SUCCESS",
  "data": {
    "createdCount": 1,
    "venueVersion": 2,
    "createdSeatIds": [
      1001
    ]
  }
}
```

##### 처리·검증 규칙

모든 등급은 같은 공연장 소속이어야 한다. createdSeatIds는 이번 요청에서 생성한 좌석만 포함하고 createdCount는 그 배열 길이와 같다.
공연이 참조한 배치에는 추가할 수 없다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.
일괄 생성 결과는 응답 본문의 ID 목록으로 식별한다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/venues/10/seats",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/seller/venues/{venueId}/seats — 공연장 좌석 조회

- 권한·허용 호출자: SELLER / MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/seller/venues/{venueId}/seats
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |
| venueVersion | integer | 필수 | 공연장 버전. |
| seats | Seat[] | 필수 | 물리 좌석과 고정 등급. 판매 상태 없음. |
| seats[].id | integer | 필수 | 물리 좌석 ID. |
| seats[].section | string | 필수 | 구역. 1~20자. |
| seats[].rowLabel | string | 필수 | 열. 1~10자. |
| seats[].seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seats[].venueSeatGradeId | integer | 필수 | 동일 공연장의 고정 등급 ID. |
| seats[].gradeName | string | 필수 | 고정 등급명. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "venueId": 10,
    "venueVersion": 2,
    "seats": [
      {
        "id": 1001,
        "section": "A",
        "rowLabel": "1",
        "seatNumber": 1,
        "venueSeatGradeId": 101,
        "gradeName": "VIP"
      }
    ]
  }
}
```

##### 처리·검증 규칙

선점·판매 상태는 포함하지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/venues/10/seats",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### PATCH /api/admin/venues/{venueId}/seats/{seatId} — 공연장 좌석 정보 수정

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연장 좌석 정보 수정
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PATCH /api/admin/venues/{venueId}/seats/{seatId}
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |
| seatId | integer | 필수 | 공연장 물리 좌석 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| section | string | 선택 | 구역. 1~20자. |
| rowLabel | string | 선택 | 열. 1~10자. |
| seatNumber | integer | 선택 | 좌석 번호. 1 이상. |
| venueSeatGradeId | integer | 선택 | 동일 공연장의 고정 등급 ID. |

```json
{
  "section": "A",
  "rowLabel": "1",
  "seatNumber": 1,
  "venueSeatGradeId": 101
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| seat | Seat | 필수 | 수정한 물리 좌석. |
| seat.id | integer | 필수 | 물리 좌석 ID. |
| seat.section | string | 필수 | 구역. 1~20자. |
| seat.rowLabel | string | 필수 | 열. 1~10자. |
| seat.seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seat.venueSeatGradeId | integer | 필수 | 동일 공연장의 고정 등급 ID. |
| seat.gradeName | string | 필수 | 고정 등급명. |
| venueVersion | integer | 필수 | 최신 공연장 버전. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "seat": {
      "id": 1001,
      "section": "A",
      "rowLabel": "1",
      "seatNumber": 1,
      "venueSeatGradeId": 101,
      "gradeName": "VIP"
    },
    "venueVersion": 3
  }
}
```

##### 처리·검증 규칙

최소 1개 필드 필수.
공연에서 사용하기 전만 수정.
같은 공연장 등급만 지정 가능.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/venues/10/seats/1001",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### DELETE /api/admin/venues/{venueId}/seats/{seatId} — 공연장 좌석 삭제

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 204
- 호출 구분: 외부
- 기능 설명: 공연장 물리 좌석 소프트 삭제.
  공연에서 사용하기 전만 허용.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
DELETE /api/admin/venues/{venueId}/seats/{seatId}
```

##### 헤더

Authorization
If-Match
If-Match에는 현재 venue.version을 큰따옴표로 감싸 전달한다. 예: If-Match: "3"
요청 본문이 없으므로 Content-Type을 보내지 않는다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 공연장 ID. |
| seatId | integer | 필수 | 공연장 물리 좌석 ID. |

###### **Body**

요청 본문 없음.

##### Response DTO

HTTP 204, 응답 본문 없음.
{} 또는 null JSON을 반환하지 않는다.

##### 처리·검증 규칙

MASTER만 호출할 수 있다.
소프트 삭제로 처리하며 좌석 행에 deleted_at을 기록한다.
성공 시 venue.version을 1 증가시킨다.
공연이 해당 공연장을 참조한 뒤에는 409 VENUE_LAYOUT_IN_USE로 삭제를 차단한다.
공연장 또는 대상 좌석이 없거나 삭제됐으면 404이며, 코드는 공연장 좌석 정보 수정(PATCH) API와 같은 코드를 사용한다. 새 404 코드를 만들지 않는다.
If-Match 누락은 428 PRECONDITION_REQUIRED, 불일치는 409 VERSION_CONFLICT다.

##### 오류 응답 DTO

```json
{
  "status": 409,
  "code": "VENUE_LAYOUT_IN_USE",
  "message": "공연에서 사용 중인 공연장 배치는 변경할 수 없습니다.",
  "path": "/api/admin/venues/10/seats/1001",
  "timestamp": "2026-11-01T14:00:00+09:00"
}
```

errors는 검증 오류일 때만 포함하고, 위 업무 오류 예시에는 포함하지 않는다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

### 공연 (판매자)

#### POST /api/seller/performances — 공연 초안 등록

- 권한·허용 호출자: SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 201
- 호출 구분: 외부
- 기능 설명: 공연 초안 등록
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/seller/performances
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 필수 | 미삭제 공연장 ID. |
| title | string | 필수 | 공연명. 1~200자. |
| genre | string | 필수 | CONCERT / MUSICAL / PLAY / SPORTS. |
| ageRating | string | 필수 | ALL / 12 / 15 / 19. |
| runningTime | integer | 필수 | 공연 시간(분). 1 이상. |
| castInfo | string\|null | 선택 | 최대 500자. 생략하면 null. |
| description | string\|null | 선택 | 초안에서는 생략 가능. 공개 조건은 별도 검증. |
| posterUrl | string\|null | 선택 | 최대 500자 HTTPS URL. 초안에서는 생략 가능. |

```json
{
  "venueId": 10,
  "title": "공연 A 서울",
  "genre": "MUSICAL",
  "ageRating": "12",
  "runningTime": 120,
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg"
}
```

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| Location | 필수 | `/api/seller/performances/{performanceId}` |

```txt
Location: /api/seller/performances/20
```

생성된 초안을 판매자가 조회하는 관리 상세 URI다. 공개 상세 URI와 구분한다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 201

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| sellerId | integer | 필수 | 인증 주체의 판매자 ID. 클라이언트 입력 금지. |

```json
{
  "status": 201,
  "message": "SUCCESS",
  "data": {
    "id": 20,
    "venue": {
      "id": 10,
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
    "sellerId": 1001
  }
}
```

##### 처리·검증 규칙

sellerId는 인증 주체에서 설정한다.
공연장은 좌석·등급 등록이 끝난 곳을 선택한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.
Location 응답 헤더는 Response Headers 절의 판매자용 공연 단건 GET 경로를 가리킨다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/seller/performances — 내 공연 목록 조회

- 권한·허용 호출자: SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 내 공연 목록 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/seller/performances
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Query**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| status | string | 선택 | 공개 상태 필터 DRAFT / PUBLISHED. 생략 시 전체. |
| keyword | string | 선택 | 검색어. 생략 시 필터 없음. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시:

```txt
/api/seller/performances?status=DRAFT&keyword=%EA%B3%B5%EC%97%B0&page=0&size=20
```

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| items | PerformanceManageSummary[] | 필수 | 관리용 공연 목록. |
| items[].id | integer | 필수 | 공연 상품 ID. |
| items[].venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| items[].venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| items[].version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| items[].sellerId | integer | 필수 | 판매자 ID. |
| page | integer | 필수 | 0부터 시작. |
| size | integer | 필수 | 1~100. |
| totalElements | integer | 필수 | 전체 건수. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "items": [
      {
        "id": 20,
        "venue": {
          "id": 10,
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
        "sellerId": 1001
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1
  }
}
```

##### 처리·검증 규칙

인증된 판매자 본인 공연만 조회한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/seller/performances/{performanceId} — 내 공연 상세 조회

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 내 공연 상세 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/seller/performances/{performanceId}
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| ETag | 필수 | `"{version}"` |

```txt
ETag: "3"
```

현재 공연 version이다. 공연 단위 명령(공연 정보 수정·삭제·공개·등급 가격·판매 제외)의 If-Match에 사용한다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| sellerId | integer | 필수 | 인증 주체의 판매자 ID. 클라이언트 입력 금지. |
| gradePrices | GradePrice[] | 필수 | 공연별 등급 가격. |
| gradePrices[].venueSeatGradeId | integer | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 초과. |
| excludedSeats | ExcludedSeat[] | 필수 | 공연 공통 판매 제외 좌석. |
| excludedSeats[].venueSeatId | integer | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |
| schedules | ScheduleManage[] | 필수 | 회차별 관리 정보. |
| schedules[].id | integer | 필수 | 회차 ID. |
| schedules[].performanceId | integer | 필수 | 공연 ID. |
| schedules[].startAt | datetime | 필수 | 회차 시작 시각. |
| schedules[].bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| schedules[].bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| schedules[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| schedules[].seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| schedules[].salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| schedules[].salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| schedules[].canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| schedules[].seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| schedules[].seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| schedules[].seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| schedules[].suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| schedules[].cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| schedules[].version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 20,
    "venue": {
      "id": 10,
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
    "sellerId": 1001,
    "gradePrices": [
      {
        "venueSeatGradeId": 101,
        "gradeName": "VIP",
        "price": 150000
      }
    ],
    "excludedSeats": [
      {
        "venueSeatId": 1002,
        "reason": "운영석"
      }
    ],
    "schedules": [
      {
        "id": 201,
        "performanceId": 20,
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
        "version": 0
      }
    ]
  }
}
```

##### 처리·검증 규칙

판매자 본인 데이터만 조회하며 초안·관리 상태를 포함한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### salesStatus 계산 규칙

별도 DB 컬럼을 두지 않고 조회·응답 시 계산한다.

1. 공연 또는 회차 취소: CANCELED
2. 현재 시각 >= bookingCloseAt: CLOSED
3. 현재 시각 < bookingOpenAt: BEFORE_OPEN
4. 판매 기간 중 salesSuspended=true 또는 seatSetupStatus!=READY: UNAVAILABLE
5. 나머지 판매 가능 조건 충족: ON_SALE

매진·여유·임박은 이 상태에 포함하지 않는다. 관리 화면의 상세 원인은 seatSetupStatus·salesSuspended로 확인한다.

#### PATCH /api/seller/performances/{performanceId} — 공연 정보 수정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 정보 수정
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PATCH /api/seller/performances/{performanceId}
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| venueId | integer | 선택 | 미삭제 공연장 ID. 공연이 DRAFT이며 삭제된 회차를 포함한 회차 등록 이력이 없을 때만 변경 가능. |
| title | string | 선택 | 공연명. 1~200자. DRAFT에서 변경 가능. |
| genre | string | 선택 | CONCERT / MUSICAL / PLAY / SPORTS. DRAFT에서 변경 가능. |
| ageRating | string | 선택 | ALL / 12 / 15 / 19. DRAFT에서 변경 가능. |
| runningTime | integer | 선택 | 공연 시간(분). 1 이상. DRAFT에서 변경 가능. |
| castInfo | string\|null | 선택 | 최대 500자. 생략하면 기존 값을 유지하고 명시적 null이면 제거한다. DRAFT·PUBLISHED 모두 수정 가능하다. |
| description | string\|null | 선택 | 생략하면 기존 값을 유지한다. DRAFT·PUBLISHED 모두 수정 가능하다. 명시적 null은 공개 필수 요건을 깨지 않는 경우에만 허용한다. |
| posterUrl | string\|null | 선택 | 최대 500자 HTTPS URL. 생략하면 기존 값을 유지한다. DRAFT·PUBLISHED 모두 수정 가능하다. 명시적 null은 공개 필수 요건을 깨지 않는 경우에만 허용한다. |

```json
{
  "castInfo": "배우 A, 배우 B",
  "description": "공연 상세 안내",
  "posterUrl": "https://example.com/poster.jpg"
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| sellerId | integer | 필수 | 인증 주체의 판매자 ID. 클라이언트 입력 금지. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 20,
    "venue": {
      "id": 10,
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
    "sellerId": 1001
  }
}
```

##### 처리·검증 규칙

최소 1개 필드 필수.
공개 후에는 description, posterUrl, castInfo만 허용한다.
필드 생략은 유지, nullable 필드의 명시적 null은 제거 의미다.
공개 필수 요건을 깨는 null 수정은 거절한다.
If-Match는 현재 performance.version이며, 성공 시 performance.version이 증가한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### 공연장 변경 확정 정책

공연의 publicationStatus가 DRAFT이고 회차 등록 이력이 전혀 없을 때만 venueId를 변경한다.

삭제된 회차도 등록 이력에 포함한다.

판매자 소유권과 새 공연장을 검증한 뒤 기존 등급별 가격·판매 제외 설정을 초기화하고 venueId 및 공연 version을 같은 트랜잭션에서 변경한다.

#### PUT /api/seller/performances/{performanceId}/grade-prices — 공연별 등급 가격 설정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연별 등급 가격 설정
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PUT /api/seller/performances/{performanceId}/grade-prices
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| gradePrices | PriceInput[] | 필수 | 전체 가격 집합. 등급 ID 중복 불가. |
| gradePrices[].venueSeatGradeId | integer | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 초과. |

```json
{
  "gradePrices": [
    {
      "venueSeatGradeId": 101,
      "price": 150000
    }
  ]
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| gradePrices | GradePrice[] | 필수 | 가격 집합. |
| gradePrices[].venueSeatGradeId | integer | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 초과. |
| performanceVersion | integer | 필수 | 변경 후 performance.version. 다음 공연 단위 명령의 If-Match에 사용한다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "gradePrices": [
      {
        "venueSeatGradeId": 101,
        "gradeName": "VIP",
        "price": 150000
      }
    ],
    "performanceVersion": 4
  }
}
```

##### 처리·검증 규칙

DRAFT에서만 가능하다.
판매 대상 등급의 가격은 모두 필요하다.
회차별 가격·좌석별 가격은 받지 않는다.
If-Match는 현재 performance.version이며, 성공 시 performance.version이 증가한다.
등록된 회차가 있으면 회차의 configVersion을 올리고 PENDING으로 바꾼 뒤, 트랜잭션 커밋 후 새 requestId로 좌석 준비를 다시 요청한다. FAILED 회차도 이 변경으로 다시 준비하며 별도 재시도 API는 없다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20/grade-prices",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### PUT /api/seller/performances/{performanceId}/seat-exclusions — 공연 공통 판매 제외 설정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 공통 판매 제외 설정
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PUT /api/seller/performances/{performanceId}/seat-exclusions
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| excludedSeats | ExcludedSeat[] | 필수 | 공연 공통 전체 제외 집합. 빈 배열은 제외 없음. |
| excludedSeats[].venueSeatId | integer | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |

```json
{
  "excludedSeats": [
    {
      "venueSeatId": 1002,
      "reason": "운영석"
    }
  ]
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| excludedSeats | ExcludedSeat[] | 필수 | 제외 좌석 집합. 없으면 빈 배열. |
| excludedSeats[].venueSeatId | integer | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |
| performanceVersion | integer | 필수 | 변경 후 performance.version. 다음 공연 단위 명령의 If-Match에 사용한다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "excludedSeats": [
      {
        "venueSeatId": 1002,
        "reason": "운영석"
      }
    ],
    "performanceVersion": 4
  }
}
```

##### 처리·검증 규칙

DRAFT에서만 변경.
모든 회차에 동일하게 적용.
등급·가격 덮어쓰기 금지.
If-Match는 현재 performance.version이며, 성공 시 performance.version이 증가한다.
등록된 회차가 있으면 회차의 configVersion을 올리고 PENDING으로 바꾼 뒤, 트랜잭션 커밋 후 새 requestId로 좌석 준비를 다시 요청한다. FAILED 회차도 이 변경으로 다시 준비하며 별도 재시도 API는 없다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20/seat-exclusions",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### POST /api/seller/performances/{performanceId}/publication — 공연 공개

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 공개
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/seller/performances/{performanceId}/publication
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| sellerId | integer | 필수 | 인증 주체의 판매자 ID. 클라이언트 입력 금지. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 20,
    "venue": {
      "id": 10,
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
    "sellerId": 1001
  }
}
```

##### 처리·검증 규칙

필수 공연 정보·활성 회차·판매 대상 등급 가격을 검증한다.
좌석 준비 중이어도 공개할 수 있다.
If-Match는 현재 performance.version이다. 공개에 성공하면 publishedAt을 기록하고 performance.version을 올린다.
멱등하다. 이미 PUBLISHED인 공연에 다시 요청하면 If-Match 일치 여부와 관계없이 현재 공개 결과를 200으로 반환하고 최초 publishedAt을 보존한다. 응답 유실 후 재요청이 409가 되지 않게 하기 위함이다.
되돌릴 수 없는 전환이므로 POST 명사 하위 리소스로 둔다. DRAFT 복귀 API는 없다.
공개 검증과 회차 변경의 경쟁은 서버가 공연 행을 잠그고(SELECT ... FOR UPDATE) 막는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20/publication",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### DELETE /api/seller/performances/{performanceId} — 공연 삭제

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 204
- 호출 구분: 외부
- 기능 설명: 공연 삭제
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
DELETE /api/seller/performances/{performanceId}
```

##### 헤더

Authorization
If-Match
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

HTTP 204

응답 본문 없음.
204에서는 {} 또는 null JSON을 반환하지 않는다.

##### 처리·검증 규칙

DRAFT·오픈 전·선점 및 예매 0건·예매 측 정리 가능 확인 후 소프트 삭제.
응답 본문은 없다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

### 회차 (판매자)

#### POST /api/seller/performances/{performanceId}/schedules — 공연 회차 등록

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 201
- 호출 구분: 외부
- 기능 설명: 공연 회차 등록
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/seller/performances/{performanceId}/schedules
```

##### 헤더

Authorization
회차 등록은 If-Match를 받지 않는다. 서버가 같은 트랜잭션에서 부모 공연 행을 잠그고(SELECT ... FOR UPDATE) 공연 상태를 확인한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. 회차가 속한 부모 공연의 식별자. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
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

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| Location | 필수 | `/api/seller/schedules/{scheduleId}` |

```txt
Location: /api/seller/schedules/201
```

생성된 회차의 판매자용 관리 조회 URI다. 본인 판매자는 DRAFT 공연의 회차와 취소된 회차도 GET /api/seller/schedules/{scheduleId}로 조회할 수 있다. 이 scheduleId는 생성된 회차 ID다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 201

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 회차 ID. |
| performanceId | integer | 필수 | 공연 ID. |
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. |

```json
{
  "status": 201,
  "message": "SUCCESS",
  "data": {
    "id": 201,
    "performanceId": 20,
    "startAt": "2026-12-01T19:00:00+09:00",
    "bookingOpenAt": "2026-11-01T14:00:00+09:00",
    "bookingCloseAt": "2026-12-01T18:00:00+09:00",
    "operationStatus": "ACTIVE",
    "seatSetupStatus": "PENDING",
    "salesSuspended": false,
    "salesStatus": "BEFORE_OPEN",
    "canceledAt": null,
    "seatConfigVersion": 1,
    "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
    "seatSetupError": null,
    "suspensionReason": null,
    "cancelReason": null,
    "version": 0
  }
}
```

##### 처리·검증 규칙

공연이 DRAFT 또는 PUBLISHED이고 취소되지 않았다면 회차 추가가 가능하다.
오픈 < 마감 <= 시작.
등급·가격·좌석배정은 서버가 공연장 원본과 공연 공통 설정에서 가져온다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
회차 등록은 If-Match를 받지 않는다. 서버가 같은 트랜잭션에서 부모 공연 행을 잠그고(SELECT ... FOR UPDATE) 공연 상태를 확인한다. 공개 검증과 회차 변경의 경쟁은 이 행 잠금으로 막는다.
업무 오류의 상세 코드는 공통 명세를 따른다.
Location 응답 헤더는 Response Headers 절의 판매자용 회차 단건 GET 경로를 가리킨다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/performances/20/schedules",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### 회차 추가·중복 확정 정책

공연의 기존 공연장·고정 등급·공통 가격·제외 정책을 그대로 사용한다.

새 회차는 PENDING으로 생성하고 READY이면서 판매 기간인 경우에만 신규 선점을 허용한다.

기존 회차·예매는 바꾸지 않는다.

중복 시작 시각은 미삭제 회차 사이에서만 검사한다.

삭제한 회차와 같은 시각에 등록하면 복원하지 않고 새 scheduleId를 발급한다.

##### 좌석 준비 처리

새 회차는 seatSetupStatus=PENDING으로 생성한다. 트랜잭션 커밋 후 공연 서비스가 Feign으로 예매 서비스의 좌석 준비 내부 API를 호출한다. 호출을 트랜잭션 안에서 하지 않는다.

성공하면 READY로 바꾼다.

일시적 실패(타임아웃·5xx·연결 실패)는 PENDING을 유지하고, 스케줄러가 seat_setup_next_attempt_at이 지난 PENDING 회차를 같은 requestId로 다시 보낸다. 예매 서비스는 같은 requestId 재처리로 좌석을 중복 생성하지 않는다(좌석 담당 합의 필요).

최대 재시도 횟수를 넘기면 FAILED로 바꾸고 운영 알림을 남긴다. 요청 데이터 오류(4xx 검증 실패)는 재시도하지 않고 바로 FAILED로 바꾸며 seat_setup_error에 안전한 요약을 남긴다.

FAILED 복구: DRAFT 공연은 판매자가 기존 구성 변경 API(등급 가격 설정, 판매 제외 설정, 공연 정보 수정의 공연장 변경)로 원인을 고치면 configVersion이 올라가고 새 requestId로 다시 준비한다. 판매자용 재시도 API는 없다.

공개 후 FAILED 회차의 운영 절차와 재시도 횟수·간격·알림 방식은 구현 시 결정한다(미확정).

판매자는 회차 조회 응답의 seatSetupStatus·seatSetupError로 상태를 확인한다.

##### salesStatus 계산 규칙

별도 DB 컬럼을 두지 않고 조회·응답 시 계산한다.

1. 공연 또는 회차 취소: CANCELED
2. 현재 시각 >= bookingCloseAt: CLOSED
3. 현재 시각 < bookingOpenAt: BEFORE_OPEN
4. 판매 기간 중 salesSuspended=true 또는 seatSetupStatus!=READY: UNAVAILABLE
5. 나머지 판매 가능 조건 충족: ON_SALE

매진·여유·임박은 이 상태에 포함하지 않는다. 관리 화면의 상세 원인은 seatSetupStatus·salesSuspended로 확인한다.

#### GET /api/seller/schedules/{scheduleId} — 판매자 공연 회차 상세 조회

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 본인 공연의 회차를 관리용 DTO로 조회한다.
  DRAFT·취소 회차도 조회 가능.
  회차 또는 부모 공연의 삭제·미존재는 404 SCHEDULE_NOT_FOUND.
  조회는 If-Match를 요구하지 않는다. 응답 헤더 ETag로 현재 회차 version을 제공한다.

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)

##### 메서드·경로

```txt
GET /api/seller/schedules/{scheduleId}
```

##### 헤더

Authorization: Bearer 토큰

조회는 If-Match를 요구하지 않는다.
조회는 회차·공연의 version을 변경하지 않는다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 회차 ID. 기존 PATCH·DELETE /api/seller/schedules/{scheduleId}와 같은 리소스다. 1 이상의 BIGINT 기반 JSON 숫자를 사용한다. |

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| ETag | 필수 | `"{version}"` |

```txt
ETag: "0"
```

현재 회차 version을 큰따옴표로 감싼 값이다. 회차 명령(수정·삭제·취소·판매 일시 중지·재개)의 If-Match에 그대로 사용한다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 회차 ID. |
| performanceId | integer | 필수 | 공연 ID. |
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| seatSetupStatus | string | 필수 | PENDING / READY / FAILED. PENDING이면 서버가 좌석 준비를 자동으로 재시도한다. FAILED이면 seatSetupError로 원인을 확인하고 기존 구성 변경 API로 복구한다. 판매자용 재시도 API는 없다. 사용자에게 내부 오류는 노출하지 않음. |
| salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. 조회는 버전을 변경하지 않는다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 201,
    "performanceId": 20,
    "startAt": "2026-12-01T19:00:00+09:00",
    "bookingOpenAt": "2026-11-01T14:00:00+09:00",
    "bookingCloseAt": "2026-12-01T18:00:00+09:00",
    "operationStatus": "ACTIVE",
    "seatSetupStatus": "PENDING",
    "salesSuspended": false,
    "salesStatus": "BEFORE_OPEN",
    "canceledAt": null,
    "seatConfigVersion": 1,
    "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
    "seatSetupError": null,
    "suspensionReason": null,
    "cancelReason": null,
    "version": 0
  }
}
```

##### 처리·권한 규칙

1. 기존 판매자 API와 같이 SELLER 역할 및 부모 공연의 sellerId를 인증 주체와 비교한다. 소유권이 없으면 기존 공통 소유권 오류인 403 FORBIDDEN으로 처리한다.
2. 본인 공연이면 DRAFT·PUBLISHED 모두 조회할 수 있다. 공연 또는 회차가 취소된 경우에도 관리 목적으로 조회한다.
3. 회차 또는 부모 공연이 없거나 삭제됐으면 404 SCHEDULE_NOT_FOUND다. 요청 대상이 회차이므로 부모 공연이 삭제된 경우에도 같은 코드를 사용하며 삭제 여부는 구분해 노출하지 않는다.
4. 회차 등록·수정과 같은 관리용 회차 DTO를 사용한다. 시간·판매 기간·salesStatus·좌석 준비 상태·요청 ID·판매 중지 사유·현재 회차 version을 포함한다. 같은 version을 응답 헤더 ETag로도 반환한다.
5. salesStatus는 공통 명세의 CANCELED → CLOSED → BEFORE_OPEN → UNAVAILABLE → ON_SALE 우선순위로 계산한다. 매진 여부와 구분한다.
6. 필드가 null이면 해당 필드는 유지하고 값만 null로 반환한다. null 허용은 등록·수정 응답과 같다.
7. 좌석 준비 내부 요약은 본인 판매자에게 안전한 정보만 제공한다. 원시 예외·민감한 내부 정보를 노출하지 않는다. 판매자는 seatSetupStatus·seatSetupError로 좌석 준비 상태를 확인한다.

##### 오류 응답

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Path ID 형식·범위 오류 |
| 401 | UNAUTHENTICATED | 인증 실패 |
| 403 | FORBIDDEN | 본인 공연의 회차만 조회할 수 있습니다. |
| 404 | SCHEDULE_NOT_FOUND | 회차 또는 부모 공연이 존재하지 않거나 삭제됨 |

```json
{
  "status": 403,
  "code": "FORBIDDEN",
  "message": "본인 공연의 회차만 조회할 수 있습니다.",
  "path": "/api/seller/schedules/201",
  "timestamp": "2026-10-27T10:00:00+09:00"
}
```

위 예시는 기존 FORBIDDEN 코드를 재사용한다.
404는 공통 명세 5.11의 기존 SCHEDULE_NOT_FOUND를 사용하며 새 코드를 만들지 않는다.
errors는 검증 오류일 때만 포함하고, 위 권한 오류 예시에는 포함하지 않는다.

#### PATCH /api/seller/schedules/{scheduleId} — 공연 회차 수정

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 회차 수정
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PATCH /api/seller/schedules/{scheduleId}
```

##### 헤더

Authorization
If-Match
If-Match는 회차의 현재 version(schedule.version)을 큰따옴표로 감싸 전달한다. 예: If-Match: "0"
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 공연 회차 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| startAt | datetime | 선택 | 회차 시작 시각. |
| bookingOpenAt | datetime | 선택 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 선택 | 예매 마감 시각. |

```json
{
  "startAt": "2026-12-01T19:00:00+09:00",
  "bookingOpenAt": "2026-11-01T14:00:00+09:00",
  "bookingCloseAt": "2026-12-01T18:00:00+09:00"
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 회차 ID. |
| performanceId | integer | 필수 | 공연 ID. |
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 201,
    "performanceId": 20,
    "startAt": "2026-12-01T19:00:00+09:00",
    "bookingOpenAt": "2026-11-01T14:00:00+09:00",
    "bookingCloseAt": "2026-12-01T18:00:00+09:00",
    "operationStatus": "ACTIVE",
    "seatSetupStatus": "PENDING",
    "salesSuspended": false,
    "salesStatus": "BEFORE_OPEN",
    "canceledAt": null,
    "seatConfigVersion": 1,
    "seatSetupRequestId": "3ec890be-7702-44cc-bbfd-f01e913e8a46",
    "seatSetupError": null,
    "suspensionReason": null,
    "cancelReason": null,
    "version": 1
  }
}
```

##### 처리·검증 규칙

DRAFT에서만 가능하다.
시간 필드 최소 1개 필수.
If-Match는 회차 버전(schedule.version)이다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/schedules/201",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### 수정 성공 시 버전

회차 수정 성공 시 schedule.version만 1 증가한다. 부모 performance.version은 바꾸지 않는다.
If-Match="0"인 예시는 수정 후 version=1을 반환한다.
단순 조회나 실패한 변경은 버전을 증가시키지 않는다.

##### 시간 변경과 좌석 준비

schedule_seat에는 시간 스냅샷이 없으므로 시간만 변경할 때 좌석을 재생성하거나 seatConfigVersion을 올리지 않는다.

공개 후 기존 회차 수정 제한은 그대로 유지한다. 공개 후 새 회차 추가 허용과 구분한다.

##### salesStatus 계산 규칙

별도 DB 컬럼을 두지 않고 조회·응답 시 계산한다.

1. 공연 또는 회차 취소: CANCELED
2. 현재 시각 >= bookingCloseAt: CLOSED
3. 현재 시각 < bookingOpenAt: BEFORE_OPEN
4. 판매 기간 중 salesSuspended=true 또는 seatSetupStatus!=READY: UNAVAILABLE
5. 나머지 판매 가능 조건 충족: ON_SALE

매진·여유·임박은 이 상태에 포함하지 않는다. 관리 화면의 상세 원인은 seatSetupStatus·salesSuspended로 확인한다.

#### DELETE /api/seller/schedules/{scheduleId} — 공연 회차 삭제

- 권한·허용 호출자: 소유 SELLER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 204
- 호출 구분: 외부
- 기능 설명: 공연 회차 삭제
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
DELETE /api/seller/schedules/{scheduleId}
```

##### 헤더

Authorization
If-Match
If-Match는 회차의 현재 version(schedule.version)을 큰따옴표로 감싸 전달한다. 예: If-Match: "0"
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 공연 회차 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

HTTP 204

응답 본문 없음.
204에서는 {} 또는 null JSON을 반환하지 않는다.

##### 처리·검증 규칙

요청 본문은 없다.
If-Match는 회차 버전(schedule.version)이다.
삭제 성공 시 schedule.version만 1 증가한다. 부모 performance.version은 바꾸지 않는다.
삭제 조건은 공연 삭제와 동일하며 응답 본문은 없다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/seller/schedules/201",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

### 관리자

#### GET /api/admin/performances — 전체 공연 목록 조회

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 전체 공연 목록 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/admin/performances
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Query**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| sellerId | integer | 선택 | 판매자 필터. |
| publicationStatus | string | 선택 | DRAFT / PUBLISHED. |
| operationStatus | string | 선택 | ACTIVE / CANCELED. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시:

```txt
/api/admin/performances?sellerId=1001&publicationStatus=DRAFT&operationStatus=ACTIVE&page=0&size=20
```

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| items | PerformanceManageSummary[] | 필수 | 관리용 공연 목록. |
| items[].id | integer | 필수 | 공연 상품 ID. |
| items[].venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| items[].venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| items[].version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| items[].sellerId | integer | 필수 | 판매자 ID. |
| page | integer | 필수 | 0부터 시작. |
| size | integer | 필수 | 1~100. |
| totalElements | integer | 필수 | 전체 건수. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "items": [
      {
        "id": 20,
        "venue": {
          "id": 10,
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
        "sellerId": 1001
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1
  }
}
```

##### 처리·검증 규칙

관리자는 전체 판매자 공연을 조회할 수 있다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/performances",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/admin/performances/{performanceId} — 관리자 공연 상세 조회

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 관리자 공연 상세 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/admin/performances/{performanceId}
```

##### 헤더

Authorization
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response Headers

| 헤더 | 필수 | 값 |
| --- | --- | --- |
| ETag | 필수 | `"{version}"` |

```txt
ETag: "3"
```

현재 공연 version이다. 공연 단위 명령의 If-Match 기준값과 같다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| version | integer | 필수 | 공연 version. 공연 정보·공연장·등급 가격·판매 제외·공개 상태가 바뀔 때 증가하며 회차 변경으로는 증가하지 않는다. 공연 단위 명령의 If-Match 기준. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| sellerId | integer | 필수 | 인증 주체의 판매자 ID. 클라이언트 입력 금지. |
| gradePrices | GradePrice[] | 필수 | 공연별 등급 가격. |
| gradePrices[].venueSeatGradeId | integer | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 초과. |
| excludedSeats | ExcludedSeat[] | 필수 | 공연 공통 판매 제외 좌석. |
| excludedSeats[].venueSeatId | integer | 필수 | 공연장 물리 좌석 ID. |
| excludedSeats[].reason | string | 필수 | 제외 사유. 1~255자. 관리 응답 전용. |
| schedules | ScheduleManage[] | 필수 | 회차별 관리 정보. |
| schedules[].id | integer | 필수 | 회차 ID. |
| schedules[].performanceId | integer | 필수 | 공연 ID. |
| schedules[].startAt | datetime | 필수 | 회차 시작 시각. |
| schedules[].bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| schedules[].bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| schedules[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| schedules[].seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| schedules[].salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| schedules[].salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| schedules[].canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |
| schedules[].seatConfigVersion | integer | 필수 | 예매 좌석 구성 버전. 1 이상. |
| schedules[].seatSetupRequestId | string | 필수 | 현재 준비 요청 상관관계 ID. |
| schedules[].seatSetupError | string\|null | 필수 | 안전한 준비 실패 요약. 없으면 null. |
| schedules[].suspensionReason | string\|null | 필수 | 판매 중지 사유. 없으면 null. |
| schedules[].cancelReason | string\|null | 필수 | 취소 사유. 없으면 null. |
| schedules[].version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 20,
    "venue": {
      "id": 10,
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
    "sellerId": 1001,
    "gradePrices": [
      {
        "venueSeatGradeId": 101,
        "gradeName": "VIP",
        "price": 150000
      }
    ],
    "excludedSeats": [
      {
        "venueSeatId": 1002,
        "reason": "운영석"
      }
    ],
    "schedules": [
      {
        "id": 201,
        "performanceId": 20,
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
        "version": 0
      }
    ]
  }
}
```

##### 처리·검증 규칙

관리 응답에만 판매자 ID·내부 준비 상태·제외 사유를 포함한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/performances/20",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### salesStatus 계산 규칙

별도 DB 컬럼을 두지 않고 조회·응답 시 계산한다.

1. 공연 또는 회차 취소: CANCELED
2. 현재 시각 >= bookingCloseAt: CLOSED
3. 현재 시각 < bookingOpenAt: BEFORE_OPEN
4. 판매 기간 중 salesSuspended=true 또는 seatSetupStatus!=READY: UNAVAILABLE
5. 나머지 판매 가능 조건 충족: ON_SALE

매진·여유·임박은 이 상태에 포함하지 않는다. 관리 화면의 상세 원인은 seatSetupStatus·salesSuspended로 확인한다.

#### POST /api/admin/schedules/{scheduleId}/cancellation — 공연 회차 취소

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 202
- 호출 구분: 외부
- 기능 설명: 공연 회차 취소
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
POST /api/admin/schedules/{scheduleId}/cancellation
```

##### 헤더

Authorization
If-Match
If-Match는 회차의 현재 version(schedule.version)을 큰따옴표로 감싸 전달한다. 예: If-Match: "0"
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 공연 회차 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| reason | string | 필수 | 취소 사유. 1~500자. |

```json
{
  "reason": "주최 측 사정으로 취소"
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 202

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 취소 회차 ID. |
| operationStatus | string | 필수 | CANCELED 고정. |
| canceledAt | datetime | 필수 | 회차 취소 확정 시각. |
| refundProcessingStatus | string | 필수 | PENDING: 후속 환불 처리 대기. 예매 서비스 전달 완료·PG 환불 완료를 보장하지 않음. |
| version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. |

```json
{
  "status": 202,
  "message": "SUCCESS",
  "data": {
    "scheduleId": 201,
    "operationStatus": "CANCELED",
    "canceledAt": "2026-10-26T11:00:00+09:00",
    "refundProcessingStatus": "PENDING",
    "version": 1
  }
}
```

##### 처리·검증 규칙

202는 회차 취소·후속 환불 처리 접수를 뜻한다.
환불 완료를 의미하지 않는다.
If-Match는 회차 버전(schedule.version)이다. 취소 성공 시 schedule.version만 1 증가한다. 부모 performance.version은 바꾸지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/schedules/201/cancellation",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### 회차 취소 계약의 확정 범위

**확정된 취소 범위**

공연 전체 취소 전용 API는 제공하지 않고 회차별 취소로 처리한다.
반복 취소 요청은 중복 환불을 생성하지 않도록 같은 회차의 기존 취소 결과를 사용한다.
202는 공연 서비스의 회차 취소·후속 처리 접수 응답이며 실제 환불 완료 응답이 아니다.

**후속 전파 계약 — 담당자 합의 필요**

검토 보고서 3-7의 내부 HTTP 호출 후 Kafka 전환은 예매 담당자의 제안이며 아직 최종 확정된 계약이 아니다.
공연 → 예매 전달 방식·경로·페이로드, 전달 실패 기록과 재시도 주체, 예매의 예약 상태별 정리 및 좌석 처리, 환불 진행 조회 계약을 공연·예매·좌석·결제 담당자가 함께 확정해야 한다.

refundProcessingStatus=PENDING은 후속 환불 처리 대기 상태다.
예매 서비스로 전달되었다는 보장이나 PG 환불 완료를 뜻하지 않는다.
예매의 CANCELED와 실제 PG 환불 완료는 구분한다.
reservation-summary의 상태별 예약 건수만으로 환불 완료를 판단하는 계약은 확정하지 않았다.

근거: [API·테이블 명세 정합성 검토 보고서 (전체 서비스)](https://app.notion.com/p/405b3ff41d3981f09dd2d702fba4ac54) 3-7. 공연 ↔ 예매·결제.

#### PUT /api/admin/schedules/{scheduleId}/sales-suspension — 회차 판매 일시 중지·재개

- 권한·허용 호출자: MASTER
- 필수 헤더: Authorization
  If-Match
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 회차 판매 일시 중지·재개
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
PUT /api/admin/schedules/{scheduleId}/sales-suspension
```

##### 헤더

Authorization
If-Match
If-Match는 회차의 현재 version(schedule.version)을 큰따옴표로 감싸 전달한다. 예: If-Match: "0"
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 공연 회차 ID. |

###### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| suspended | boolean | 필수 | true 중지, false 재개. |
| reason | string\|null | 선택 | 중지(suspended=true) 시 필수이며 trim 후 1~500자. 재개(suspended=false) 시 생략 또는 null이고 저장된 중지 사유를 비운다. |

```json
{
  "suspended": true,
  "reason": "판매 설정 점검"
}
```

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 회차 ID. |
| salesSuspended | boolean | 필수 | 설정 결과. |
| suspensionReason | string\|null | 필수 | 재개 시 null. |
| version | integer | 필수 | 현재 회차 version. 회차 명령의 If-Match에 사용한다. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "scheduleId": 201,
    "salesSuspended": true,
    "suspensionReason": "판매 설정 점검",
    "version": 1
  }
}
```

##### 처리·검증 규칙

suspended=true이면 reason은 trim 후 1~500자 필수다.
재개 시 저장된 중지 사유를 null로 정리한다.
취소·마감·미준비 상태를 우회하지 못한다.
If-Match는 회차 버전(schedule.version)이다. 변경 성공 시 schedule.version만 1 증가한다. 부모 performance.version은 바꾸지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/admin/schedules/201/sales-suspension",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### 판매 중지 확정 정책

suspended=true이면 reason은 trim 후 1~500자 필수다.
suspended=false이면 reason은 생략 또는 null이며 기존 저장 사유를 비운다.

신규 선점을 차단하지만 이미 확보한 유효 HOLD는 원래 holdExpiresAt까지 결제를 시작할 수 있다.

진행 중 결제도 계속 처리한다. 만료 시각은 연장하지 않는다.

신규 선점용 sale-validations를 기존 선점 결제에 그대로 적용하지 않는다.

### 공개 조회

#### GET /api/performances — 공개 공연 목록 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공개 공연 목록 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/performances
```

##### 헤더

없음
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Query**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| genre | string | 선택 | CONCERT / MUSICAL / PLAY / SPORTS. |
| region | string | 선택 | 지역. 생략 시 전체. |
| from | date | 선택 | 공연 시작일 검색 하한. KST, 포함. |
| to | date | 선택 | 공연 시작일 검색 상한. KST, 포함. |
| keyword | string | 선택 | 검색어. 생략 시 필터 없음. |
| sort | string | 선택 | LATEST / BOOKING_OPEN_ASC. 기본 LATEST. |
| page | integer | 선택 | 0부터 시작, 기본 0. |
| size | integer | 선택 | 1~100, 기본 20. |

예시:

```txt
/api/performances?genre=MUSICAL&region=%EC%84%9C%EC%9A%B8&from=2026-12-01&to=2026-12-31&keyword=%EA%B3%B5%EC%97%B0&sort=LATEST&page=0&size=20
```

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| items | PerformanceSummary[] | 필수 | 공개 공연 목록. |
| items[].id | integer | 필수 | 공연 상품 ID. |
| items[].venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| items[].venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
| items[].venue.name | string | 필수 | 공연장명. 1~100자. |
| items[].venue.address | string | 필수 | 주소. 1~255자. |
| items[].venue.region | string | 필수 | 지역. 1~20자. |
| items[].title | string | 필수 | 공연명. 1~200자. |
| items[].genre | string | 필수 | CONCERT / MUSICAL / PLAY / SPORTS. |
| items[].posterUrl | string\|null | 필수 | HTTPS 이미지 URL. 최대 500자. 초안 미등록 시 null. |
| items[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| items[].period | Period\|null | 필수 | 활성 회차 집계 기간. 회차가 없으면 null. |
| items[].period.from | date | 필수 | 활성 회차의 첫 공연 날짜. |
| items[].period.to | date | 필수 | 활성 회차의 마지막 공연 날짜. |
| page | integer | 필수 | 0부터 시작. |
| size | integer | 필수 | 1~100. |
| totalElements | integer | 필수 | 전체 건수. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "items": [
      {
        "id": 20,
        "venue": {
          "id": 10,
          "name": "서울 공연장",
          "address": "서울특별시 중구 예시로 10",
          "region": "서울"
        },
        "title": "공연 A 서울",
        "genre": "MUSICAL",
        "posterUrl": "https://example.com/poster.jpg",
        "operationStatus": "ACTIVE",
        "period": {
          "from": "2026-12-01",
          "to": "2026-12-02"
        }
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1
  }
}
```

##### 처리·검증 규칙

공개·미삭제·정상 공연만 기본 조회.
from과 to가 모두 있으면 from <= to.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/performances",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/performances/{performanceId} — 공개 공연 상세 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공개 공연 상세 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/performances/{performanceId}
```

##### 헤더

없음
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 공연 상품 ID. |
| venue | VenueSummary | 필수 | 공연 하나에 공연장 하나. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
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
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| publishedAt | datetime\|null | 필수 | 최초 공개 시각. 초안이면 null. |
| period | Period\|null | 필수 | 활성 회차 집계 기간. 회차가 없으면 null. |
| period.from | date | 필수 | 활성 회차의 첫 공연 날짜. |
| period.to | date | 필수 | 활성 회차의 마지막 공연 날짜. |
| createdAt | datetime | 필수 | 생성 시각. |
| updatedAt | datetime | 필수 | 최종 변경 시각. |
| gradePrices | GradePrice[] | 필수 | 해당 공연 가격. 모든 회차 공통. |
| gradePrices[].venueSeatGradeId | integer | 필수 | 공연장의 고정 등급 ID. |
| gradePrices[].gradeName | string | 필수 | 공연장 원본 등급명. |
| gradePrices[].price | integer | 필수 | 공연 공통 기본 가격. 원, 0 초과. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 20,
    "venue": {
      "id": 10,
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
    "operationStatus": "ACTIVE",
    "publishedAt": "2026-10-26T10:00:00+09:00",
    "period": {
      "from": "2026-12-01",
      "to": "2026-12-02"
    },
    "createdAt": "2026-10-26T09:00:00+09:00",
    "updatedAt": "2026-10-26T10:00:00+09:00",
    "gradePrices": [
      {
        "venueSeatGradeId": 101,
        "gradeName": "VIP",
        "price": 150000
      }
    ]
  }
}
```

##### 처리·검증 규칙

초안·삭제는 404.
공개된 취소 공연은 취소 안내 목적으로 상세 조회 가능.
판매자 ID와 내부 오류는 반환하지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/performances/20",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### GET /api/performances/{performanceId}/schedules — 공연 회차 목록 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 회차 목록 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/performances/{performanceId}/schedules
```

##### 헤더

없음
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. 회차가 속한 부모 공연의 식별자. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |
| schedules | SchedulePublic[] | 필수 | 공개 회차 목록. 없으면 빈 배열. |
| schedules[].id | integer | 필수 | 회차 ID. |
| schedules[].performanceId | integer | 필수 | 공연 ID. |
| schedules[].startAt | datetime | 필수 | 회차 시작 시각. |
| schedules[].bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| schedules[].bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| schedules[].operationStatus | string | 필수 | ACTIVE / CANCELED. |
| schedules[].seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| schedules[].salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| schedules[].salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| schedules[].canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "performanceId": 20,
    "schedules": [
      {
        "id": 201,
        "performanceId": 20,
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
}
```

##### 처리·검증 규칙

회차별 임의 등급·가격을 반환하지 않는다.
가격은 공연 상세의 공통 gradePrices를 사용한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/performances/20/schedules",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### salesStatus 계산 규칙

별도 DB 컬럼을 두지 않고 조회·응답 시 계산한다.

1. 공연 또는 회차 취소: CANCELED
2. 현재 시각 >= bookingCloseAt: CLOSED
3. 현재 시각 < bookingOpenAt: BEFORE_OPEN
4. 판매 기간 중 salesSuspended=true 또는 seatSetupStatus!=READY: UNAVAILABLE
5. 나머지 판매 가능 조건 충족: ON_SALE

매진·여유·임박은 이 상태에 포함하지 않는다. 관리 화면의 상세 원인은 seatSetupStatus·salesSuspended로 확인한다.

#### GET /api/schedules/{scheduleId} — 공연 회차 상세 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 회차 상세 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/schedules/{scheduleId}
```

##### 헤더

없음
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 공연 회차 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| id | integer | 필수 | 회차 ID. |
| performanceId | integer | 필수 | 공연 ID. |
| startAt | datetime | 필수 | 회차 시작 시각. |
| bookingOpenAt | datetime | 필수 | 예매 오픈 시각. |
| bookingCloseAt | datetime | 필수 | 예매 마감 시각. |
| operationStatus | string | 필수 | ACTIVE / CANCELED. |
| seatSetupStatus | string | 필수 | PENDING / READY / FAILED. 사용자에게 내부 오류는 노출하지 않음. |
| salesSuspended | boolean | 필수 | 판매 일시 중지 여부. |
| salesStatus | string | 필수 | CANCELED / CLOSED / BEFORE_OPEN / UNAVAILABLE / ON_SALE. 매진 여부는 예매 서비스 별도 조회. |
| canceledAt | datetime\|null | 필수 | 취소 시각. 정상 회차이면 null. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "id": 201,
    "performanceId": 20,
    "startAt": "2026-12-01T19:00:00+09:00",
    "bookingOpenAt": "2026-11-01T14:00:00+09:00",
    "bookingCloseAt": "2026-12-01T18:00:00+09:00",
    "operationStatus": "ACTIVE",
    "seatSetupStatus": "READY",
    "salesSuspended": false,
    "salesStatus": "BEFORE_OPEN",
    "canceledAt": null
  }
}
```

##### 처리·검증 규칙

판매 상태는 서버 시각·운영 상태·준비 상태로 계산.
좌석별 선점 상태는 예매 서비스 조회를 이용한다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/schedules/201",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

##### salesStatus 계산 규칙

별도 DB 컬럼을 두지 않고 조회·응답 시 계산한다.

1. 공연 또는 회차 취소: CANCELED
2. 현재 시각 >= bookingCloseAt: CLOSED
3. 현재 시각 < bookingOpenAt: BEFORE_OPEN
4. 판매 기간 중 salesSuspended=true 또는 seatSetupStatus!=READY: UNAVAILABLE
5. 나머지 판매 가능 조건 충족: ON_SALE

매진·여유·임박은 이 상태에 포함하지 않는다. 관리 화면의 상세 원인은 seatSetupStatus·salesSuspended로 확인한다.

#### GET /api/performances/{performanceId}/seat-layout — 공연 좌석 배치 조회

- 권한·허용 호출자: 누구나
- 필수 헤더: 없음
- 성공 HTTP: 200
- 호출 구분: 외부
- 기능 설명: 공연 좌석 배치 조회
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

##### 메서드·경로

```txt
GET /api/performances/{performanceId}/seat-layout
```

##### 헤더

없음
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

##### Request DTO

###### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |

###### **Body**

요청 본문 없음.
빈 JSON 객체를 필수로 보내지 않는다.

##### Response DTO

외부 응답은 status·message·data로 감싼다. 아래 필드 표는 data 내부 구조다.

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| performanceId | integer | 필수 | 공연 ID. |
| venue | VenueSummary | 필수 | 공연장. |
| venue.id | integer | 필수 | 공연장 ID. BIGINT 기반 JSON 숫자로 표현. |
| venue.name | string | 필수 | 공연장명. 1~100자. |
| venue.address | string | 필수 | 주소. 1~255자. |
| venue.region | string | 필수 | 지역. 1~20자. |
| seats | LayoutSeat[] | 필수 | 고정 등급·공연별 가격·공통 제외 여부. |
| seats[].id | integer | 필수 | 물리 좌석 ID. |
| seats[].section | string | 필수 | 구역. 1~20자. |
| seats[].rowLabel | string | 필수 | 열. 1~10자. |
| seats[].seatNumber | integer | 필수 | 좌석 번호. 1 이상. |
| seats[].venueSeatGradeId | integer | 필수 | 동일 공연장의 고정 등급 ID. |
| seats[].gradeName | string | 필수 | 고정 등급명. |
| seats[].isSellable | boolean | 필수 | 공연 공통 제외 정책 반영. 실시간 점유 여부 아님. |
| seats[].basePrice | integer\|null | 필수 | 공연 등급 가격. 판매 제외 등급 가격 미설정 시 null. |

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "performanceId": 20,
    "venue": {
      "id": 10,
      "name": "서울 공연장",
      "address": "서울특별시 중구 예시로 10",
      "region": "서울"
    },
    "seats": [
      {
        "id": 1001,
        "section": "A",
        "rowLabel": "1",
        "seatNumber": 1,
        "venueSeatGradeId": 101,
        "gradeName": "VIP",
        "isSellable": true,
        "basePrice": 150000
      }
    ]
  }
}
```

##### 처리·검증 규칙

정적 배치·공연장 등급·공연별 가격·공연 공통 제외 여부만 제공.
실시간 선택 가능 여부는 포함하지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

##### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/api/performances/20/seat-layout",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

## 3. 내부 API

### POST /internal/schedules/{scheduleId}/sale-validations — 회차 판매 정책·기본 가격 검증

- 권한·허용 호출자: 예매 서비스
- 필수 헤더: 서비스 인증
- 성공 HTTP: 200
- 호출 구분: 내부
- 기능 설명: 회차 판매 정책·기본 가격 검증
  공연장 고정 등급·공연별 공통 가격 정책 적용.
  상세 정책은 본문 및 공통 계약 참조.

결정 근거·전체 공통 계약: [전체 API 공통 계약 · 보고서 결정 반영](https://app.notion.com/p/405b3ff41d39817497f1fd490e3ea41b)

**DTO 계약 초안 — 현재 설계 기준**

공통 계약: [공연 API 공통 계약·상세 명세](https://app.notion.com/p/405b3ff41d3981eca811d2da18f1e5c5)
소유·공연장 고정 등급·공연별 공통 가격 정책은 기존 명세를 유지한다.
필수는 필드 존재 여부다. null 허용 여부는 타입의 |null로 표시한다.
ID는 BIGINT 기반 JSON 숫자(integer), 버전·수량·원화 가격은 integer, 시각은 +09:00 ISO 8601이다.

#### 메서드·경로

```txt
POST /internal/schedules/{scheduleId}/sale-validations
```

#### 헤더

서비스 인증
If-Match가 있으면 요청 대상 리소스의 version(venue API는 venue.version, 공연 API는 performance.version, 회차 API는 schedule.version)을 큰따옴표로 감싸 전달한다.
Content-Type: application/json은 JSON 요청 본문이 있을 때 사용한다.

#### Request DTO

##### **Path**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| scheduleId | integer | 필수 | 공연 회차 ID. |

##### **Body**

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| configVersion | integer | 필수 | 예매 서비스가 보유한 좌석 구성 버전. 1 이상. |
| venueSeatIds | integer[] | 필수 | 1개 이상, 중복 불가. 공연장 물리 좌석 ID 목록. |

```json
{
  "configVersion": 1,
  "venueSeatIds": [
    1001
  ]
}
```

#### Response DTO

HTTP 200

| 필드 | 타입 | 필수 여부 | 설명·검증 |
| --- | --- | --- | --- |
| allowed | boolean | 필수 | 신규 선점 판매 정책 통과 여부. 좌석 확보 보장 아님. |
| reasonCode | string\|null | 필수 | 허용 시 null. 거절 시 PERFORMANCE_NOT_PUBLISHED / PERFORMANCE_CANCELED / SCHEDULE_CANCELED / SALES_SUSPENDED / SEAT_SETUP_NOT_READY / BEFORE_OPEN / SALES_CLOSED / SEAT_NOT_SELLABLE / SEAT_CONFIG_VERSION_MISMATCH 중 원인. |
| scheduleId | integer | 필수 | 대상 회차 ID. |
| configVersion | integer | 필수 | 서버 최신 구성 버전. |
| policyVersion | integer | 필수 | 부모 공연 version. |
| performanceTitle | string | 필수 | 공연명 스냅샷. |
| scheduleStartAt | datetime | 필수 | 공연 시각 스냅샷. |
| validatedAt | datetime | 필수 | 서버 검증 시각. |
| seats | ValidationSeat[] | 필수 | 허용 시 요청 좌석 가격. 거절 시 빈 배열. |
| seats[].venueSeatId | integer | 필수 | 검증한 물리 좌석 ID. |
| seats[].venueSeatGradeId | integer | 필수 | 공연장 고정 등급 ID. |
| seats[].gradeName | string | 필수 | 공연장 원본 등급명. |
| seats[].basePrice | integer | 필수 | 공연 공통 가격. 0 초과. |

```json
{
  "allowed": true,
  "reasonCode": null,
  "scheduleId": 201,
  "configVersion": 1,
  "policyVersion": 3,
  "performanceTitle": "공연 A 서울",
  "scheduleStartAt": "2026-12-01T19:00:00+09:00",
  "validatedAt": "2026-11-01T14:01:00+09:00",
  "seats": [
    {
      "venueSeatId": 1001,
      "venueSeatGradeId": 101,
      "gradeName": "VIP",
      "basePrice": 150000
    }
  ]
}
```

#### 처리·검증 규칙

거절도 정상 검증 결과이면 HTTP 200, allowed=false.
reasonCode 필수, seats=[].
가격은 서버 원본으로 결정하며 좌석 점유 여부는 판단하지 않는다.
알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.
인증·역할·본인 소유권, 공개 후 수정 제한, 시간 순서와 FK 소속을 서버가 검증한다.
If-Match 누락·충돌 및 업무 오류의 상세 코드는 공통 명세를 따른다.

#### 오류 응답 DTO

```json
{
  "status": 400,
  "code": "INVALID_REQUEST",
  "message": "요청 값을 확인해주세요.",
  "path": "/internal/schedules/201/sale-validations",
  "timestamp": "2026-11-01T14:00:00+09:00",
  "errors": [
    {
      "field": "요청 필드 경로",
      "reason": "검증 실패 사유"
    }
  ]
}
```

errors는 검증 오류일 때만 포함한다. 각 항목은 field, reason이다.
취소·삭제·준비·기능 제안 등 정책 상태는 원본 공통 계약을 유지한다.

#### 공연 상태·삭제 검증

공연이 DRAFT이면 PERFORMANCE_NOT_PUBLISHED, 공연이 취소됐으면 PERFORMANCE_CANCELED로 allowed=false를 반환한다.

공연·회차가 삭제되었거나 없으면 기존 HTTP 404 ErrorResponse의 PERFORMANCE_NOT_FOUND / SCHEDULE_NOT_FOUND로 처리한다. 삭제 여부는 노출하지 않는다.

NOT_FOUND를 HTTP 200 reasonCode에 포함하는 응답 구조까지는 확정되지 않았다.

좌석 선점 API는 판매 거절 reasonCode를 그대로 외부 code로 전달하며 HTTP 400을 사용한다. SEAT_NOT_SELLABLE·SEAT_CONFIG_VERSION_MISMATCH는 HTTP 409다.

관람 나이 미달은 선점을 차단한다. 생년월일 확보·검증 기준·ageRating 전달 계약은 미확정이므로 추가 협의한다.
