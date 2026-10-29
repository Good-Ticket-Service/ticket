# API 명세

기준: `origin/develop` 7a48c5e(공연 #54 포함), 2026-10-27. 저장소에 옮겨진 Notion 명세를 통합했다. 실시간 Notion 대조는 미실시다.
표기는 `필드:타입`, `?`는 생략 가능, `|null`은 null 허용, `[]`는 배열이다. R은 외부 `ApiResponse.data` 또는 내부 원문이다. 공통 응답·인증·시각·기본 오류는 [conventions.md](conventions.md), enum·저장은 [tables.md](tables.md), 상태 전이·미합의 사항은 [architecture.md](architecture.md)를 따른다. 명세 존재는 구현 완료를 뜻하지 않는다.
상태: 구현됨은 develop 코드 존재, 합의는 구현 가능한 계약, 초안은 담당 합의 전이다. 구현됨이어도 미연동 경로는 별도 표시한다.
`원본 복원: 경로:줄`은 7a48c5e의 Git 이력 `docs/경로`를 가리키는 출처이며 현재 파일 탐색 경로가 아니다. 담당 확인 표시는 이번 복원의 정확성 검토 요청이며, 그 자체로 기존 합의를 미합의로 바꾸지 않는다.

## 회원 — user-service

상태: 가입·판매자 가입·로그인·본인 조회·내부 조회는 구현됨. 수정·탈퇴는 초안(구현 전).

공개 가입·로그인 외에는 본인 JWT. B=본문, Q=쿼리, R=응답.

| Method URI | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|
| POST /api/auth/signup | 201 | Signup | SignupResult(role=USER) | 409 DUPLICATE_EMAIL |
| POST /api/auth/seller/signup | 201 | Signup | SignupResult(role=SELLER) | 409 DUPLICATE_EMAIL |
| POST /api/auth/login | 200 | email:string, password:string | accessToken:string, tokenType:string=Bearer, expiresIn:int=3600 | 401 LOGIN_FAILED |
| GET /api/users/me | 200 | 없음 | User | 404 USER_NOT_FOUND |
| PATCH /api/users/me | 200 | name:string, phone:string(선택/null 정책 미정) | User | 미정; 구현 전 |
| DELETE /api/users/me | 200(기존 예시 기준) | 없음 | userId:long, status:WITHDRAWN, withdrawnAt:datetime | 미정; 구현 전 |
| GET /internal/users/{userId} | 200 | 없음 | userId:long, email:string, role:string | 내부 호출, 조회 멱등;404 USER_NOT_FOUND(없음·논리 삭제·비ACTIVE). ACTIVE 검증은 #88 브랜치 구현 |

- Signup = `{email:string(이메일), password:string(8~15자·대소문자/숫자/특수문자), name:string(공백 불가), phone:string(01[016789]-숫자3~4-숫자4)}`. 역할은 서버 고정. 탈퇴 이메일도 중복 거절.
- SignupResult = `{userId:long,email:string,name:string,role:string,status:string}`.
- User = `{userId:long,email:string,name:string,phone:string,role:string,status:string,createdAt:datetime,updatedAt:datetime}`.

## 공연 — performance-service

상태: GET /api/performances, GET /api/performances/{performanceId}, GET /api/performances/{performanceId}/schedules, GET /api/schedules/{scheduleId}, GET /api/performances/{performanceId}/seat-layout는 구현됨(#83, #107, #108). 나머지 10개는 초안(구현 전)이다. 공연장 고정 등급과 공연별 공통 가격 정책은 합의다.

33개 계약 중 이번 프로젝트 기간에는 아래 표의 15개를 구현한다. 나머지 18개는 「추후 구현 예정 API」에 경로만 남긴다.

공통 규칙

- Path ID는 양의 long이다.
- 공연 목록은 `PageP<T>={items:T[],page:int,size:int,totalElements:long}`이다. page는 0부터 시작하고 기본 0이다. size는 1~100이고 기본 20이다.
- 버전 필드(version, venueVersion, performanceVersion, policyVersion, configVersion, seatConfigVersion)는 long이다. 테이블의 BIGINT 컬럼과 같다.
- If-Match는 대상 리소스의 version을 큰따옴표로 감싼 값이다(예: `"3"`).
- 공연장의 등급·좌석 명령은 venue.version을 쓴다.
- 등급별 가격 설정과 공연 공개는 performance.version을 쓴다.
- 회차 명령은 schedule.version을 쓴다.
- If-Match가 없으면 428 PRECONDITION_REQUIRED, version이 다르면 409 VERSION_CONFLICT다.
- 회차 등록은 If-Match를 받지 않는다.
- 관리용 단건 조회는 응답 헤더 `ETag: "{version}"`을 준다. 이 값을 다음 명령의 If-Match에 그대로 쓴다.
- 공연 초안 등록과 회차 등록의 Idempotency-Key는 권장 초안이다. 키의 범위와 보존 기간은 정하지 않았다.
- 요청 형식 오류는 400 VALIDATION_ERROR다(#83 결정). 필드 검증, 타입·열거값 불일치, 날짜 형식, page·size 범위, Path ID 형식이 여기에 해당한다. 팀 공통 GlobalExceptionHandler가 이 코드로 응답하기 때문이다.
- 업무 규칙 위반은 INVALID_TIME_RANGE 같은 공연 오류 코드를 쓴다.
- 「추가 오류」 칸에는 공통 오류(400 VALIDATION_ERROR, 401 UNAUTHORIZED, 403 FORBIDDEN)를 빼고 그 API에서만 생기는 오류를 적는다.
- 표의 Genre는 CONCERT, MUSICAL, PLAY, SPORTS 중 하나다.
- 표의 AgeRating은 ALL, 12, 15, 19 중 하나다.
- 표의 PerformanceSort는 LATEST, BOOKING_OPEN_ASC 중 하나다.

### 이번 기간 구현 API

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| POST /api/admin/venues | MASTER | 201, Location | B: name:string[1~100자],address:string[1~255자],region:string[1~20자] | P1 | — |
| POST /api/admin/venues/{venueId}/seat-grades | MASTER | 201 | 헤더 If-Match; B: grades:GradeInput[][1개 이상] | P3 | 404 VENUE_NOT_FOUND;409 DUPLICATE_VENUE_GRADE;409 VENUE_LAYOUT_IN_USE;409 VERSION_CONFLICT;428 PRECONDITION_REQUIRED |
| POST /api/admin/venues/{venueId}/seats | MASTER | 201 | 헤더 If-Match; B: seats:SeatInput[][1개 이상] | P5 | 400 VENUE_GRADE_MISMATCH;404 VENUE_NOT_FOUND;409 DUPLICATE_VENUE_SEAT;409 VENUE_LAYOUT_IN_USE;409 VERSION_CONFLICT;428 PRECONDITION_REQUIRED |
| POST /api/seller/performances | SELLER | 201, Location | B: PerformanceInput | P8 | 404 VENUE_NOT_FOUND |
| PUT /api/seller/performances/{performanceId}/grade-prices | 소유 SELLER | 200 | 헤더 If-Match; B: gradePrices:PriceInput[] | P11 | 400 VENUE_GRADE_MISMATCH;400 GRADE_PRICE_MISSING;404 PERFORMANCE_NOT_FOUND;409 PERFORMANCE_CONFIGURATION_LOCKED;409 VERSION_CONFLICT;428 PRECONDITION_REQUIRED |
| POST /api/seller/performances/{performanceId}/publication | 소유 SELLER | 200 | 헤더 If-Match; 없음 | P8 | 404 PERFORMANCE_NOT_FOUND;409 PUBLICATION_REQUIREMENTS_NOT_MET;409 INVALID_STATUS_TRANSITION;409 VERSION_CONFLICT;428 PRECONDITION_REQUIRED |
| POST /api/seller/performances/{performanceId}/schedules | 소유 SELLER | 201, Location | B: startAt:datetime,bookingOpenAt:datetime,bookingCloseAt:datetime | P13 | 400 INVALID_TIME_RANGE;404 PERFORMANCE_NOT_FOUND;409 DUPLICATE_SCHEDULE;409 INVALID_STATUS_TRANSITION |
| GET /api/seller/schedules/{scheduleId} | 소유 SELLER | 200, ETag | 없음 | P13 | 404 SCHEDULE_NOT_FOUND |
| PUT /api/admin/schedules/{scheduleId}/sales-suspension | MASTER | 200 | 헤더 If-Match; B: suspended:boolean,reason?:string\|null | P15 | 404 SCHEDULE_NOT_FOUND;409 INVALID_STATUS_TRANSITION;409 VERSION_CONFLICT;428 PRECONDITION_REQUIRED |
| GET /api/performances | 누구나 | 200 | Q: genre?:Genre,region?:string,from?:date,to?:date,keyword?:string,sort?:PerformanceSort=LATEST,page?:int=0,size?:int=20[1~100] | P16 | 400 INVALID_TIME_RANGE |
| GET /api/performances/{performanceId} | 누구나 | 200 | 없음 | P17 | 404 PERFORMANCE_NOT_FOUND |
| GET /api/performances/{performanceId}/schedules | 누구나 | 200 | 없음 | P18 | 404 PERFORMANCE_NOT_FOUND |
| GET /api/schedules/{scheduleId} | 누구나 | 200 | 없음 | P19 | 404 SCHEDULE_NOT_FOUND |
| GET /api/performances/{performanceId}/seat-layout | 누구나 | 200 | 없음 | P20 | 404 PERFORMANCE_NOT_FOUND |

- GradeInput = `{name:string[1~20자, 공백 불가]}`. 요청 안에서 이름이 겹치면 400 VALIDATION_ERROR다.
- SeatInput = `{section:string[1~20자],rowLabel:string[1~10자],seatNumber:int[1 이상],venueSeatGradeId:long[같은 공연장의 등급]}`.
- PerformanceInput = `{venueId:long,title:string[1~200자],genre:Genre,ageRating:AgeRating,runningTime:int[1 이상],castInfo?:string|null[최대 500자],description?:string|null,posterUrl?:string|null[HTTPS URL, 최대 500자]}`.
- PriceInput = `{venueSeatGradeId:long,price:int[0 초과]}`. 요청 안에서 등급 ID가 겹치면 400 VALIDATION_ERROR다.
- sales-suspension의 reason은 중지(suspended=true)할 때 필수이고 trim 후 1~500자다.
- 재개(suspended=false)할 때 reason은 생략하거나 null로 보낸다. 저장된 중지 사유는 지운다.
- GET /api/performances의 from과 to는 KST 날짜이고 양 끝을 포함한다. from이 to보다 늦으면 400 INVALID_TIME_RANGE다.
- Location은 생성한 리소스의 관리용 GET 경로다. 공연장은 `/api/seller/venues/{venueId}`, 공연은 `/api/seller/performances/{performanceId}`, 회차는 `/api/seller/schedules/{scheduleId}`다.
- 등급 등록과 좌석 등록은 일괄 생성이라 Location을 주지 않는다. 생성된 ID는 응답 본문(P3, P5)에 있다.

### 내부 API

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| POST /internal/schedules/{scheduleId}/sale-validations | 예매 서비스 | 200 | B: configVersion:long[1 이상],venueSeatIds:long[][1개 이상, 중복 불가] | P21 | 404 SCHEDULE_NOT_FOUND |

- 판매 검증의 서비스 인증 방식은 팀에서 정하지 않았다. 그래서 인증 실패 코드를 적지 않는다.

### 공연 응답 스키마

- P1 = `id:long; name:string; address:string; region:string; version:long; createdAt:datetime; updatedAt:datetime`.
- P3 = `grades:Grade[]; grades[].id:long; grades[].name:string; venueVersion:long`.
- P5 = `createdCount:integer; createdSeatIds:long[]; venueVersion:long`.
- P8 = `id:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; publicationStatus:string; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; version:long; createdAt:datetime; updatedAt:datetime; sellerId:long`.
- P11 = `gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:long; gradePrices[].gradeName:string; gradePrices[].price:integer; performanceVersion:long`.
- P13 = `id:long; performanceId:long; startAt:datetime; bookingOpenAt:datetime; bookingCloseAt:datetime; operationStatus:string; seatSetupStatus:string; salesSuspended:boolean; salesStatus:string; canceledAt:datetime|null; seatConfigVersion:long; seatSetupRequestId:string; seatSetupError:string|null; suspensionReason:string|null; cancelReason:string|null; version:long`.
- P15 = `scheduleId:long; salesSuspended:boolean; suspensionReason:string|null; version:long`.
- P16 = `items:PerformanceSummary[]; items[].id:long; items[].venue:VenueSummary; items[].venue.id:long; items[].venue.name:string; items[].venue.address:string; items[].venue.region:string; items[].title:string; items[].genre:string; items[].posterUrl:string|null; items[].operationStatus:string; items[].period:Period|null; items[].period.from:date; items[].period.to:date; page:integer; size:integer; totalElements:long`.
- P17 = `id:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; createdAt:datetime; updatedAt:datetime; gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:long; gradePrices[].gradeName:string; gradePrices[].price:integer`.
- P18 = `performanceId:long; schedules:SchedulePublic[]; schedules[].id:long; schedules[].performanceId:long; schedules[].startAt:datetime; schedules[].bookingOpenAt:datetime; schedules[].bookingCloseAt:datetime; schedules[].operationStatus:string; schedules[].seatSetupStatus:string; schedules[].salesSuspended:boolean; schedules[].salesStatus:string; schedules[].canceledAt:datetime|null`.
- P19 = `id:long; performanceId:long; startAt:datetime; bookingOpenAt:datetime; bookingCloseAt:datetime; operationStatus:string; seatSetupStatus:string; salesSuspended:boolean; salesStatus:string; canceledAt:datetime|null`.
- P20 = `performanceId:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; seats:LayoutSeat[]; seats[].id:long; seats[].section:string; seats[].rowLabel:string; seats[].seatNumber:integer; seats[].venueSeatGradeId:long; seats[].gradeName:string; seats[].isSellable:boolean; seats[].basePrice:integer|null`.
- P21 = `allowed:boolean; reasonCode:string|null; scheduleId:long; performanceId:long; sellerId:long; configVersion:long; policyVersion:long; performanceTitle:string; scheduleStartAt:datetime; validatedAt:datetime; seats:ValidationSeat[]; seats[].venueSeatId:long; seats[].venueSeatGradeId:long; seats[].gradeName:string; seats[].basePrice:integer`.
- 스키마 번호는 다른 절과 architecture가 참조하므로 바꾸지 않는다. 비어 있는 번호(P2, P4, P6, P7, P9, P10, P12, P14)는 추후 구현 예정 API의 스키마라 여기에 두지 않는다.

판매 검증 P21.performanceId·sellerId (합의 #86):
- performanceId는 회차가 속한 공연의 ID, sellerId는 그 공연의 판매자 ID다.
- 좌석은 두 값을 BookingFacade.hold의 HoldTarget으로 그대로 넘긴다. 클라이언트 입력에서 받지 않는다.
- 예매는 두 값을 예약에 저장하고 결제 준비(POST /internal/payments)에 선점 시점 값으로 넘긴다. 결제는 같은 orderId의 재요청에서 값이 다르면 409 PAYMENT_ORDER_CONFLICT로 거절하기 때문이다.

판매 검증 P21.scheduleStartAt·performanceTitle (합의 #126):
- 좌석은 두 값도 HoldTarget으로 그대로 넘긴다. 허용 응답에 두 값이 없으면 좌석이 503으로 선점을 거절한다.
- 예매는 두 값을 예약에 선점 시점 스냅샷으로 저장하고 다시 조회하지 않는다. scheduleStartAt은 취소 기한, performanceTitle은 내 예매 목록 표시에 쓴다. 취소가 공연 서비스 가용성에 묶이지 않게 하기 위해서다.

### 공연 오류 코드

공연 업무 코드 (이번 구현 범위)

| HTTP | 코드 | 뜻 |
|---|---|---|
| 400 | INVALID_TIME_RANGE | 날짜·시각의 순서가 맞지 않음 |
| 400 | VENUE_GRADE_MISMATCH | 다른 공연장의 등급이나 없는 등급을 참조함 |
| 400 | GRADE_PRICE_MISSING | 판매 대상 좌석이 쓰는 등급에 가격이 없음 |
| 404 | PERFORMANCE_NOT_FOUND | 공연이 없거나 삭제됨. 공개 조회에서는 DRAFT도 포함 |
| 404 | SCHEDULE_NOT_FOUND | 회차나 부모 공연이 없거나 삭제됨 |
| 404 | VENUE_NOT_FOUND | 공연장이 없거나 삭제됨 |
| 409 | VERSION_CONFLICT | If-Match가 현재 version과 다름 |
| 409 | PUBLICATION_REQUIREMENTS_NOT_MET | 공개 필수 조건이 부족함 |
| 409 | PERFORMANCE_CONFIGURATION_LOCKED | 공개된 공연의 구성을 바꾸려 함 |
| 409 | DUPLICATE_SCHEDULE | 같은 공연에 시작 시각이 같은 회차가 있음 |
| 409 | DUPLICATE_VENUE_SEAT | 같은 구역·열·번호의 좌석이 있음 |
| 409 | DUPLICATE_VENUE_GRADE | 같은 공연장에 이름이 같은 등급이 있음 |
| 409 | VENUE_LAYOUT_IN_USE | 공연이 참조한 공연장의 등급·좌석을 추가하려 함 |
| 428 | PRECONDITION_REQUIRED | If-Match가 없음 |

- 428 PRECONDITION_REQUIRED와 409 VERSION_CONFLICT는 공연 전용 코드다. 다른 도메인은 If-Match를 쓰지 않으므로 팀 공통 코드에 넣지 않는다.
- 공연은 팀 공통 코드 409 INVALID_STATUS_TRANSITION도 쓴다. 취소된 공연에 회차를 등록하거나 공연을 공개할 때, 취소된 회차의 판매를 중지하거나 재개할 때다.
- 공연 초안 등록에서 venueId의 공연장이 없거나 삭제됐으면 404 VENUE_NOT_FOUND다.
- 좌석 등록과 가격 설정에서 없는 등급 ID는 400 VENUE_GRADE_MISMATCH로 응답한다. 등급 미존재 전용 코드는 두지 않는다.
- PUBLICATION_REQUIREMENTS_NOT_MET 응답에는 부족한 항목을 함께 준다.
- DUPLICATE_VENUE_SEAT가 나면 좌석 일괄 등록 전체를 롤백한다.

판매 거절 사유 (P21.reasonCode)

| reasonCode | 뜻 | 좌석 외부 API의 HTTP |
|---|---|---|
| PERFORMANCE_NOT_PUBLISHED | 공연이 DRAFT임 | 400 |
| PERFORMANCE_CANCELED | 공연이 취소됨 | 400 |
| SCHEDULE_CANCELED | 회차가 취소됨 | 400 |
| SALES_SUSPENDED | 회차 판매가 일시 중지됨 | 400 |
| SEAT_SETUP_NOT_READY | 좌석 준비가 READY가 아님 | 400 |
| BEFORE_OPEN | 예매 오픈 전임 | 400 |
| SALES_CLOSED | 예매가 마감됨 | 400 |
| SEAT_NOT_SELLABLE | 판매 제외 좌석이거나 이 공연장의 좌석이 아님 | 409 |
| SEAT_CONFIG_VERSION_MISMATCH | 요청 configVersion이 회차의 최신 구성 버전과 다름 | 409 |

- 이 값은 오류 코드가 아니라 판매 검증 200 응답의 값이다. 좌석 서비스는 이 값을 외부 선점 API의 오류 code로 그대로 쓴다.
- 다른 도메인이 이 값에 의존하므로 이름을 바꾸지 않는다.
- 공연 API는 SEAT_NOT_SELLABLE과 SEAT_CONFIG_VERSION_MISMATCH를 HTTP 오류로 내지 않는다. 409는 좌석 외부 API의 응답이다.

### 업무 규칙

공개 조회
- 공개 조회는 PUBLISHED이고 삭제되지 않은 공연만 보여 준다. DRAFT이거나 삭제된 공연은 404 PERFORMANCE_NOT_FOUND다.
- 공개 공연 목록은 취소된 공연을 뺀다(#83 결정).
- 취소된 공개 공연은 취소 안내를 위해 상세를 200으로 조회할 수 있다.
- 공개 회차 상세는 부모 공연이 DRAFT이거나 삭제됐으면 404 SCHEDULE_NOT_FOUND다.
- 공개 응답에는 sellerId, version, publicationStatus를 넣지 않는다.
- 공개 응답에는 좌석 준비 내부 오류와 판매 제외 사유를 넣지 않는다.
- 공개 조회는 알 수 없는 쿼리 파라미터를 거절하지 않고 무시한다(#83 결정). 프런트의 추적·캐시 파라미터를 허용하기 위해서다.
- LATEST는 공개 시각의 최신순이다. 공개 시각이 같으면 ID 역순이다.
- BOOKING_OPEN_ASC는 예매가 마감되지 않은 활성 회차 중 가장 이른 예매 오픈 시각의 오름차순이다.
- BOOKING_OPEN_ASC에서 그런 회차가 없는 공연은 마지막에 둔다. 기준 시각이 같으면 ID 역순이다.
- 활성 회차는 삭제되지 않고 취소되지 않은 회차다.
- period는 활성 회차의 첫 공연 날짜와 마지막 공연 날짜다. 활성 회차가 없으면 null이다.
- from·to 필터는 시작일(KST)이 범위 안인 활성 회차가 있는 공연을 고른다.
- salesStatus는 CANCELED, CLOSED, BEFORE_OPEN, UNAVAILABLE, ON_SALE 순서로 먼저 맞는 값을 준다. 매진 여부는 포함하지 않는다.
- 좌석 배치(P20)의 isSellable은 공연 공통 판매 제외를 반영한다. 실시간 점유 여부가 아니다.
- 좌석 배치(P20)의 basePrice는 판매 제외 좌석이면서 그 등급에 가격이 없을 때만 null이다.
- 좌석 배치(P20)와 좌석 서비스의 선택 가능 좌석 조회(`GET /api/seats/schedules/{scheduleId}`)는 좌석 순서가 다르다. 두 응답은 순서가 아니라 P20의 `seats[].id`와 좌석 조회의 `venueSeatId`(같은 공연장 좌석 ID)로 짝짓는다.

공연·회차 관리
- 등급별 가격 설정은 공연의 전체 가격 집합을 보내 교체한다. 판매 대상 좌석이 쓰는 등급은 모두 가격이 있어야 하며(없으면 400 GRADE_PRICE_MISSING), 모든 좌석이 판매 제외된 등급만 가격을 생략할 수 있다.
- 공개 후에는 등급별 가격을 바꿀 수 없다(409 PERFORMANCE_CONFIGURATION_LOCKED).
- 공연이 참조한 공연장에는 등급과 좌석을 추가할 수 없다(409 VENUE_LAYOUT_IN_USE).
- 공연 공개는 멱등이다. 이미 공개된 공연에 다시 요청하면 If-Match가 달라도 200과 현재 결과를 준다. 최초 publishedAt은 바뀌지 않는다.
- DRAFT로 되돌리는 API는 없다.
- 좌석 준비가 PENDING이나 FAILED여도 공연을 공개할 수 있다.
- 취소되지 않은 공연에는 공개 후에도 회차를 추가할 수 있다.
- 회차 시각은 예매 오픈 < 예매 마감 ≤ 공연 시작이어야 한다. 어기면 400 INVALID_TIME_RANGE다.
- 회차 등록은 좌석 배정을 받지 않는다. 등급, 가격, 판매 제외는 공연 설정을 쓴다.

좌석 준비
- 회차 등록과 등급별 가격 변경은 회차를 seatSetupStatus=PENDING으로 만든다.
- 공연 서비스는 트랜잭션 커밋 뒤 좌석 준비를 요청한다. 회차 등록 응답은 준비 완료를 기다리지 않는다.
- 준비에 성공하면 READY다. 일시적 실패는 서버가 같은 requestId로 자동 재시도한다.
- 재시도 한도를 넘거나 요청 데이터 오류로 실패하면 FAILED다.
- 판매자용 좌석 준비 재시도 API는 없다. 판매자는 seatSetupStatus와 seatSetupError로 상태를 확인한다.
- 좌석 준비 호출 경로와 응답은 좌석 담당과 합의 전이다(architecture 14절).

판매 중지와 판매 검증
- 판매 중지는 신규 선점만 막는다. 이미 잡힌 유효 HOLD는 원래 만료 시각까지 결제할 수 있다.
- 판매를 재개해도 오픈 전, 마감 후, 좌석 미준비, 취소 조건은 그대로 적용된다.
- 판매 검증의 업무 거절은 HTTP 200, allowed=false, reasonCode로 준다. 이때 seats는 빈 배열이다.
- 판매 검증의 회차나 부모 공연이 없거나 삭제됐으면 404 SCHEDULE_NOT_FOUND다.
- 판매 검증이 허용이면 요청 좌석의 등급과 가격을 준다. 가격은 공연별 등급 가격이다.
- policyVersion은 부모 공연의 version이다.
- 판매 검증은 좌석 점유 여부를 판단하지 않는다. 좌석 확보는 좌석 서비스가 한다.

### 추후 구현 예정 API

아래 18개는 이번 프로젝트 기간에는 구현하지 않으며 추후 구현 예정이다. 계약 상세는 공연 담당이 보관한다. 다른 도메인에서 이 API가 필요하면 공연 담당(박의연)과 협의한다.

- `GET /api/seller/performances`
- `GET /api/seller/performances/{performanceId}`
- `PATCH /api/seller/performances/{performanceId}`
- `PUT /api/seller/performances/{performanceId}/seat-exclusions`
- `PATCH /api/seller/schedules/{scheduleId}`
- `GET /api/seller/venues`
- `GET /api/seller/venues/{venueId}`
- `GET /api/seller/venues/{venueId}/seat-grades`
- `GET /api/seller/venues/{venueId}/seats`
- `DELETE /api/seller/performances/{performanceId}`
- `DELETE /api/seller/schedules/{scheduleId}`
- `PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId}`
- `DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId}`
- `PATCH /api/admin/venues/{venueId}/seats/{seatId}`
- `DELETE /api/admin/venues/{venueId}/seats/{seatId}`
- `GET /api/admin/performances`
- `GET /api/admin/performances/{performanceId}`
- `POST /api/admin/schedules/{scheduleId}/cancellation`

## 좌석 — reservation-service / seat

상태: 선택 가능 좌석 조회(#84)와 좌석 선점(#148)은 구현됨(Gateway 라우트 #97). 나머지 외부 API는 초안(구현 전). 외부 경로는 `/api/seats/**`다. `/api/schedules/{scheduleId}`는 공연 회차 단건이 쓰므로, 좌석은 맨 앞 리소스를 따로 두어 Gateway가 앞부분만 보고 서비스를 고르게 한다(2026-10-28 합의). #34 Facade 계약은 합의이며 별도 절의 구현 상태를 따른다.

| Method URI | 권한 | 성공 | B | R | 추가 오류 |
|---|---|---|---|---|---|
| GET /api/seats/schedules/{scheduleId} | JWT+X-Queue-Token | 200 | 없음 | scheduleId:long,configVersion:long,asOf:datetime,seats:SelectableSeat[] | 403 QUEUE_TOKEN_INVALID;404 SEAT_NOT_FOUND(회차에 좌석이 없음: 없는 회차·좌석 준비 전을 나누지 않음) |
| GET /api/seats/schedules/{scheduleId}/remaining | 공개 | 200 | 없음 | scheduleId:long,grades:[{venueSeatGradeId:long,gradeName:string,remaining:int}],asOf:datetime,cached:boolean | — |
| POST /api/seats/schedules/{scheduleId}/holds | USER+X-Queue-Token | 201; 동일 유효 선점 200 | scheduleSeatId:long | reservationId:long,scheduleSeatId:long,status:HOLD,holdExpiresAt:datetime,basePrice:int | 400 판매 거절 reasonCode;404 SEAT_NOT_FOUND/SCHEDULE_NOT_FOUND;409 SEAT_ALREADY_TAKEN/PURCHASE_LIMIT_EXCEEDED/SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH;503 SERVICE_UNAVAILABLE |
| DELETE /api/seats/schedules/{scheduleId}/holds/{reservationId} | 본인 JWT | 204 | 없음 | 없음 | 403 FORBIDDEN;404 RESERVATION_NOT_FOUND;409 INVALID_STATUS_TRANSITION |

- SelectableSeat = `{scheduleSeatId:long,venueSeatId:long,section:string,rowLabel:string,seatNumber:int,venueSeatGradeId:long,gradeName:string,basePrice:int|null,selectable:boolean}`. 다른 사용자의 예약 ID/만료 시각 제외.
- 선점 판매 거절은 sale-validations의 reasonCode를 외부 code로 반환: 기본 400, SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH만 409. 삭제·미존재는 404. 연령 미달 오류는 미합의이며 architecture 14절을 따른다.

- 선점 API도 입장 토큰이 없거나 만료되면 Gateway가 403 QUEUE_TOKEN_INVALID로 거절한다. (원본 복원: seat/api.md:124,191, 담당 확인 필요)
- 요청 좌석이 없거나 경로의 scheduleId 소속이 아니면 404 SEAT_NOT_FOUND다. (구현 #148)
- 다른 회차 좌석을 404로 막는 이유는 다른 회차 좌석으로 판매 기간·매수 제한을 우회하지 못하게 하려는 것이다. (구현 #148)
- 판매 검증이 허용이어도 좌석의 config_version·base_price가 검증 응답과 다르면 409 SEAT_CONFIG_VERSION_MISMATCH다. (구현 #148)
- is_sellable=false 좌석은 판매 검증 결과와 상관없이 409 SEAT_NOT_SELLABLE이다. (구현 #148)
- 선점 처리: [트랜잭션 밖] 회차 소속 확인 → 판매 검증 → [트랜잭션] 요청 좌석·이 회원의 HOLD 좌석·요청 좌석을 잡은 예약의 좌석 전체를 id 오름차순으로 한 번에 잠금 → 잠근 뒤의 시각으로 판정 → 만료된 선점은 예약 ID 오름차순으로 expireIfDue하고 그 예약의 좌석을 모두 반환 → countActiveSeats → BookingFacade.hold → 좌석 HOLD. 잠금 순서는 좌석 → 예약이다. (구현 #148)
- 선점 재요청: 잠근 좌석이 이 회원 예약의 만료 전 HOLD면 기존 예약을 200으로 준다. 잠금을 기다리는 사이 같은 회원의 다른 요청이 만든 예약도 잠근 뒤 다시 읽어 200으로 준다. (구현 #148)
- 선점 시간은 10분이고 hold_expires_at은 좌석과 예약에 같은 값이다. HoldTarget의 공연·판매자·회차 시작 시각·공연명은 판매 검증 응답 값이다. (구현 #148)
- 선점 해제는 잠금 없이 BookingFacade.getReservation으로 예약을 먼저 읽어 404·403을 가린다. (원본 복원: seat/api.md:236, 담당 확인 필요)
- 예약이 없거나 예약의 scheduleId가 경로와 다르면 404 RESERVATION_NOT_FOUND, 본인 예약이 아니면 403 FORBIDDEN이다. (원본 복원: seat/api.md:236,256-257, 담당 확인 필요)
- 해제의 회차 확인은 좌석이 아니라 예약으로 한다. 이미 끝난 예약에는 좌석이 남아 있지 않을 수 있다. (원본 복원: seat/api.md:236, 담당 확인 필요)
- 모든 facade가 MANDATORY이므로 호출자 트랜잭션 안에서 비잠금 사전 조회 후 view.scheduleSeatIds 순서로 좌석을 잠그고 BookingFacade.release를 부른다. (원본 복원: seat/api.md:237-238, 담당 확인 필요)
- 좌석 API 응답 시각은 +09:00 오프셋을 포함한 ISO 8601이다. (원본 복원: seat/api.md:15, 담당 확인 필요)

## 예매 — reservation-service / booking

상태: 단건 조회·checkout·confirm·취소는 구현됨. DTO 필드는 아래 표와 같다. checkout의 쿠폰 반영은 구현됐지만(#143), coupon 모듈의 CouponUsage와 연결하기 전에는 쿠폰을 고르면 503이다. 취소 확정 때 좌석과 함께 쿠폰을 돌려준다(#127, #125). 목록은 초안이다.

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| GET /api/reservations/{reservationId} | 본인 USER 또는 MASTER | 200 | 없음 | Reservation | 404 RESERVATION_NOT_FOUND;403 FORBIDDEN |
| POST /api/reservations/{reservationId}/payments/checkout | 본인 USER | 200 | userCouponId?:long,pgProvider:string(TOSS/KAKAO) | orderId:string,amount:int,checkoutUrl:string\|null | 400 VALIDATION_ERROR;409 HOLD_EXPIRED/INVALID_STATUS_TRANSITION;쿠폰 오류;503 SERVICE_UNAVAILABLE |
| POST /api/reservations/{reservationId}/payments/confirm | 본인 USER | 200 | orderId:string(1~64),pgAuthToken:string(1~255) | Reservation | 400 VALIDATION_ERROR;409 HOLD_EXPIRED/INVALID_STATUS_TRANSITION |
| GET /api/reservations | 본인 USER | 200 | Q status?:string,page?:int=0,size?:int=10(10/30/50),sort?:string=createdAt,DESC | PageC<ReservationSummary>(항목 필드 미정) | 구현 전 |
| POST /api/reservations/{reservationId}/cancel | 본인 USER | 200 | reason?:string | Reservation(CANCELING/CANCELED) | 409 NOT_CANCELLABLE/INVALID_STATUS_TRANSITION |

- Reservation = `{reservationId:long,scheduleId:long,scheduleSeatIds:long[],status:string,holdExpiresAt:datetime,orderId:string|null,paymentAmount:long|null}`.

- 공통: checkout·confirm·cancel도 없는 예매면 404 RESERVATION_NOT_FOUND, 본인 예매가 아니면 403 FORBIDDEN이다. (원본 복원: reservation/api.md:42-43, 담당 확인 필요)
- 공통: 지금 상태에서 할 수 없는 요청은 409 INVALID_STATUS_TRANSITION이다. (원본 복원: reservation/api.md:44, reservation/orchestration.md:353, 담당 확인 필요)
- checkout: HOLD이고 선점 만료 전인 예매만 받는다. PAYMENT_IN_PROGRESS에서는 새 준비를 받지 않는다. (원본 복원: reservation/orchestration.md:168,201, 담당 확인 필요)
- checkout: 예매 상태를 바꾸지 않는다. 성공해도 실패해도 예매는 HOLD 그대로다. (원본 복원: reservation/api.md:111, reservation/orchestration.md:154, 담당 확인 필요)
- checkout: 현행 checkout은 새 orderId를 발급하고 원격 준비 뒤 이전 order_id 일치 조건으로 기록한다. 동일 시도 재전송과 새 준비의 구분·순서 보장은 architecture 14절 합의 대상이다. (원본 복원: reservation/orchestration.md:207,229, 담당 확인 필요)
- checkout: 두 준비가 동시에 오면 나중에 반영되는 쪽이 결제창을 받기 전에 오류로 끝난다. (원본 복원: reservation/orchestration.md:210-211,229, 담당 확인 필요)
- checkout: 결제 호출이 실패하거나 결과를 모르면 오류로 응답한다. 이때 상태, order_id, 잡은 쿠폰은 그대로다. (원본 복원: reservation/orchestration.md:209, 담당 확인 필요)
- checkout: 금액은 서버가 정한다. 클라이언트가 보낸 금액은 받지 않는다. (원본 복원: reservation/orchestration.md:202, reservation/booking-decisions.md:55, 담당 확인 필요)
- checkout: 할인 전 금액은 티켓 가격(선점 시점 스냅샷)의 합계다. (원본 복원: reservation/orchestration.md:202, 담당 확인 필요)
- checkout: 할인액은 예매가 min(쿠폰 할인액, max(0, 할인 전 금액 - 최소 결제 금액))으로 자른다. 최소 결제 금액은 100원이다. (원본 복원: reservation/orchestration.md:204, shared/modules/coupon-usage.md:12, 담당 확인 필요)
- checkout: 결제는 받은 할인액을 보정하지 않고 검증만 한다. 최종 금액이 100원 미만이면 결제가 거절한다. (원본 복원: shared/internal-api/payment.md:66,79, 담당 확인 필요)
- checkout: 응답 amount는 totalPrice - discountAmount이다. 예매는 이 값을 payment_amount로 저장하고 승인 요청 금액으로 쓴다. (원본 복원: reservation/tables.md:89, reservation/orchestration.md:175, 담당 확인 필요)
- checkout: 쿠폰을 쓸 수 없으면 400 COUPON_NOT_AVAILABLE이다. 본인 쿠폰이 아니거나, AVAILABLE이 아니거나, 유효 기간이 지난 경우다. 예매는 HOLD 그대로다. (원본 복원: reservation/api.md:111, shared/modules/coupon-usage.md:31, reservation/orchestration.md:329, 담당 확인 필요)
- checkout: 할인 전 금액이 최소 결제 금액 이하인데 쿠폰을 고르면 400 COUPON_NOT_AVAILABLE이다. (원본 복원: reservation/orchestration.md:204, 담당 확인 필요)
- checkout: 쿠폰은 결제 시도가 아니라 예매에 잡힌다. 결제가 실패해 HOLD로 돌아와도 쿠폰은 유지된다. (원본 복원: reservation/orchestration.md:100,108, reservation/api.md:52, 담당 확인 필요)
- confirm: 금액은 받지 않는다. 승인 금액은 예매에 저장된 payment_amount다. (원본 복원: reservation/orchestration.md:221,347, 담당 확인 필요)
- confirm: HOLD, 만료 전, order_id 일치일 때 PAYMENT_IN_PROGRESS로 바꾸고 결제를 부른다. (원본 복원: reservation/orchestration.md:142,218, 담당 확인 필요)
- confirm: 업무 결과는 HTTP 상태가 아니라 200 응답의 status로 알린다. 결제 실패와 결과 모름도 200이다. (원본 복원: reservation/api.md:143,195, 담당 확인 필요)
- confirm: 응답 status는 결제 성공이면 CONFIRMED이다. (원본 복원: reservation/orchestration.md:223,254, 담당 확인 필요)
- confirm: 결제 실패이고 선점 시간이 남았으면 응답 status는 HOLD이다. (원본 복원: reservation/orchestration.md:224,254, 담당 확인 필요)
- confirm: 결제 실패이고 선점 시간이 지났으면 응답 status는 EXPIRED이다. (원본 복원: reservation/orchestration.md:224,254, 담당 확인 필요)
- confirm: 결과를 모르면 응답 status는 PAYMENT_IN_PROGRESS이다. 클라이언트는 GET /api/reservations/{reservationId}로 폴링한다. (원본 복원: reservation/orchestration.md:225,249, 담당 확인 필요)
- confirm: 승인 뒤 결과 반영이 실패해도 오류 대신 200과 현재 상태(PAYMENT_IN_PROGRESS)를 준다. (원본 복원: reservation/booking-decisions.md:61, 담당 확인 필요)
- confirm: 같은 orderId의 승인이 다시 왔을 때 예매가 PAYMENT_IN_PROGRESS나 CONFIRMED이면 결제를 다시 부르지 않는다. 이때 200과 현재 상태를 준다. (원본 복원: reservation/orchestration.md:219,231, reservation/booking-decisions.md:60, 담당 확인 필요)
- confirm: 선점 시간이 지난 뒤의 승인은 만료 스케줄러가 돌았는지와 관계없이 409 HOLD_EXPIRED이다. (원본 복원: reservation/orchestration.md:220, reservation/booking-decisions.md:60, 담당 확인 필요)
- confirm: 마지막 checkout의 orderId가 아닌 승인은 409 ORDER_ID_MISMATCH로 거절한다(현행 enum 확인). (원본 복원: reservation/orchestration.md:229,353, 담당 확인 필요)
- confirm: 결제가 실패해 HOLD로 돌아가면 예매의 order_id와 payment_amount를 지운다. 다시 결제하려면 checkout부터 다시 한다. (원본 복원: reservation/booking-decisions.md:59, reservation/tables.md:86,105, 담당 확인 필요)
- confirm: 지운 orderId로 다시 온 승인은 결제를 부르지 않고 거절한다. (원본 복원: reservation/booking-decisions.md:59, 담당 확인 필요)
- confirm: 지난 시도의 결과가 늦게 와도 현재 시도에 반영하지 않는다. 실패는 무시하고 성공은 그 orderId만 F6 보상 대상으로 남긴다. 자동 환불의 MVP 구현 범위는 architecture 14절에서 조율한다. (원본 복원: reservation/booking-decisions.md:38, reservation/orchestration.md:164, 담당 확인 필요)
- confirm: pgAuthToken은 PAYMENT_IN_PROGRESS 동안만 예매에 남는다. CONFIRMED·HOLD·EXPIRED로 바뀌면 지운다. (원본 복원: reservation/orchestration.md:176, reservation/tables.md:90, 담당 확인 필요)
- 결과 확인: 결제 조회가 404이면 예매는 알림을 남기고 PAYMENT_IN_PROGRESS를 그대로 둔다. (원본 복원: reservation/orchestration.md:242, 담당 확인 필요)
- cancel: CONFIRMED이고 now < scheduleStartAt인 예매만 CANCELING으로 바꾼다. 회차 시작 시각과 같거나 지났으면 409 NOT_CANCELLABLE이다. scheduleStartAt은 선점 때 저장한 값이다. (#126, #127)
- cancel: HOLD·PAYMENT_IN_PROGRESS·EXPIRED·RELEASED이면 409 INVALID_STATUS_TRANSITION이다. (#127)
- cancel: 이미 CANCELING이나 CANCELED이면 결제를 부르지 않고 200과 현재 상태를 준다. 기한도 다시 확인하지 않는다. (#127)
- cancel: 기한은 CONFIRMED→CANCELING 때만 확인한다. 기한 안에 받은 취소는 회차가 시작된 뒤에도 끝까지 처리한다. (#127)
- cancel: reason은 선택값이다. 결제에는 사유 USER_CANCEL을 보낸다. (#127)
- cancel 처리 순서: [트랜잭션] 본인·CONFIRMED·기한 확인 후 CONFIRMED→CANCELING 조건부 변경 → [트랜잭션 밖] 결제 refund(orderId, USER_CANCEL) → [트랜잭션] 좌석 잠금(ID 오름차순) → CANCELING→CANCELED → 좌석 반환·쿠폰 복원. CANCELED가 될 때까지 좌석은 SOLD다. (#127)
- cancel: 환불 대상 orderId는 CONFIRMED에 쓴 예매의 order_id이고 CANCELING에 들어갈 때 고정한다. 이후 재요청도 같은 값을 쓴다. (#127)
- cancel: 결제가 200과 해석 가능한 refundStatus(WAITING·REQUESTED·DONE·FAILED·SKIPPED·NOT_REQUIRED)를 주면 환불 요청을 받은 것으로 보고 CANCELED로 바꾼다. refundStatus 값과 관계없다. (#127)
- cancel: 응답 본문을 해석하지 못했거나 모르는 refundStatus, 타임아웃·5xx, 404 PAYMENT_NOT_FOUND이면 CANCELING을 두고 200 CANCELING을 준다. 404는 잘못된 orderId나 데이터 유실 같은 이상 상황이라 알림을 남긴다. (#127)
- cancel: CANCELING→CONFIRMED로 되돌리는 경로는 없다. 결제가 받은 환불이 PG에서 실패해도 티켓을 되살리지 않는다. (#127)
- 취소 확인 스케줄러: 오래 머문 CANCELING은 결제 조회 없이 같은 orderId로 refund를 다시 요청하고, 받으면 위 순서로 CANCELED까지 마무리한다. 결제는 같은 orderId 재요청에 최초 사유를 보존하고 최신 refundStatus와 금액을 준다. 클라이언트는 GET /api/reservations/{reservationId}로 확인한다. (#127)

## 대기열 — reservation-service / queue

상태: 진입(#100), 순번 조회(#101), 대기 취소(#102)는 구현. 공연 단위 입장 토큰 정책과 구현 완료를 구분한다.

| Method URI | 권한 | 성공 | B | R | 추가 오류 |
|---|---|---|---|---|---|
| POST /api/queues/performances/{performanceId} | JWT | 200 | 없음 | position:long,aheadCount:long,estimatedWaitSeconds:long | 503 QUEUE_DISABLED;503 SERVICE_UNAVAILABLE |
| GET /api/queues/performances/{performanceId}/me | JWT | 200 | 없음 | status:string(WAITING),position:long,aheadCount:long,estimatedWaitSeconds:long | 404 NOT_IN_QUEUE;503 QUEUE_DISABLED;503 SERVICE_UNAVAILABLE |
| DELETE /api/queues/performances/{performanceId}/me | JWT | 204 | 없음 | 없음 | 503 QUEUE_DISABLED;503 SERVICE_UNAVAILABLE |

- 줄은 공연 단위로 하나다. 입장 토큰 범위가 공연이고, 회차들이 같은 서버 자원을 쓰기 때문이다.
- 대기열 경로는 공연 경로(/api/performances) 아래가 아니라 대기열 영역(/api/queues)에 둔다. 대기열은 reservation-service 소유라 공연 담당과 경로를 조율하지 않기 위해서다.
- 줄을 서는 회원은 경로나 본문이 아니라 Gateway가 넘긴 사용자 헤더로 정한다.
- 진입은 이미 서 있으면 원래 순번을 돌려준다. 새로 만들지 않으므로 201이 아니라 200이다.
- position은 1부터 센다. aheadCount는 내 앞 인원이다. estimatedWaitSeconds는 aheadCount ÷ 초당 입장 인원을 올림한 초다.
- 순번 조회는 줄을 선 회원이 몇 초마다 부르는 폴링 API다. status는 지금 WAITING뿐이고, 입장 차례가 된 상태(ENTERED)는 입장 토큰과 함께 추가한다.
- 순번 조회는 부를 때마다 마지막 조회 시각을 남긴다. 이탈자(창을 닫고 떠난 회원)를 찾는 데 쓰는 서버 관리 기록이라 GET으로 둔다.
- 순번 조회 응답에는 Cache-Control: no-store를 붙인다. 캐시되면 요청이 서버에 오지 않아 이탈자로 잘못 판단하기 때문이다.
- 대기 취소는 대기 줄과 마지막 조회 시각에서 함께 빠진다. 뒤에 선 회원의 순번이 그만큼 당겨진다.
- 대기 취소는 이미 줄에 없어도 204다. 응답이 유실돼 다시 보낸 요청이 실패로 보이지 않도록 여러 번 불러도 같은 결과를 준다.
- 취소한 뒤 다시 진입하면 원래 자리가 아니라 맨 뒤에 선다.
- 입장한 회원의 입장권 반납(일찍 나가기)은 대기 취소에 포함하지 않는다. 입장 토큰과 함께 정한다.
- 대기열이 꺼져 있으면(reservation.queue.enabled=false) 503 QUEUE_DISABLED다.
- Redis에 연결하지 못하면 대기열 없이 통과시키지 않고 503 SERVICE_UNAVAILABLE로 거절한다. 통과시키면 막으려던 요청이 예매 서버로 몰리기 때문이다.
- 오픈 전 진입과 판매 중지 공연의 진입은 아직 막지 않는다. 공연 서비스 확인 방식이 architecture 14절 미합의 항목이기 때문이다.

입장 토큰 조회/전달 응답과 세부 오류는 미합의이며 architecture 14절을 따른다. 존재하지 않는 발급 API를 추가하지 않는다.

- 입장 토큰 X-Queue-Token은 공연(performanceId) 단위이고 TTL은 10분이다. (원본 복원: seat/api.md:17, 담당 확인 필요)
- 좌석 선점 여부에 따라 입장 토큰 TTL을 늘리거나 없애지 않는다. (원본 복원: seat/api.md:17, 담당 확인 필요)
- Gateway는 토큰의 회원과 공연 범위를 검증하고, 공연 범위는 경로의 scheduleId로 확인한다. (원본 복원: seat/api.md:17, seat/plan.md:109, 담당 확인 필요)
- 입장 토큰은 좌석 조회(GET /api/seats/schedules/{scheduleId})와 선점(POST /api/seats/schedules/{scheduleId}/holds)에만 적용한다. (원본 복원: seat/api.md:23-25,93,207, 담당 확인 필요)

## 쿠폰 — reservation-service / coupon

상태: 생성·수정·발급 중단·삭제·발급, 목록·상세 조회, 내 쿠폰 조회는 구현됨. 동기201이 현재 API 계약이며202 전환은 architecture 14절의 미합의다.

로그인은 USER/SELLER/MASTER 모두. `PageC<T>={content:T[],page:int,size:int,totalElements:long,totalPages:int,sort:string}`. page=0,size=10(10/30/50). sort는 실제 적용한 정렬을 요청과 같은 `필드,방향` 형식으로 돌려주고, 정렬을 보내지 않으면 기본값 `createdAt,DESC`, 여러 개면 `;`로 잇는다(예: `issueEndAt,ASC;createdAt,DESC`).

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| POST /api/coupons | MASTER | 201 | CouponInput | Coupon | 400 VALIDATION_ERROR |
| PATCH /api/coupons/{couponId} | MASTER | 200 | CouponInput의 변경 필드만 | Coupon | 404 COUPON_NOT_FOUND;409 COUPON_NOT_MODIFIABLE/COUPON_QUANTITY_BELOW_ISSUED/COUPON_ALREADY_CLOSED |
| POST /api/coupons/{couponId}/close | MASTER | 200 | 없음 | Coupon(issueStatus=ENDED) | 404 COUPON_NOT_FOUND;409 COUPON_ALREADY_CLOSED |
| DELETE /api/coupons/{couponId} | MASTER | 204 | 없음 | 없음 | 404 COUPON_NOT_FOUND;409 COUPON_NOT_DELETABLE |
| GET /api/coupons | 로그인 | 200 | Q issueStatus?:string=ISSUING,page?,size?,sort?:string=createdAt,DESC(createdAt/issueStartAt/issueEndAt,ASC/DESC) | PageC<CouponDetail> | 400 VALIDATION_ERROR |
| GET /api/coupons/{couponId} | 로그인 | 200 | 없음 | CouponDetail | 404 COUPON_NOT_FOUND |
| POST /api/coupons/{couponId}/issue | USER | 201 | 없음 | UserCoupon(usedAt 제외,status=AVAILABLE) | 400 COUPON_ISSUE_PERIOD_CLOSED;404 COUPON_NOT_FOUND;409 COUPON_SOLD_OUT/COUPON_ALREADY_ISSUED |
| GET /api/coupons/me | 로그인 | 200 | Q status?:string,page?,size?,sort?:string=createdAt,DESC(createdAt/expiresAt,ASC/DESC) | PageC<UserCoupon> | 400 VALIDATION_ERROR |

- CouponInput = `{name:string(1~100),discountAmount:int>=1,totalQuantity:int>=1,issueStartAt:datetime,issueEndAt:datetime(>start 및 >now),validDays:int>=1}`.
- Coupon = `{couponId:long,name:string,discountAmount:int,totalQuantity:int,remainingQuantity:int,issueStartAt:datetime,issueEndAt:datetime,validDays:int,issueStatus:string}`.
- CouponDetail = Coupon + `{issuedByMe:boolean}`. issuedByMe는 요청한 사용자가 이 쿠폰을 이미 받았는지이며, 사용·만료와 관계없이 받은 적이 있으면 true다. 받을 수 없는 SELLER·MASTER는 false다.
- UserCoupon = `{userCouponId:long,couponId:long,name:string,discountAmount:int,status:string,issuedAt:datetime,expiresAt:datetime,usedAt:datetime|null}`.
- 발급 시작 후 수정 가능: name,issueEndAt,totalQuantity(>=issuedQuantity). 삭제는 issuedQuantity=0일 때만. 발급은 1인 1장.
- 발급이 끝난(ENDED) 쿠폰의 issueEndAt은 바꿀 수 없고 409 COUPON_ALREADY_CLOSED다. 종료 시각을 미래로 바꾸면 중단한 쿠폰이 다시 열리기 때문이다. 다시 발급하려면 새 쿠폰을 만든다.
- 발급의 issuedAt·expiresAt은 공용 Clock(Asia/Seoul) 기준이며 expiresAt = issuedAt + validDays다.
- 이미 받은 회원이 다시 요청하면 쿠폰의 기간·수량과 상관없이 409 COUPON_ALREADY_ISSUED다.
- 발급 기간은 요청 시각으로 판단한다. 관리자가 발급을 중단하는 동안 먼저 도착해 기다리던 요청은 중단 뒤에도 발급될 수 있다.
- 내 쿠폰은 요청한 사용자 본인의 쿠폰만 보여 주고, status를 보내지 않으면 전부다. 받은 쿠폰이 없는 SELLER·MASTER는 빈 목록이다.

- 모든 쿠폰 API는 토큰이 없거나 유효하지 않으면 401 UNAUTHORIZED다. (원본 복원: coupon/api.md:90,125,145,178,202,219,246,283, 담당 확인 필요)
- MASTER 전용 API(생성·수정·발급 중단·삭제)는 MASTER가 아니면 403 FORBIDDEN이다. (원본 복원: coupon/api.md:91,126,146,179, 담당 확인 필요)
- 발급은 @RequireRole(Role.USER)이고 역할이 정확히 같은지만 보므로 MASTER·SELLER는 403 FORBIDDEN이다. (원본 복원: coupon/api.md:25,247, 담당 확인 필요)
- 발급 수량은 조건부 UPDATE로 issued_quantity를 올려 초과 발급을 막는다. (원본 복원: coupon/api.md:228, coupon/tables.md:67, 담당 확인 필요)
- 같은 회원의 중복 발급은 uk_p_user_coupons_user_coupon 위반으로 막고 409 COUPON_ALREADY_ISSUED로 응답한다. (원본 복원: coupon/api.md:228, coupon/tables.md:118, 담당 확인 필요)
- 삭제는 행을 지우지 않고 deleted_at·deleted_by를 기록하는 논리 삭제다. 삭제된 쿠폰은 모든 조회와 발급에서 빠진다. (원본 복원: coupon/api.md:156, 담당 확인 필요)
- 삭제는 `issued_quantity = 0 AND deleted_at IS NULL` 조건부 UPDATE 한 번으로 한다. 삭제와 첫 발급이 동시에 오면 하나만 성공한다. (원본 복원: coupon/api.md:165-170, 담당 확인 필요)
- 삭제 UPDATE가 0행이면 409 COUPON_NOT_DELETABLE이다. 쿠폰이 없거나 이미 삭제됐으면 먼저 조회해 404 COUPON_NOT_FOUND로 구분한다. (원본 복원: coupon/api.md:172,180-181, 담당 확인 필요)
- 내 쿠폰 조회는 유효 기간이 지났지만 만료 배치가 아직 처리하지 않은 쿠폰을 EXPIRED로 보여 주고 AVAILABLE 조회에서 뺀다. (원본 복원: coupon/api.md:258, 담당 확인 필요)

## 결제 내부 — payment-service

상태: HTTP 계약 합의. 준비·승인·조회·환불 Controller 구현. 관리자 운영 API는 아래 별도 절을 따른다.

권한: 내부 예매 호출(서비스 인증 방식 미정). orderId는 예매가 시도마다 발급하는 1~64자 문자열, 같은 시도 재시도는 같은 값. 준비/승인/환불은 orderId 멱등. HTTP 200에도 업무 FAILED가 올 수 있다.

결제 HTTP 계약은 합의 상태(#22·PR #25), 구현 완료와 구분한다. D16에 따라 고도화에도 준비·승인 동기 계약을 유지한다.

| Method URI | 성공 | B | R | 추가 오류 |
|---|---|---|---|---|
| POST /internal/payments | 200 | orderId:string,reservationId:long,userId:long,sellerId:long,performanceId:long,scheduleId:long,totalPrice:int>0,discountAmount:int>=0,pgProvider:string,expiresAt:datetime | paymentId:long,orderId:string,amount:int>=100,status:READY/FAILED,failureType:string?,failureCode:string?,checkoutUrl:string\|null | 400 INVALID_PAYMENT_AMOUNT;409 PAYMENT_ORDER_CONFLICT/PAYMENT_ALREADY_IN_PROGRESS/PAYMENT_ALREADY_DONE/PAYMENT_CHECKOUT_CONFLICT |
| POST /internal/payments/{orderId}/confirm | 200 | pgAuthToken:string,amount:int | paymentId:long,status:DONE/FAILED/IN_PROGRESS,failureType:string?,failureCode:string? | 400 PAYMENT_AMOUNT_MISMATCH;404 PAYMENT_NOT_FOUND |
| GET /internal/payments/{orderId} | 200 | 없음 | paymentId:long,orderId:string,reservationId:long,status:string,amount:int,failureType:string?,failureCode:string?,confirmRequestedAt:datetime\|null,approvedAt:datetime\|null,refund:RefundView\|null | 404 PAYMENT_NOT_FOUND |
| POST /internal/payments/{orderId}/refund | 200 | reason:USER_CANCEL/SCHEDULE_CANCELED/CONFIRM_FAILED | refundStatus:WAITING/REQUESTED/DONE/FAILED/SKIPPED/NOT_REQUIRED,refundAmount:int | 404 PAYMENT_NOT_FOUND |

- RefundView = `{status:string,reason:string,refundAmount:int,refundedAt:datetime|null}`. amount=totalPrice-discountAmount, 보정 없이 100원 미만 거절. 동일 orderId의 할인액 변경도 PAYMENT_ORDER_CONFLICT.
- 준비 재요청은 기존 응답; 이전 READY는 SUPERSEDED로 종료하되 승인 접수된 READY/IN_PROGRESS는 대체 불가. DONE 결제 존재 시 새 준비 불가.
- 실패 구분: REJECTED(PG_REJECTED/AMOUNT_MISMATCH/INVALID_AUTH_TOKEN), UNAVAILABLE(PG_UNAVAILABLE/PG_BUSY/PG_UNRESOLVED), EXPIRED(EXPIRED), NOT_APPROVED(NOT_APPROVED/CANCELED_AT_PG), CLOSED(SUPERSEDED/ABANDONED/READY_FAILED).
- 환불은 전액. READY→FAILED(ABANDONED), FAILED는 NOT_REQUIRED(환불 행 없음); IN_PROGRESS는 WAITING, DONE은 REQUESTED 후 PG 취소, CANCELED는 기존 환불. 접수와 PG 취소 완료는 다르다.
- 기존 환불 행이 있으면 최초 사유와 상태를 먼저 재현한다. NOT_REQUIRED의 refundAmount는 0이다.

## 결제 관리자 — payment-service (#88 브랜치 구현)

권한: MASTER. 외부 ApiResponse/ErrorResponse 적용. Gateway 인증 경계는 infra 12절을 따른다.

| Method URI | 성공 | 입력 | data | 추가 오류 |
|---|---|---|---|---|
| GET /api/admin/payments/refunds | 200 | Q: after:long>=0=0,limit:int[1,100]=20,status?:RefundStatus,mode?:RefundRecoveryMode,provider?:PgProvider,olderThanMinutes?:long[0,5256000] | items:OperationView[],nextCursor:long\|null | 400 VALIDATION_ERROR |
| GET /api/admin/payments/{orderId}/refund | 200 | Q: refresh:boolean=false | current:OperationView,commands:OperationHistory[최대20],pgObservation:string,observedAt:datetime\|null | 404 PAYMENT_NOT_FOUND/REFUND_NOT_FOUND |
| POST /api/admin/payments/{orderId}/refund/retry | 200 | B: OperationInput | OperationOutcome | 404 PAYMENT_NOT_FOUND/REFUND_NOT_FOUND;409 REFUND_COMMAND_CONFLICT/INVALID_STATUS_TRANSITION;503 SERVICE_UNAVAILABLE |
| POST /api/admin/payments/{orderId}/refund/resume | 200 | B: OperationInput | OperationOutcome | 위와 같음 |

- OperationInput=`{commandId:string[8,80],reason:RESUME_OBSERVATION/ROOT_CAUSE_FIXED/AUTH_CONFIGURATION_FIXED,expectedStatus:RefundStatus,expectedMode:RefundRecoveryMode,expectedVersion:long>=0,expectedAttempt:int>=0}` 모든 필드 필수. commandId는 영숫자·`_`·`-`, orderId는 기존 결제 계약과 같은 비공백 1~64자.
- OperationOutcome=`{commandId:string,result:APPLIED/CONFIRMED_CANCELED,explanation:string,status:RefundStatus,mode:RefundRecoveryMode,executionVersion:long,cancelAttempt:int,processedAt:datetime}`.
- OperationView: refundId:long,orderId:string,paymentStatus:PaymentStatus,status:RefundStatus,mode:RefundRecoveryMode,provider:PgProvider,refundReason:RefundReason,amount:int,executionVersion:long,cancelAttempt:int,automaticallyQueryable:boolean,automaticCancelAllowed:boolean,guidance:string.
- OperationView 추가: rowCreatedAt/requestedAt/restrictionStartedAt/nextAttemptAt/paymentNextAttemptAt/paymentNextReconcileAt/cancelKeyCreatedAt:datetime|null,restrictionReason/lastError/failureCode:string|null.
- OperationHistory: commandId/action/actor/reason/result/explanation/expectedState/beforeState/afterState/inquiry:string,processedAt:datetime.
- 목록은 미완료 환불만 반환하며 after/nextCursor는 refund ID다. 상세의 refresh=true만 PG 읽기 전용 조회를 수행한다. pgObservation=NOT_QUERIED/APPROVED/CANCELED_REQUIRES_VALIDATION/INCONSISTENT/NOT_FOUND/UNKNOWN.
- 같은 사용자·commandId·입력은 최초 결과를 재현한다. 다른 입력/사용자는409 REFUND_COMMAND_CONFLICT. STALE/DENIED는409,PG_UNKNOWN은503이며 명령 결과는 저장한다. 동일 명령의 결과 불명은 같은 commandId로 확인한다.
- retry 허용: AUTH_CONFIGURATION_FIXED는 인증 거절(TOSS UNAUTHORIZED_KEY, KAKAO -401), ROOT_CAUSE_FIXED는 조회 전용의 거래·금액 불일치와 업무 거절(FAILED·PAUSED, #164). 모두 PG 조회로 같은 거래의 승인 전액이 그대로이고 자동 쓰기 기간 안일 때만 APPLIED, 결과 불명·부분 취소·기간 초과는 거절.
- resume는 조회 전용 재개, retry의 APPLIED는 기존 Recovery에 실행을 예약한 상태다. PG 취소·고객 계좌 반영 완료를 뜻하지 않는다. 실행자·명령 종류는 본문에서 받지 않는다.

## 서비스 내부 모듈 계약

상태: BookingFacade는 합의·구현됨(쿠폰 호출 제외). HoldTarget 두 필드 추가와 CANCELING 집계는 합의·구현됨(#126). 좌석의 판매 검증 응답 연결은 선점 API에서 한다. SeatFacade는 합의·구현됨(#80), CouponUsage는 합의(#125)이며 구현 전이다.

| 제공 → 소비 | 상태 | 메서드/타입 |
|---|---|---|
| booking → seat | 합의 #34, #126 | hold(Long userId,HoldTarget target,List<SeatLine>,LocalDateTime holdExpiresAt):Long; getReservation(Long):Optional<ReservationView>; release(Long userId,Long reservationId):ReleaseResult |
| booking → seat | 합의 #34 | countActiveSeats(Long userId,Long scheduleId):int; findActiveHold(Long userId,Long scheduleId):Optional<ReservationView>; expireIfDue(Long reservationId):boolean; findPaymentInProgress(Collection<Long>):Set<Long> |
| seat → booking | 합의 #34; 구현됨(#80) | lockSeats(Collection<Long>):List<SeatLock>; sell(Collection<Long>,Long reservationId):int; release(Collection<Long>,Long reservationId):int |
| coupon → booking | 합의 #125 | reserve(Long userCouponId,Long userId,Long reservationId):CouponDiscount; use(Long reservationId); release(Long reservationId) |

- HoldTarget=`{scheduleId:Long,performanceId:Long,sellerId:Long,scheduleStartAt:LocalDateTime,performanceTitle:String}` (#86, #126); SeatLine=`{scheduleSeatId:Long,price:int}`; ReservationView=`{reservationId:Long,userId:Long,scheduleId:Long,state:ReservationState,holdExpiresAt:LocalDateTime,scheduleSeatIds:List<Long>}`; SeatLock=`{scheduleSeatId:Long,state:SeatState,reservationId:Long,holdExpiresAt:LocalDateTime}`.
- ReleaseResult=RELEASED/EXPIRED/ALREADY_CLOSED/NOT_RELEASABLE; facade 전용 enum은 domain enum을 노출하지 않는다. 트랜잭션 규칙은 architecture 참조.
- CouponDiscount=`{userCouponId:Long,couponId:Long,name:String,discountAmount:int}`. CouponUsage는 `coupon.application.facade`에 두고, 예매는 이 패키지만 쓴다. (#125)
- CouponUsage 전파는 REQUIRED다. 예매 트랜잭션에 합류해 함께 커밋·롤백된다. (#125)
- reserve: 본인 쿠폰이 아니거나, AVAILABLE이 아니거나, 유효 기간이 지났으면 COUPON_NOT_AVAILABLE 예외다. 예매는 이 오류를 checkout 400으로 그대로 낸다. (#125)
- reserve: discountAmount는 발급 시점 할인액 그대로다. 최소 결제 금액에 맞춰 자르는 일은 예매가 한다. 쿠폰은 정가를 모르기 때문이다. (#125)
- reserve: 같은 예매에 같은 쿠폰이 다시 오면 이미 잡힌 값을 돌려준다. 예매에 다른 쿠폰이 잡혀 있으면 예외다. (#125)
- 쿠폰 교체: 예매는 기존 쿠폰의 release를 먼저 부르고 새 reserve를 같은 트랜잭션에서 부른다. 예매 하나에 쿠폰은 하나만 잡힌다(uk_p_user_coupons_reservation). (#125)
- use: RESERVED를 USED로 바꾼다. 유효 기간이 지났어도 USED다. 이미 USED면 아무것도 하지 않는다. (#125)
- release: RESERVED와 USED를 되돌린다. 유효 기간이 남았으면 AVAILABLE, 지났으면 EXPIRED이고 reservation_id를 지운다. 만료 배치는 RESERVED·USED를 건드리지 않으므로 이 판정은 release가 공용 Clock으로 한다. (#125)
- release: 대상이 없거나 이미 되돌렸어도 성공한다. 예매 스케줄러가 같은 예매로 다시 부를 수 있기 때문이다. (#125)
- 예매는 checkout에서 reserve, 결제 성공 확정에서 use, 결제 실패 후 만료·선점 만료·선점 해제·취소 확정에서 release를 부른다. 결제 실패로 HOLD에 돌아가거나 결과를 모르면 부르지 않는다. (#125)
- 좌석 준비(공연→seat): MVP Feign 계약의 URI·응답·타임아웃 미합의. 요청 초안=`{requestId:string,scheduleId:long,configVersion:long,seats:[{venueSeatId:long,venueSeatGradeId:long,gradeName:string,basePrice:int|null,isSellable:boolean,section:string,rowLabel:string,seatNumber:int}]}`. Kafka 전환 후 Requested/Completed/Failed의 토픽·스키마·버전은 별도 합의. 미확정 HTTP 경로나 메시지 계약을 생성하지 않는다.

- BookingFacade 공통: 상태를 바꾸지 못한 경우(0행)는 예외가 아니라 반환값으로 알린다. 다음 행동은 호출자가 고른다. (원본 복원: shared/modules/booking-facade.md:17, 담당 확인 필요)
- hold: HoldTarget의 다섯 값 중 하나라도 null이면 INVALID_REQUEST다. performanceId·sellerId(#86)와 scheduleStartAt(KST)·performanceTitle(#126)은 판매 검증(P21) 응답 값이다.
- hold: HOLD 예매와 좌석마다 티켓을 만든다. 티켓 가격은 넘겨받은 선점 시점 가격이다. (원본 복원: shared/modules/booking-facade.md:40, 담당 확인 필요)
- hold: 1차는 좌석 목록 길이1이다. 같은 좌석이 두 번 들어오거나 가격이 음수면 INVALID_REQUEST이다. (원본 복원: shared/modules/booking-facade.md:41, 담당 확인 필요)
- hold: 활성 예매(HOLD·PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING)가 이미 있으면 부분 UNIQUE 위반을 잡지 않고 그대로 던진다. 이 예외는 호출 안에서 드러난다. (원본 복원: shared/modules/booking-facade.md:42, 담당 확인 필요)
- getReservation: 상태와 상관없이 예매를 읽는다. 없으면 빈 값이다. (원본 복원: shared/modules/booking-facade.md:61, 담당 확인 필요)
- getReservation: scheduleSeatIds는 오름차순이고, 호출자는 이 순서로 좌석을 잠근다. (원본 복원: shared/modules/booking-facade.md:56, 담당 확인 필요)
- release: 없는 예매면 RESERVATION_NOT_FOUND, 남의 예매면 FORBIDDEN 예외다. 그 밖의 경우는 모두 ReleaseResult로 알린다. (원본 복원: shared/modules/booking-facade.md:80, 담당 확인 필요)
- release: 결제 시작과 해제가 동시에 오면 한쪽만 성공한다. 진 쪽의 결과는 NOT_RELEASABLE이다. (원본 복원: shared/modules/booking-facade.md:81, 담당 확인 필요)
- release: 쿠폰 연동 시 잡아 둔 쿠폰은 booking이 같은 트랜잭션에서 CouponUsage.release로 해제한다(현행 미연결). seat는 쿠폰을 다루지 않는다. (원본 복원: shared/modules/booking-facade.md:82, 담당 확인 필요)
- countActiveSeats: 예매 수가 아니라 활성 예매의 티켓 수를 센다. (원본 복원: shared/modules/booking-facade.md:88,97, 담당 확인 필요)
- countActiveSeats: 집계 대상은 HOLD·PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING이다. 취소 중에도 좌석은 SOLD이고 유효한 예매다. (#126)
- findActiveHold: 이 회원의 이 회차 HOLD 예매를 만료 여부와 상관없이 돌려준다. (원본 복원: shared/modules/booking-facade.md:89, 담당 확인 필요)
- expireIfDue: `status=HOLD AND hold_expires_at<=:now`일 때만 EXPIRED로 바꾼다. 쿠폰 연동 시 같은 트랜잭션에서 해제한다(현행 미연결). (원본 복원: shared/modules/booking-facade.md:90, 담당 확인 필요)
- expireIfDue: PAYMENT_IN_PROGRESS면 선점 시간이 지났어도 false이다. (원본 복원: shared/modules/booking-facade.md:98, 담당 확인 필요)

- findPaymentInProgress에 빈 목록을 넘기면 쿼리 없이 빈 집합을 돌려준다. (원본 복원: shared/modules/booking-facade.md:99, 담당 확인 필요)
- 결제 성공 확정: booking은 lockSeats로 좌석이 이 예약의 HOLD인지 먼저 확인하고, 예약을 PAYMENT_IN_PROGRESS→CONFIRMED(조건부)로 바꾼 뒤 sell을 부른다. (원본 복원: shared/modules/seat-facade.md:51, 담당 확인 필요)
- 확정 때 좌석이 이 예약 것이 아니면 확정하지 않고 CONFIRM_FAILED 보상 대상으로 남긴다. CANCELING 전이와 MVP 구현 범위는 architecture 14절에서 조율한다. (원본 복원: shared/modules/seat-facade.md:51, 담당 확인 필요)
- 선점 만료 스케줄러: 후보를 잠금 없이 조회하고, 예약마다 lockSeats → HOLD→EXPIRED(조건부) → 1건이면 release 순서로 처리한다. (원본 복원: shared/modules/seat-facade.md:52, 담당 확인 필요)
- 결제 실패이고 시간이 지났으면 lockSeats → PAYMENT_IN_PROGRESS→EXPIRED → release 순서다. (원본 복원: shared/modules/seat-facade.md:53, 담당 확인 필요)
- 예매 취소: CONFIRMED→CANCELING 커밋 → 환불 요청 접수 → lockSeats(ID 오름차순) → CANCELING→CANCELED → release 순서다. 기존 잠금 순서(좌석 → 예약)와 같다. CANCELING 예매의 좌석은 BookingFacade.release로 풀지 않는다(NOT_RELEASABLE). (#127)
- 좌석 준비 저장은 한 트랜잭션에서 전부 반영하거나 전부 반영하지 않는다. (원본 복원: seat/tables.md:111, 담당 확인 필요)
- 좌석 준비 응답 초안=`{scheduleId:long,configVersion:long,seatCount:int,sellableCount:int}`. 타입은 미합의다. (원본 복원: shared/internal-api/seat.md:34-35, 담당 확인 필요)
- CouponUsage: 준비·승인 응답이 유실돼 PAYMENT_IN_PROGRESS가 유지되면 쿠폰을 해제하지 않는다. 같은 orderId 조회·재요청으로 결과를 확인한다. (원본 복원: shared/modules/coupon-usage.md:41, 담당 확인 필요)

## Fake PG — fake-pg:8090

상태: 구현됨. 아래 제공자별 API는 모의 계약이며 실PG 검증 완료를 뜻하지 않는다.

외부 PG 호환 형식이며 공통 래퍼를 적용하지 않는다. Gateway 비노출. 아래는 실제 PG 계약이 아닌 현재 모의 서버 계약이다.

### TOSS

인증 `Authorization: Basic base64(secretKey+":")`. 오류 `{code:string,message:string}`. 시각 +09:00.

| Method URI | B/헤더 | 성공 R |
|---|---|---|
| POST /v1/payments/confirm | paymentKey:string,orderId:string,amount:int; Idempotency-Key? | 200 TossPayment(status=DONE) |
| GET /v1/payments/orders/{orderId} | 없음 | 200 TossPayment |
| GET /v1/payments/{paymentKey} | 없음 | 200 TossPayment |
| POST /v1/payments/{paymentKey}/cancel | cancelReason:string,cancelAmount?:int; Idempotency-Key? | 200 TossPayment(status=CANCELED) |
| POST /fake/toss/checkout | orderId:string,amount:int,orderName?:string | 200 paymentKey:string,orderId:string,amount:int,authExpiresAt:datetime |

- TossPayment=`{mId:string,version:string,paymentKey:string,type:string,orderId:string,orderName:string,currency:string,method:string,totalAmount:int,balanceAmount:int,status:string,requestedAt:datetime,approvedAt:datetime|null,cancels:[{cancelAmount:int,cancelReason:string,canceledAt:datetime,transactionKey:string}]}`.
- E: 400 INVALID_REQUEST/ALREADY_PROCESSED_PAYMENT/ALREADY_CANCELED_PAYMENT;401 UNAUTHORIZED_KEY;403 REJECT_CARD_PAYMENT/NOT_CANCELABLE_PAYMENT;404 NOT_FOUND_PAYMENT/NOT_FOUND_PAYMENT_SESSION;409 IDEMPOTENT_REQUEST_PROCESSING;422 INVALID_IDEMPOTENCY_KEY;429 TOO_MANY_REQUESTS;500 FAILED_INTERNAL_SYSTEM_PROCESSING.

### KAKAO

인증 `Authorization: SECRET_KEY {secretKey}`. snake_case JSON, cid=TC0ONETIME, quantity=1, 전액 취소. 시각은 오프셋 없는 KST. 오류 `{error_code:int,error_message:string}`. 멱등 헤더 없음; 기승인/기취소는 원 tid 조회로 확인한다.

| Method URI | B | 성공 R |
|---|---|---|
| POST /online/v1/payment/ready | cid:string,partner_order_id:string,partner_user_id:string,item_name:string,quantity:int,total_amount:int,tax_free_amount:int,approval_url:string,cancel_url:string,fail_url:string | 200 tid:string,next_redirect_pc_url:string,next_redirect_mobile_url:string,next_redirect_app_url:string,android_app_scheme:string,ios_app_scheme:string,created_at:datetime |
| POST /online/v1/payment/approve | cid:string,tid:string,partner_order_id:string,partner_user_id:string,pg_token:string | 200 KakaoPayment |
| POST /online/v1/payment/order | cid:string,tid:string | 200 KakaoPayment |
| POST /online/v1/payment/cancel | cid:string,tid:string,cancel_amount:int,cancel_tax_free_amount:int | 200 KakaoPayment(status=CANCEL_PAYMENT) |
| POST /fake/kakao/approve | tid:string | 200 tid:string,partner_order_id:string,pg_token:string |

- KakaoPayment=`{aid:string,tid:string,cid:string,status:string,partner_order_id:string,partner_user_id:string,payment_method_type:string,amount:Money,canceled_amount:Money,cancel_available_amount:Money,item_name:string,quantity:int,created_at:datetime,approved_at:datetime|null,canceled_at:datetime|null}`; Money=`{total:int,tax_free:int,vat:int,point:int,discount:int}`.
- E: 400 -2(입력),-701(사용자 인증 전),-702(기승인/종료),-703(tid 없음/만료),-704(조회/취소 대상 없음),-721(기취소),-722(취소 불가),-780(승인 거절);401 -401;429 -429;500 -500.
- 같은 주문의 재준비는 새 tid로 이전 tid를 무효화한다. 같은 tid 사용자 인증은 같은 pg_token. 주문번호로 카카오 거래 조회 불가.

### 장애 관리

| Method URI | 요청 | 성공 R |
|---|---|---|
| GET /fake/admin/faults | 없음 | 200 장애 설정 맵(provider.operation→Fault) |
| GET /fake/admin/faults/{provider}/{operation} | 없음 | 200 Fault |
| PUT /fake/admin/faults/{provider}/{operation} | Fault | 200 Fault |
| DELETE /fake/admin/faults | 없음 | 204 |
| GET /fake/admin/faults/stats | 없음 | 200 장애 적용 횟수 맵(provider.operation→FaultStats); 장애 설정 초기화로 비워지지 않음 |
| DELETE /fake/admin/faults/stats | 없음 | 204 |
| GET /fake/admin/faults/seed | 없음 | 200 `{seed:long|null}` |
| PUT /fake/admin/faults/seed | seed?:long(생략=실행마다 다른 난수) | 200 `{seed:long|null}`; 확률 판단 순서를 처음부터 다시 시작. 기동 시 기본값은 `FAKE_PG_FAULT_SEED` |
| GET /fake/admin/transactions | Q orderId?:string,status?:string,limit?:int=100(1~1000 보정) | 200 `TransactionView[]`; 최신순 |
| POST /fake/admin/transactions/{provider}/{orderId}/external-cancel | cancelAmount?:int(생략=전액),reason?:string | 200 TransactionView; 우리 API를 거치지 않은 취소. 승인액 미만이면 PARTIAL_CANCELED. E 404 NOT_FOUND_PAYMENT, 403 NOT_CANCELABLE_PAYMENT(DONE 아님), 400 INVALID_REQUEST(금액 범위) |
| DELETE /fake/admin/transactions | 없음 | 204 거래/멱등 기록 초기화 |

- provider=TOSS/KAKAO; operation=READY(KAKAO만)/CONFIRM/INQUIRE/CANCEL.
- Fault=`{latencyMs?:long>=0,serverErrorRate?:double[0,1],failRate?:double[0,1],responseLossRate?:double[0,1],delayedApprovalRate?:double[0,1],delayedApprovalMs?:long>=0,holdMs?:long>=0(기본15000),rateLimitPerSecond?:int>=0(0=무제한),tamperRate?:double[0,1],tamperMode?:MISSING_FIELDS|OTHER_TRANSACTION|WRONG_AMOUNT}`. 생략은 장애 없음. tamper는 처리·저장은 정상이고 내보내는 2xx 본문만 바꾼다(거래 키·금액·시각 제거, 다른 거래 키·주문번호, 금액+1000). tamperRate>0이면 tamperMode 필수. FaultStats=`{requests,latencyApplied,rateLimited,serverErrors,rejections,delayedApprovals,responseLosses,tampered:long}`; requests는 429·멱등 재현 포함, rejections는 거절을 정한 횟수. 같은 seed라도 동시 요청은 도착 순서에 따라 거래별 장애가 달라질 수 있다.

- TransactionView=`{provider:string,paymentKey:string,orderId:string,amount:int,status:string,requestedAt:datetime,approvedAt:datetime|null,canceledAt:datetime|null,canceledAmount:int,cancelTransactionKey:string|null,failureCode:string|null}`.
