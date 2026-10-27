# API 명세

기준: `origin/develop` 7a48c5e(공연 #54 포함), 2026-10-27. 저장소에 옮겨진 Notion 명세를 통합했다. 실시간 Notion 대조는 미실시다.
표기는 `필드:타입`, `?`는 생략 가능, `|null`은 null 허용, `[]`는 배열이다. R은 외부 `ApiResponse.data` 또는 내부 원문이다. 공통 응답·인증·시각·기본 오류는 [conventions.md](conventions.md), enum·저장은 [tables.md](tables.md), 상태 전이·미합의 사항은 [architecture.md](architecture.md)를 따른다. 명세 존재는 구현 완료를 뜻하지 않는다.

## 회원 — user-service

공개 가입·로그인 외에는 본인 JWT. B=본문, Q=쿼리, R=응답.

| Method URI | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|
| POST /api/auth/signup | 201 | Signup | SignupResult(role=USER) | 409 DUPLICATE_EMAIL |
| POST /api/auth/seller/signup | 201 | Signup | SignupResult(role=SELLER) | 409 DUPLICATE_EMAIL |
| POST /api/auth/login | 200 | email:string, password:string | accessToken:string, tokenType:string=Bearer, expiresIn:int=3600 | 401 LOGIN_FAILED |
| GET /api/users/me | 200 | 없음 | User | 404 USER_NOT_FOUND |
| PATCH /api/users/me | 200 | name:string, phone:string(선택/null 정책 미정) | User | 미정; 구현 전 |
| DELETE /api/users/me | 200(기존 예시 기준) | 없음 | userId:long, status:WITHDRAWN, withdrawnAt:datetime | 미정; 구현 전 |
| GET /internal/users/{userId} | 200 | 없음 | userId:long, email:string, role:string | 내부 호출, 조회 멱등;404 USER_NOT_FOUND |

- Signup = `{email:string(이메일), password:string(8~15자·대소문자/숫자/특수문자), name:string(공백 불가), phone:string(01[016789]-숫자3~4-숫자4)}`. 역할은 서버 고정. 탈퇴 이메일도 중복 거절.
- SignupResult = `{userId:long,email:string,name:string,role:string,status:string}`.
- User = `{userId:long,email:string,name:string,phone:string,role:string,status:string,createdAt:datetime,updatedAt:datetime}`.

## 공연 — performance-service

33개 계약. Path ID는 양의 long. 공연 목록은 `PageP<T>={items:T[],page:int,size:int,totalElements:long}`. 기본 page=0,size=20(1~100). Idempotency-Key는 공연·회차 생성/회차 취소의 권장 초안이며 범위·보존 기간 미정이다.
If-Match는 큰따옴표로 감싼 대상 version. 누락 428 PRECONDITION_REQUIRED, 충돌 409 VERSION_CONFLICT. 하위 등급/좌석은 venue.version, 가격/제외는 performance.version, 회차 명령은 schedule.version. 회차 생성에는 If-Match 없음.
오류 별칭은 현행 common에 맞춰 UNAUTHENTICATED → UNAUTHORIZED로 정리했다. INVALID_REQUEST는 CommonErrorCode에 실제 존재하므로 VALIDATION_ERROR와 임의 합치지 않는다.

### POST /api/admin/venues

- 권한: MASTER; 성공: 201; 헤더: Location=`/api/seller/venues/{venueId}`.
- B: `name:string[공연장명. 1~100자.]; address:string[주소. 1~255자.]; region:string[지역. 1~20자.]`.
- R: P1.
- E: 400 INVALID_REQUEST.

### GET /api/seller/venues

- 권한: SELLER / MASTER; 성공: 200.
- Q: `region?:string[지역. 생략 시 전체.]; keyword?:string[검색어. 생략 시 필터 없음.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P2.
- E: 400 INVALID_REQUEST.

### GET /api/seller/venues/{venueId}

- 권한: SELLER / MASTER; 성공: 200; 헤더: 응답 ETag.
- R: P1.
- E: 400 INVALID_REQUEST; 401 UNAUTHORIZED; 403 FORBIDDEN; 404 VENUE_NOT_FOUND.

### POST /api/admin/venues/{venueId}/seat-grades

- 권한: MASTER; 성공: 201; 헤더: If-Match.
- B: `grades:GradeInput[][1개 이상. 공연장 내 동일 이름 중복 불가.]; grades[].name:string[등급명. 1~20자, 공백 불가.]`.
- R: P3.
- E: 400 INVALID_REQUEST.

### GET /api/seller/venues/{venueId}/seat-grades

- 권한: SELLER / MASTER; 성공: 200.
- R: P3.
- E: 400 INVALID_REQUEST.

### PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId}

- 권한: MASTER; 성공: 200; 헤더: If-Match.
- B: `name:string[새 등급명. 1~20자, 공연장 내 유일.]`.
- R: P4.
- E: 400 INVALID_REQUEST.

### DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId}

- 권한: MASTER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 409 VENUE_GRADE_IN_USE; 409 VENUE_LAYOUT_IN_USE; 428 PRECONDITION_REQUIRED; 409 VERSION_CONFLICT.

### POST /api/admin/venues/{venueId}/seats

- 권한: MASTER; 성공: 201; 헤더: If-Match.
- B: `seats:SeatInput[][1개 이상. 동일 구역·열·번호 중복 불가.]; seats[].section:string[구역. 1~20자.]; seats[].rowLabel:string[열. 1~10자.]; seats[].seatNumber:integer[좌석 번호. 1 이상.]; seats[].venueSeatGradeId:integer[동일 공연장의 고정 등급 ID.]`.
- R: P5.
- E: 400 INVALID_REQUEST.

### GET /api/seller/venues/{venueId}/seats

- 권한: SELLER / MASTER; 성공: 200.
- R: P6.
- E: 400 INVALID_REQUEST.

### PATCH /api/admin/venues/{venueId}/seats/{seatId}

- 권한: MASTER; 성공: 200; 헤더: If-Match.
- B: `section?:string[구역. 1~20자.]; rowLabel?:string[열. 1~10자.]; seatNumber?:integer[좌석 번호. 1 이상.]; venueSeatGradeId?:integer[동일 공연장의 고정 등급 ID.]`.
- R: P7.
- E: 400 INVALID_REQUEST.

### DELETE /api/admin/venues/{venueId}/seats/{seatId}

- 권한: MASTER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 409 VENUE_LAYOUT_IN_USE; 428 PRECONDITION_REQUIRED; 409 VERSION_CONFLICT.

### POST /api/seller/performances

- 권한: SELLER; 성공: 201; 헤더: Location=`/api/seller/performances/{performanceId}`.
- B: `venueId:integer[미삭제 공연장 ID.]; title:string[공연명. 1~200자.]; genre:string[CONCERT / MUSICAL / PLAY / SPORTS.]; ageRating:string[ALL / 12 / 15 / 19.]; runningTime:integer[공연 시간(분). 1 이상.]; castInfo?:string|null[최대 500자. 생략하면 null.]; description?:string|null[초안에서는 생략 가능. 공개 조건은 별도 검증.]; posterUrl?:string|null[최대 500자 HTTPS URL. 초안에서는 생략 가능.]`.
- R: P8.
- E: 400 INVALID_REQUEST.

### GET /api/seller/performances

- 권한: SELLER; 성공: 200.
- Q: `status?:string[공개 상태 필터 DRAFT / PUBLISHED. 생략 시 전체.]; keyword?:string[검색어. 생략 시 필터 없음.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P9.
- E: 400 INVALID_REQUEST.

### GET /api/seller/performances/{performanceId}

- 권한: 소유 SELLER; 성공: 200; 헤더: 응답 ETag.
- R: P10.
- E: 400 INVALID_REQUEST.

### PATCH /api/seller/performances/{performanceId}

- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `venueId?:integer[미삭제 공연장 ID. 공연이 DRAFT이며 삭제된 회차를 포함한 회차 등록 이력이 없을 때만 변경 가능.]; title?:string[공연명. 1~200자. DRAFT에서 변경 가능.]; genre?:string[CONCERT / MUSICAL / PLAY / SPORTS. DRAFT에서 변경 가능.]; ageRating?:string[ALL / 12 / 15 / 19. DRAFT에서 변경 가능.]; runningTime?:integer[공연 시간(분). 1 이상. DRAFT에서 변경 가능.]; castInfo?:string|null[최대 500자. 생략하면 기존 값을 유지하고 명시적 null이면 제거한다. DRAFT·PUBLISHED 모두 수정 가능하다.]; description?:string|null[생략하면 기존 값을 유지한다. DRAFT·PUBLISHED 모두 수정 가능하다. 명시적 null은 공개 필수 요건을 깨지 않는 경우에만 허용한다.]; posterUrl?:string|null[최대 500자 HTTPS URL. 생략하면 기존 값을 유지한다. DRAFT·PUBLISHED 모두 수정 가능하다. 명시적 null은 공개 필수 요건을 깨지 않는 경우에만 허용한다.]`.
- R: P8.
- E: 400 INVALID_REQUEST.

### PUT /api/seller/performances/{performanceId}/grade-prices

- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `gradePrices:PriceInput[][전체 가격 집합. 등급 ID 중복 불가.]; gradePrices[].venueSeatGradeId:integer[공연장의 고정 등급 ID.]; gradePrices[].price:integer[공연 공통 기본 가격. 원, 0 초과.]`.
- R: P11.
- E: 400 INVALID_REQUEST.

### PUT /api/seller/performances/{performanceId}/seat-exclusions

- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `excludedSeats:ExcludedSeat[][공연 공통 전체 제외 집합. 빈 배열은 제외 없음.]; excludedSeats[].venueSeatId:integer[공연장 물리 좌석 ID.]; excludedSeats[].reason:string[제외 사유. 1~255자. 관리 응답 전용.]`.
- R: P12.
- E: 400 INVALID_REQUEST.

### POST /api/seller/performances/{performanceId}/publication

- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- R: P8.
- E: 400 INVALID_REQUEST.

### DELETE /api/seller/performances/{performanceId}

- 권한: 소유 SELLER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 400 INVALID_REQUEST.

### POST /api/seller/performances/{performanceId}/schedules

- 권한: 소유 SELLER; 성공: 201; 헤더: Location=`/api/seller/schedules/{scheduleId}`.
- B: `startAt:datetime[회차 시작 시각.]; bookingOpenAt:datetime[예매 오픈 시각.]; bookingCloseAt:datetime[예매 마감 시각.]`.
- R: P13.
- E: 400 INVALID_REQUEST.

### GET /api/seller/schedules/{scheduleId}

- 권한: 소유 SELLER; 성공: 200; 헤더: 응답 ETag.
- R: P13.
- E: 400 INVALID_REQUEST; 401 UNAUTHORIZED; 403 FORBIDDEN; 404 SCHEDULE_NOT_FOUND.

### PATCH /api/seller/schedules/{scheduleId}

- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `startAt?:datetime[회차 시작 시각.]; bookingOpenAt?:datetime[예매 오픈 시각.]; bookingCloseAt?:datetime[예매 마감 시각.]`.
- R: P13.
- E: 400 INVALID_REQUEST.

### DELETE /api/seller/schedules/{scheduleId}

- 권한: 소유 SELLER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 400 INVALID_REQUEST.

### GET /api/admin/performances

- 권한: MASTER; 성공: 200.
- Q: `sellerId?:integer[판매자 필터.]; publicationStatus?:string[DRAFT / PUBLISHED.]; operationStatus?:string[ACTIVE / CANCELED.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P9.
- E: 400 INVALID_REQUEST.

### GET /api/admin/performances/{performanceId}

- 권한: MASTER; 성공: 200; 헤더: 응답 ETag.
- R: P10.
- E: 400 INVALID_REQUEST.

### POST /api/admin/schedules/{scheduleId}/cancellation

- 권한: MASTER; 성공: 202; 헤더: If-Match.
- B: `reason:string[취소 사유. 1~500자.]`.
- R: P14.
- E: 400 INVALID_REQUEST.

### PUT /api/admin/schedules/{scheduleId}/sales-suspension

- 권한: MASTER; 성공: 200; 헤더: If-Match.
- B: `suspended:boolean[true 중지, false 재개.]; reason?:string|null[중지(suspended=true) 시 필수이며 trim 후 1~500자. 재개(suspended=false) 시 생략 또는 null이고 저장된 중지 사유를 비운다.]`.
- R: P15.
- E: 400 INVALID_REQUEST.

### GET /api/performances

- 권한: 누구나; 성공: 200.
- Q: `genre?:string[CONCERT / MUSICAL / PLAY / SPORTS.]; region?:string[지역. 생략 시 전체.]; from?:date[공연 시작일 검색 하한. KST, 포함.]; to?:date[공연 시작일 검색 상한. KST, 포함.]; keyword?:string[검색어. 생략 시 필터 없음.]; sort?:string[LATEST / BOOKING_OPEN_ASC. 기본 LATEST.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P16.
- E: 400 INVALID_REQUEST.

### GET /api/performances/{performanceId}

- 권한: 누구나; 성공: 200.
- R: P17.
- E: 400 INVALID_REQUEST.

### GET /api/performances/{performanceId}/schedules

- 권한: 누구나; 성공: 200.
- R: P18.
- E: 400 INVALID_REQUEST.

### GET /api/schedules/{scheduleId}

- 권한: 누구나; 성공: 200.
- R: P19.
- E: 400 INVALID_REQUEST.

### GET /api/performances/{performanceId}/seat-layout

- 권한: 누구나; 성공: 200.
- R: P20.
- E: 400 INVALID_REQUEST.

### POST /internal/schedules/{scheduleId}/sale-validations

- 권한: 예매 서비스; 성공: 200.
- B: `configVersion:integer[예매 서비스가 보유한 좌석 구성 버전. 1 이상.]; venueSeatIds:integer[][1개 이상, 중복 불가. 공연장 물리 좌석 ID 목록.]`.
- R: P21.
- E: 400 INVALID_REQUEST.

### 공연 응답 스키마

- P1 = `id:integer; name:string; address:string; region:string; version:integer; createdAt:datetime; updatedAt:datetime`.
- P2 = `items:Venue[]; items[].id:integer; items[].name:string; items[].address:string; items[].region:string; items[].version:integer; items[].createdAt:datetime; items[].updatedAt:datetime; page:integer; size:integer; totalElements:integer`.
- P3 = `grades:Grade[]; grades[].id:integer; grades[].name:string; venueVersion:integer`.
- P4 = `grade:Grade; grade.id:integer; grade.name:string; venueVersion:integer`.
- P5 = `createdCount:integer; createdSeatIds:integer[]; venueVersion:integer`.
- P6 = `venueId:integer; venueVersion:integer; seats:Seat[]; seats[].id:integer; seats[].section:string; seats[].rowLabel:string; seats[].seatNumber:integer; seats[].venueSeatGradeId:integer; seats[].gradeName:string`.
- P7 = `seat:Seat; seat.id:integer; seat.section:string; seat.rowLabel:string; seat.seatNumber:integer; seat.venueSeatGradeId:integer; seat.gradeName:string; venueVersion:integer`.
- P8 = `id:integer; venue:VenueSummary; venue.id:integer; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; publicationStatus:string; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; version:integer; createdAt:datetime; updatedAt:datetime; sellerId:integer`.
- P9 = `items:PerformanceManageSummary[]; items[].id:integer; items[].venue:VenueSummary; items[].venue.id:integer; items[].venue.name:string; items[].venue.address:string; items[].venue.region:string; items[].title:string; items[].genre:string; items[].posterUrl:string|null; items[].publicationStatus:string; items[].operationStatus:string; items[].period:Period|null; items[].period.from:date; items[].period.to:date; items[].version:integer; items[].sellerId:integer; page:integer; size:integer; totalElements:integer`.
- P10 = `id:integer; venue:VenueSummary; venue.id:integer; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; publicationStatus:string; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; version:integer; createdAt:datetime; updatedAt:datetime; sellerId:integer; gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:integer; gradePrices[].gradeName:string; gradePrices[].price:integer; excludedSeats:ExcludedSeat[]; excludedSeats[].venueSeatId:integer; excludedSeats[].reason:string; schedules:ScheduleManage[]; schedules[].id:integer; schedules[].performanceId:integer; schedules[].startAt:datetime; schedules[].bookingOpenAt:datetime; schedules[].bookingCloseAt:datetime; schedules[].operationStatus:string; schedules[].seatSetupStatus:string; schedules[].salesSuspended:boolean; schedules[].salesStatus:string; schedules[].canceledAt:datetime|null; schedules[].seatConfigVersion:integer; schedules[].seatSetupRequestId:string; schedules[].seatSetupError:string|null; schedules[].suspensionReason:string|null; schedules[].cancelReason:string|null; schedules[].version:integer`.
- P11 = `gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:integer; gradePrices[].gradeName:string; gradePrices[].price:integer; performanceVersion:integer`.
- P12 = `excludedSeats:ExcludedSeat[]; excludedSeats[].venueSeatId:integer; excludedSeats[].reason:string; performanceVersion:integer`.
- P13 = `id:integer; performanceId:integer; startAt:datetime; bookingOpenAt:datetime; bookingCloseAt:datetime; operationStatus:string; seatSetupStatus:string; salesSuspended:boolean; salesStatus:string; canceledAt:datetime|null; seatConfigVersion:integer; seatSetupRequestId:string; seatSetupError:string|null; suspensionReason:string|null; cancelReason:string|null; version:integer`.
- P14 = `scheduleId:integer; operationStatus:string; canceledAt:datetime; refundProcessingStatus:string; version:integer`.
- P15 = `scheduleId:integer; salesSuspended:boolean; suspensionReason:string|null; version:integer`.
- P16 = `items:PerformanceSummary[]; items[].id:integer; items[].venue:VenueSummary; items[].venue.id:integer; items[].venue.name:string; items[].venue.address:string; items[].venue.region:string; items[].title:string; items[].genre:string; items[].posterUrl:string|null; items[].operationStatus:string; items[].period:Period|null; items[].period.from:date; items[].period.to:date; page:integer; size:integer; totalElements:integer`.
- P17 = `id:integer; venue:VenueSummary; venue.id:integer; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; createdAt:datetime; updatedAt:datetime; gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:integer; gradePrices[].gradeName:string; gradePrices[].price:integer`.
- P18 = `performanceId:integer; schedules:SchedulePublic[]; schedules[].id:integer; schedules[].performanceId:integer; schedules[].startAt:datetime; schedules[].bookingOpenAt:datetime; schedules[].bookingCloseAt:datetime; schedules[].operationStatus:string; schedules[].seatSetupStatus:string; schedules[].salesSuspended:boolean; schedules[].salesStatus:string; schedules[].canceledAt:datetime|null`.
- P19 = `id:integer; performanceId:integer; startAt:datetime; bookingOpenAt:datetime; bookingCloseAt:datetime; operationStatus:string; seatSetupStatus:string; salesSuspended:boolean; salesStatus:string; canceledAt:datetime|null`.
- P20 = `performanceId:integer; venue:VenueSummary; venue.id:integer; venue.name:string; venue.address:string; venue.region:string; seats:LayoutSeat[]; seats[].id:integer; seats[].section:string; seats[].rowLabel:string; seats[].seatNumber:integer; seats[].venueSeatGradeId:integer; seats[].gradeName:string; seats[].isSellable:boolean; seats[].basePrice:integer|null`.
- P21 = `allowed:boolean; reasonCode:string|null; scheduleId:integer; configVersion:integer; policyVersion:integer; performanceTitle:string; scheduleStartAt:datetime; validatedAt:datetime; seats:ValidationSeat[]; seats[].venueSeatId:integer; seats[].venueSeatGradeId:integer; seats[].gradeName:string; seats[].basePrice:integer`.


공연 공통 업무 오류: 400 INVALID_TIME_RANGE/VENUE_GRADE_MISMATCH/GRADE_PRICE_MISSING;404 PERFORMANCE_NOT_FOUND/SCHEDULE_NOT_FOUND/VENUE_NOT_FOUND;409 PUBLICATION_REQUIREMENTS_NOT_MET/PERFORMANCE_CONFIGURATION_LOCKED/VENUE_LAYOUT_IN_USE/DUPLICATE_SCHEDULE/DUPLICATE_VENUE_SEAT/DELETE_NOT_ALLOWED/RESERVATION_EXISTS/DUPLICATE_VENUE_GRADE/VENUE_GRADE_IN_USE/SEAT_CONFIG_VERSION_MISMATCH;503 DEPENDENCY_UNAVAILABLE. 각 리소스 명령의 조건에만 적용한다. 등급/좌석 미존재 코드는 미정.

판매 검증 P21.reasonCode: 허용 시 null; 거절 시 PERFORMANCE_NOT_PUBLISHED/PERFORMANCE_CANCELED/SCHEDULE_CANCELED/SALES_SUSPENDED/SEAT_SETUP_NOT_READY/BEFORE_OPEN/SALES_CLOSED/SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH. 업무 거절은 HTTP200,allowed=false,seats=[]; 삭제/미존재는 HTTP404. success HTTP200,allowed=true이면 요청 좌석 가격을 반환한다.

## 좌석 — reservation-service / seat

| Method URI | 권한 | 성공 | B | R | 추가 오류 |
|---|---|---|---|---|---|
| GET /api/schedules/{scheduleId}/seats | JWT+X-Queue-Token | 200 | 없음 | scheduleId:long,configVersion:long,asOf:datetime,seats:SelectableSeat[] | 403 QUEUE_TOKEN_INVALID |
| GET /api/schedules/{scheduleId}/remaining | 공개 | 200 | 없음 | scheduleId:long,grades:[{venueSeatGradeId:long,gradeName:string,remaining:int}],asOf:datetime,cached:boolean | — |
| POST /api/schedules/{scheduleId}/holds | USER+X-Queue-Token | 201; 동일 유효 선점 200 | scheduleSeatId:long | reservationId:long,scheduleSeatId:long,status:HOLD,holdExpiresAt:datetime,basePrice:int | 404 SEAT_NOT_FOUND;409 SEAT_ALREADY_TAKEN/PURCHASE_LIMIT_EXCEEDED/SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH;503 SERVICE_UNAVAILABLE |
| DELETE /api/schedules/{scheduleId}/holds/{reservationId} | 본인 JWT | 204 | 없음 | 없음 | 403 FORBIDDEN;404 RESERVATION_NOT_FOUND;409 INVALID_STATUS_TRANSITION |

- SelectableSeat = `{scheduleSeatId:long,venueSeatId:long,section:string,rowLabel:string,seatNumber:int,venueSeatGradeId:long,gradeName:string,basePrice:int|null,selectable:boolean}`. 다른 사용자의 예약 ID/만료 시각 제외.
- 선점 판매 거절은 sale-validations의 reasonCode를 외부 code로 반환: 기본 400, SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH만 409. 삭제·미존재는 404. 연령 미달 오류 코드는 미정.

## 예매 — reservation-service / booking

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| GET /api/reservations/{reservationId} | 본인 USER 또는 MASTER | 200 | 없음 | Reservation | 404 RESERVATION_NOT_FOUND;403 FORBIDDEN |
| POST /api/reservations/{reservationId}/payments/checkout | 본인 USER | 200 | userCouponId?:long,pgProvider:string(TOSS/KAKAO) | orderId:string,amount:int,checkoutUrl:string\|null | 409 HOLD_EXPIRED/INVALID_STATUS_TRANSITION;쿠폰 오류;503 SERVICE_UNAVAILABLE |
| POST /api/reservations/{reservationId}/payments/confirm | 본인 USER | 200 | orderId:string(1~64),pgAuthToken:string(1~255) | Reservation | 400 VALIDATION_ERROR;409 HOLD_EXPIRED/INVALID_STATUS_TRANSITION |
| GET /api/reservations | 본인 USER | 200 | Q status?:string,page?:int=0,size?:int=10(10/30/50),sort?:string=createdAt,DESC | PageC<ReservationSummary>(항목 필드 미정) | 구현 전 |
| POST /api/reservations/{reservationId}/cancel | 본인 USER | 200 | reason?:string | Reservation(CANCELING/CANCELED) | 취소 정책/세부 오류 미정; 구현 전 |

- Reservation 현행 DTO = `{reservationId:long,scheduleId:long,seatIds:long[],status:string,holdExpiresAt:datetime,orderId:string|null,paymentAmount:long|null}`.
- checkout 현행 DTO는 `couponId`, `paymentWindowUrl`, 선택 pgProvider. 합의 목표는 위 표의 `userCouponId`, `checkoutUrl`, 필수 pgProvider. 응답 좌석명 목표는 `scheduleSeatIds`(MVP 길이 1). 현재 미연동 포트는 503이며 문서 개정만으로 구현된 것으로 보지 않는다.

## 대기열 — reservation-service / queue

| Method URI | 권한 | 성공 | B | R |
|---|---|---|---|---|
| POST /api/schedules/{id}/queue | JWT | 200 | 미기재 | position:int,aheadCount:int,estimatedWaitSeconds:int |
| GET /api/schedules/{id}/queue/me | JWT | 200 | 없음 | 미정 |
| DELETE /api/schedules/{id}/queue/me | JWT | 204 | 없음 | 없음 |

입장 토큰 조회/전달 응답, 세부 오류는 미정. 존재하지 않는 발급 API를 추가하지 않는다.

## 쿠폰 — reservation-service / coupon

모든 항목 설계 계약이며 구현 전. 로그인은 USER/SELLER/MASTER 모두. `PageC<T>={content:T[],page:int,size:int,totalElements:long,totalPages:int,sort:형식 미정}`. page=0,size=10(10/30/50).

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| POST /api/coupons | MASTER | 201 | CouponInput | Coupon | 400 VALIDATION_ERROR |
| PATCH /api/coupons/{couponId} | MASTER | 200 | CouponInput의 변경 필드만 | Coupon | 404 COUPON_NOT_FOUND;409 COUPON_NOT_MODIFIABLE/COUPON_QUANTITY_BELOW_ISSUED |
| POST /api/coupons/{couponId}/close | MASTER | 200 | 없음 | Coupon(issueStatus=ENDED) | 404 COUPON_NOT_FOUND;409 COUPON_ALREADY_CLOSED |
| DELETE /api/coupons/{couponId} | MASTER | 204 | 없음 | 없음 | 404 COUPON_NOT_FOUND;409 COUPON_NOT_DELETABLE |
| GET /api/coupons | 로그인 | 200 | Q issueStatus?:string=ISSUING,page?,size?,sort?:string=createdAt,DESC(createdAt/issueStartAt/issueEndAt,ASC/DESC) | PageC<CouponDetail> | 400 VALIDATION_ERROR |
| GET /api/coupons/{couponId} | 로그인 | 200 | 없음 | CouponDetail | 404 COUPON_NOT_FOUND |
| POST /api/coupons/{couponId}/issue | USER | 201 | 없음 | UserCoupon(usedAt 제외,status=AVAILABLE) | 400 COUPON_ISSUE_PERIOD_CLOSED;404 COUPON_NOT_FOUND;409 COUPON_SOLD_OUT/COUPON_ALREADY_ISSUED |
| GET /api/coupons/me | 로그인 | 200 | Q status?:string,page?,size?,sort?:string=createdAt,DESC(createdAt/expiresAt,ASC/DESC) | PageC<UserCoupon> | 400 VALIDATION_ERROR |

- CouponInput = `{name:string(1~100),discountAmount:int>=1,totalQuantity:int>=1,issueStartAt:datetime,issueEndAt:datetime(>start 및 >now),validDays:int>=1}`.
- Coupon = `{couponId:long,name:string,discountAmount:int,totalQuantity:int,remainingQuantity:int,issueStartAt:datetime,issueEndAt:datetime,validDays:int,issueStatus:string}`.
- CouponDetail = Coupon + `{issuedByMe:boolean}`. SELLER의 issuedByMe=false.
- UserCoupon = `{userCouponId:long,couponId:long,name:string,discountAmount:int,status:string,issuedAt:datetime,expiresAt:datetime,usedAt:datetime|null}`.
- 발급 시작 후 수정 가능: name,issueEndAt,totalQuantity(>=issuedQuantity). 삭제는 issuedQuantity=0일 때만. 발급은 1인 1장.

## 결제 내부 — payment-service

권한: 내부 예매 호출; 관리자 경로는 운영자 전용(서비스 인증 방식 미정). orderId는 예매가 시도마다 발급하는 1~64자 문자열, 같은 시도 재시도는 같은 값. 준비/승인/환불은 orderId 멱등. HTTP 200에도 업무 FAILED가 올 수 있다.

결제 HTTP 계약은 합의 상태(#22·PR #25), 구현 완료와 구분한다. D16에 따라 고도화에도 준비·승인 동기 계약을 유지한다.

| Method URI | 성공 | B | R | 추가 오류 |
|---|---|---|---|---|
| POST /internal/payments | 200 | orderId:string,reservationId:long,userId:long,sellerId:long,performanceId:long,scheduleId:long,totalPrice:int>0,discountAmount:int>=0,pgProvider:string,expiresAt:datetime | paymentId:long,orderId:string,amount:int>=100,status:READY/FAILED,failureType:string?,failureCode:string?,checkoutUrl:string\|null | 400 INVALID_PAYMENT_AMOUNT;409 PAYMENT_ORDER_CONFLICT/PAYMENT_ALREADY_IN_PROGRESS/PAYMENT_ALREADY_DONE/PAYMENT_CHECKOUT_CONFLICT |
| POST /internal/payments/{orderId}/confirm | 200 | pgAuthToken:string,amount:int | paymentId:long,status:DONE/FAILED/IN_PROGRESS,failureType:string?,failureCode:string? | 400 PAYMENT_AMOUNT_MISMATCH;404 PAYMENT_NOT_FOUND |
| GET /internal/payments/{orderId} | 200 | 없음 | paymentId:long,orderId:string,reservationId:long,status:string,amount:int,failureType:string?,failureCode:string?,confirmRequestedAt:datetime\|null,approvedAt:datetime\|null,refund:RefundView\|null | 404 PAYMENT_NOT_FOUND |
| POST /internal/payments/{orderId}/refund | 200 | reason:USER_CANCEL/SCHEDULE_CANCELED/CONFIRM_FAILED | refundStatus:WAITING/REQUESTED/DONE/FAILED/SKIPPED/NOT_REQUIRED,refundAmount:int | 404 PAYMENT_NOT_FOUND |
| POST /internal/admin/payments/{orderId}/refund/unblock | 미정 | 없음 | 응답 형식 미정 | 409 INVALID_STATUS_TRANSITION |
| POST /internal/admin/payments/{orderId}/refund/retry | 미정 | 없음 | 응답 형식 미정 | FAILED 환불만; 세부 오류 미정 |

- RefundView = `{status:string,reason:string,refundAmount:int,refundedAt:datetime|null}`. amount=totalPrice-discountAmount, 보정 없이 100원 미만 거절. 동일 orderId의 할인액 변경도 PAYMENT_ORDER_CONFLICT.
- 준비 재요청은 기존 응답; 이전 READY는 SUPERSEDED로 종료하되 승인 접수된 READY/IN_PROGRESS는 대체 불가. DONE 결제 존재 시 새 준비 불가.
- 실패 구분: REJECTED(PG_REJECTED/AMOUNT_MISMATCH/INVALID_AUTH_TOKEN), UNAVAILABLE(PG_UNAVAILABLE/PG_BUSY/PG_UNRESOLVED), EXPIRED(EXPIRED), NOT_APPROVED(NOT_APPROVED/CANCELED_AT_PG), CLOSED(SUPERSEDED/ABANDONED/READY_FAILED).
- 환불은 전액. READY→FAILED(ABANDONED), FAILED는 NOT_REQUIRED(환불 행 없음); IN_PROGRESS는 WAITING, DONE은 REQUESTED 후 PG 취소, CANCELED는 기존 환불. 접수와 PG 취소 완료는 다르다.

## 서비스 내부 모듈 계약

| 제공 → 소비 | 상태 | 메서드/타입 |
|---|---|---|
| booking → seat | 합의 #34 | hold(Long userId,Long scheduleId,List<SeatLine>,LocalDateTime holdExpiresAt):Long; getReservation(Long):Optional<ReservationView>; release(Long userId,Long reservationId):ReleaseResult |
| booking → seat | 합의 #34 | countActiveSeats(Long userId,Long scheduleId):int; findActiveHold(Long userId,Long scheduleId):Optional<ReservationView>; expireIfDue(Long reservationId):boolean; findPaymentInProgress(Collection<Long>):Set<Long> |
| seat → booking | 합의 #34; 구현 전 | lockSeats(Collection<Long>):List<SeatLock>; sell(Collection<Long>,Long reservationId):int; release(Collection<Long>,Long reservationId):int |
| coupon → booking | 초안 #27 | reserve(Long userCouponId,Long userId,Long reservationId):할인액; use(Long reservationId); release(Long reservationId). 반환 타입/인자 최종 합의 대기 |

- SeatLine=`{scheduleSeatId:Long,price:int}`; ReservationView=`{reservationId:Long,userId:Long,scheduleId:Long,state:ReservationState,holdExpiresAt:LocalDateTime,scheduleSeatIds:List<Long>}`; SeatLock=`{scheduleSeatId:Long,state:SeatState,reservationId:Long,holdExpiresAt:LocalDateTime}`.
- ReleaseResult=RELEASED/EXPIRED/ALREADY_CLOSED/NOT_RELEASABLE; facade 전용 enum은 domain enum을 노출하지 않는다. 트랜잭션 규칙은 architecture 참조.
- CouponUsage 초안: reserve는 소유자/AVAILABLE/유효기간 위반 시 COUPON_NOT_AVAILABLE, 같은 예매·같은 RESERVED 쿠폰이면 기존 할인액 반환. use는 동일 예약 RESERVED라면 만료 후에도 USED, release는 대상 없음도 성공하는 멱등 호출이다. 해당 오류는 예매 checkout에서 전달한다.
- 좌석 준비(공연→seat): MVP Feign 계약의 URI·응답·타임아웃 미합의. 요청 초안=`{requestId:string,scheduleId:long,configVersion:long,seats:[{venueSeatId:long,venueSeatGradeId:long,gradeName:string,basePrice:int|null,isSellable:boolean,section:string,rowLabel:string,seatNumber:int}]}`. Kafka 전환 후 Requested/Completed/Failed의 토픽·스키마·버전은 별도 합의. 미확정 HTTP 경로나 메시지 계약을 생성하지 않는다.

## Fake PG — fake-pg:8090

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
| GET /fake/admin/transactions | Q orderId?:string,status?:string,limit?:int=100(1~1000 보정) | 200 `TransactionView[]`; 최신순 |
| DELETE /fake/admin/transactions | 없음 | 204 거래/멱등 기록 초기화 |

- provider=TOSS/KAKAO; operation=READY(KAKAO만)/CONFIRM/INQUIRE/CANCEL.
- Fault=`{latencyMs?:long>=0,serverErrorRate?:double[0,1],failRate?:double[0,1],responseLossRate?:double[0,1],delayedApprovalRate?:double[0,1],delayedApprovalMs?:long>=0,holdMs?:long>=0(기본15000),rateLimitPerSecond?:int>=0(0=무제한)}`. 생략은 장애 없음.

- TransactionView=`{provider:string,paymentKey:string,orderId:string,amount:int,status:string,requestedAt:datetime,approvedAt:datetime|null,canceledAt:datetime|null,cancelTransactionKey:string|null,failureCode:string|null}`.
