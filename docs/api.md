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
| GET /internal/users/{userId} | 200 | 없음 | userId:long, email:string, role:string | 내부 호출, 조회 멱등;404 USER_NOT_FOUND |

- Signup = `{email:string(이메일), password:string(8~15자·대소문자/숫자/특수문자), name:string(공백 불가), phone:string(01[016789]-숫자3~4-숫자4)}`. 역할은 서버 고정. 탈퇴 이메일도 중복 거절.
- SignupResult = `{userId:long,email:string,name:string,role:string,status:string}`.
- User = `{userId:long,email:string,name:string,phone:string,role:string,status:string,createdAt:datetime,updatedAt:datetime}`.

## 공연 — performance-service

상태: #54의 개별 DTO는 초안이다. 공연장 고정 등급·공연별 공통 가격 등 정책 합의는 유지한다.

33개 계약. Path ID는 양의 long. 공연 목록은 `PageP<T>={items:T[],page:int,size:int,totalElements:long}`. 기본 page=0,size=20(1~100). Idempotency-Key는 공연·회차 생성/회차 취소의 권장 초안이며 범위·보존 기간 미정이다.
If-Match는 큰따옴표로 감싼 대상 version. 누락 428 PRECONDITION_REQUIRED, 충돌 409 VERSION_CONFLICT. 하위 등급/좌석은 venue.version, 가격/제외는 performance.version, 회차 명령은 schedule.version. 회차 생성에는 If-Match 없음.
오류 별칭은 현행 common에 맞춰 UNAUTHENTICATED → UNAUTHORIZED로 정리했다.
요청 형식 오류(필드 검증, 타입·열거값 불일치, 날짜 형식, page·size 범위, Path ID 형식)는 400 VALIDATION_ERROR다. 공통 GlobalExceptionHandler가 이 코드로 응답하기 때문이다(#83, 공연 담당 결정). 업무 규칙 위반은 INVALID_TIME_RANGE 같은 공연 오류 코드를 쓴다.

- 공연 관리 PATCH는 최소1개 필드가 필요하다. 알 수 없는 필드·타입/필수값 오류는400, 공개 후 금지 구성 필드는400/409로 거절한다. (원본 복원: performance/api.md:1739,2502, 담당 확인 필요)
- 공연/회차 생성·회차 취소의 Idempotency-Key는 권장 초안이다. 같은 키·같은 요청은 기존 결과, 다른 요청은409이며 범위·보존기간은 미합의다. (원본 복원: performance/api.md:112-114, 담당 확인 필요)

### POST /api/admin/venues

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 201; 헤더: Location=`/api/seller/venues/{venueId}`.
- B: `name:string[공연장명. 1~100자.]; address:string[주소. 1~255자.]; region:string[지역. 1~20자.]`.
- R: P1.
- E: 400 VALIDATION_ERROR.

### GET /api/seller/venues

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: SELLER / MASTER; 성공: 200.
- Q: `region?:string[지역. 생략 시 전체.]; keyword?:string[검색어. 생략 시 필터 없음.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P2.
- E: 400 VALIDATION_ERROR.

### GET /api/seller/venues/{venueId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: SELLER / MASTER; 성공: 200; 헤더: 응답 ETag.
- R: P1.
- E: 400 VALIDATION_ERROR; 401 UNAUTHORIZED; 403 FORBIDDEN; 404 VENUE_NOT_FOUND.

### POST /api/admin/venues/{venueId}/seat-grades

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 201; 헤더: If-Match.
- B: `grades:GradeInput[][1개 이상. 공연장 내 동일 이름 중복 불가.]; grades[].name:string[등급명. 1~20자, 공백 불가.]`.
- R: P3.
- E: 400 VALIDATION_ERROR.
- 공연장 내 등급명 중복은409 DUPLICATE_VENUE_GRADE다. 사용 중 배치에는 등급을 추가하지 않는다(409 VENUE_LAYOUT_IN_USE). (원본 복원: performance/api.md:198-244, 담당 확인 필요)

### GET /api/seller/venues/{venueId}/seat-grades

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: SELLER / MASTER; 성공: 200.
- R: P3.
- E: 400 VALIDATION_ERROR.

### PATCH /api/admin/venues/{venueId}/seat-grades/{gradeId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 200; 헤더: If-Match.
- B: `name:string[새 등급명. 1~20자, 공연장 내 유일.]`.
- R: P4.
- E: 400 VALIDATION_ERROR.
- 공연이 참조한 배치 수정은409 VENUE_LAYOUT_IN_USE, 등급명 중복은409 DUPLICATE_VENUE_GRADE다. 성공 시 venue.version을 증가시킨다. (원본 복원: performance/api.md:239-244, 담당 확인 필요)

### DELETE /api/admin/venues/{venueId}/seat-grades/{gradeId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 409 VENUE_GRADE_IN_USE; 409 VENUE_LAYOUT_IN_USE; 428 PRECONDITION_REQUIRED; 409 VERSION_CONFLICT.
- 사용 중 배치는409 VENUE_LAYOUT_IN_USE, 미삭제 좌석이 쓰는 등급은409 VENUE_GRADE_IN_USE다. 소프트 삭제와 venue.version 증가를 함께 처리한다. (원본 복원: performance/api.md:241-244, 담당 확인 필요)

### POST /api/admin/venues/{venueId}/seats

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 201; 헤더: If-Match.
- B: `seats:SeatInput[][1개 이상. 동일 구역·열·번호 중복 불가.]; seats[].section:string[구역. 1~20자.]; seats[].rowLabel:string[열. 1~10자.]; seats[].seatNumber:integer[좌석 번호. 1 이상.]; seats[].venueSeatGradeId:long[동일 공연장의 고정 등급 ID.]`.
- R: P5.
- E: 400 VALIDATION_ERROR.
- 다른 공연장 등급은400 VENUE_GRADE_MISMATCH, 같은 위치 좌석 중복은409 DUPLICATE_VENUE_SEAT이며 일괄 요청 전체를 롤백한다. 사용 중 배치 추가는409 VENUE_LAYOUT_IN_USE다. (원본 복원: performance/api.md:232-244, 담당 확인 필요)

### GET /api/seller/venues/{venueId}/seats

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: SELLER / MASTER; 성공: 200.
- R: P6.
- E: 400 VALIDATION_ERROR.

### PATCH /api/admin/venues/{venueId}/seats/{seatId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 200; 헤더: If-Match.
- B: `section?:string[구역. 1~20자.]; rowLabel?:string[열. 1~10자.]; seatNumber?:integer[좌석 번호. 1 이상.]; venueSeatGradeId?:long[동일 공연장의 고정 등급 ID.]`.
- R: P7.
- E: 400 VALIDATION_ERROR.
- 공연이 참조한 배치는409 VENUE_LAYOUT_IN_USE다. 공연장 소속·위치 중복을 검사하고 성공 시 venue.version을 증가시킨다. (원본 복원: performance/api.md:239-244, 담당 확인 필요)

### DELETE /api/admin/venues/{venueId}/seats/{seatId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 409 VENUE_LAYOUT_IN_USE; 428 PRECONDITION_REQUIRED; 409 VERSION_CONFLICT.
- 공연이 참조한 배치는409 VENUE_LAYOUT_IN_USE다. 소프트 삭제하고 venue.version을 증가시킨다. (원본 복원: performance/api.md:241-244, 담당 확인 필요)

### POST /api/seller/performances

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: SELLER; 성공: 201; 헤더: Location=`/api/seller/performances/{performanceId}`.
- B: `venueId:long[미삭제 공연장 ID.]; title:string[공연명. 1~200자.]; genre:string[CONCERT / MUSICAL / PLAY / SPORTS.]; ageRating:string[ALL / 12 / 15 / 19.]; runningTime:integer[공연 시간(분). 1 이상.]; castInfo?:string|null[최대 500자. 생략하면 null.]; description?:string|null[초안에서는 생략 가능. 공개 조건은 별도 검증.]; posterUrl?:string|null[최대 500자 HTTPS URL. 초안에서는 생략 가능.]`.
- R: P8.
- E: 400 VALIDATION_ERROR.

### GET /api/seller/performances

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: SELLER; 성공: 200.
- Q: `status?:string[공개 상태 필터 DRAFT / PUBLISHED. 생략 시 전체.]; keyword?:string[검색어. 생략 시 필터 없음.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P9.
- E: 400 VALIDATION_ERROR.

### GET /api/seller/performances/{performanceId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: 응답 ETag.
- R: P10.
- E: 400 VALIDATION_ERROR.

### PATCH /api/seller/performances/{performanceId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `venueId?:long[미삭제 공연장 ID. 공연이 DRAFT이며 삭제된 회차를 포함한 회차 등록 이력이 없을 때만 변경 가능.]; title?:string[공연명. 1~200자. DRAFT에서 변경 가능.]; genre?:string[CONCERT / MUSICAL / PLAY / SPORTS. DRAFT에서 변경 가능.]; ageRating?:string[ALL / 12 / 15 / 19. DRAFT에서 변경 가능.]; runningTime?:integer[공연 시간(분). 1 이상. DRAFT에서 변경 가능.]; castInfo?:string|null[최대 500자. 생략하면 기존 값을 유지하고 명시적 null이면 제거한다. DRAFT·PUBLISHED 모두 수정 가능하다.]; description?:string|null[생략하면 기존 값을 유지한다. DRAFT·PUBLISHED 모두 수정 가능하다. 명시적 null은 공개 필수 요건을 깨지 않는 경우에만 허용한다.]; posterUrl?:string|null[최대 500자 HTTPS URL. 생략하면 기존 값을 유지한다. DRAFT·PUBLISHED 모두 수정 가능하다. 명시적 null은 공개 필수 요건을 깨지 않는 경우에만 허용한다.]`.
- R: P8.
- E: 400 VALIDATION_ERROR.
- 최소1개 변경 필드를 받는다. 공개 후 description/posterUrl/castInfo만 허용하며 공개 필수 조건을 깨는 null 변경은 거절한다. (원본 복원: performance/api.md:2499-2505, 담당 확인 필요)

### PUT /api/seller/performances/{performanceId}/grade-prices

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `gradePrices:PriceInput[][전체 가격 집합. 등급 ID 중복 불가.]; gradePrices[].venueSeatGradeId:long[공연장의 고정 등급 ID.]; gradePrices[].price:integer[공연 공통 기본 가격. 원, 0 초과.]`.
- R: P11.
- E: 400 VALIDATION_ERROR.
- DRAFT 전용이며 공개 후409 PERFORMANCE_CONFIGURATION_LOCKED다. 전체 가격 집합을 교체한다. 중복/타 공연장 등급을 거절하고 판매 대상 등급 가격 누락은400 GRADE_PRICE_MISSING이다. 모두 제외된 등급만 가격 생략 가능하다. (원본 복원: performance/api.md:298-329, 담당 확인 필요)

### PUT /api/seller/performances/{performanceId}/seat-exclusions

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `excludedSeats:ExcludedSeat[][공연 공통 전체 제외 집합. 빈 배열은 제외 없음.]; excludedSeats[].venueSeatId:long[공연장 물리 좌석 ID.]; excludedSeats[].reason:string[제외 사유. 1~255자. 관리 응답 전용.]`.
- R: P12.
- E: 400 VALIDATION_ERROR.
- DRAFT 전용 전체 집합 교체다. 빈 배열은 제외 없음이다. 중복/타 공연장 좌석과 남은 판매 대상의 가격 누락을 거절한다. 공개 후409 PERFORMANCE_CONFIGURATION_LOCKED다. (원본 복원: performance/api.md:332-353, 담당 확인 필요)

### POST /api/seller/performances/{performanceId}/publication

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- R: P8.
- E: 400 VALIDATION_ERROR.
- 본문 없음. 공개 필수 조건 부족은409 PUBLICATION_REQUIREMENTS_NOT_MET와 누락 항목이다. 이미 공개됐다면 If-Match 불일치에도 현재 결과200·최초 publishedAt을 유지한다. DRAFT 복귀 API는 없다. (원본 복원: performance/api.md:399-410, 담당 확인 필요)

### DELETE /api/seller/performances/{performanceId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 400 VALIDATION_ERROR.
- 소프트 삭제한다. 삭제 정책 위반은409 DELETE_NOT_ALLOWED, 예매 존재는409 RESERVATION_EXISTS, 예매 확인 불가는503 DEPENDENCY_UNAVAILABLE다. 삭제 초안 조건은 DRAFT·오픈 전·선점 및 예매0건·예매 측 정리 가능 확인이다. 조회 계약은 architecture 14절에서 확정한다. (원본 복원: performance/api.md:528,544-547,2988,3558, 담당 확인 필요)

### POST /api/seller/performances/{performanceId}/schedules

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 201; 헤더: Location=`/api/seller/schedules/{scheduleId}`.
- B: `startAt:datetime[회차 시작 시각.]; bookingOpenAt:datetime[예매 오픈 시각.]; bookingCloseAt:datetime[예매 마감 시각.]`.
- R: P13.
- E: 400 VALIDATION_ERROR.
- 취소되지 않은 본인 공연에만 등록하며 공개 후 새 회차 추가도 허용한다. 기존 좌석/등급/가격/제외 설정을 사용하고 좌석별 배정 입력은 거절한다. 중복은409 DUPLICATE_SCHEDULE, 잘못된 시각은400 INVALID_TIME_RANGE다. (원본 복원: performance/api.md:355-397, 담당 확인 필요)

### GET /api/seller/schedules/{scheduleId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: 응답 ETag.
- R: P13.
- E: 400 VALIDATION_ERROR; 401 UNAUTHORIZED; 403 FORBIDDEN; 404 SCHEDULE_NOT_FOUND.

### PATCH /api/seller/schedules/{scheduleId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 200; 헤더: If-Match.
- B: `startAt?:datetime[회차 시작 시각.]; bookingOpenAt?:datetime[예매 오픈 시각.]; bookingCloseAt?:datetime[예매 마감 시각.]`.
- R: P13.
- E: 400 VALIDATION_ERROR.
- DRAFT 회차의 시간만 수정하며 최소1개 필드가 필요하다. 잘못된 시각은400 INVALID_TIME_RANGE, 중복은409 DUPLICATE_SCHEDULE다. 성공 시 schedule.version만 증가하고 시간만 변경하면 좌석 재준비하지 않는다. (원본 복원: performance/api.md:385-397, 담당 확인 필요)

### DELETE /api/seller/schedules/{scheduleId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 소유 SELLER; 성공: 204; 헤더: If-Match.
- R: 없음 (204 본문 없음).
- E: 400 VALIDATION_ERROR.
- 소프트 삭제하고 schedule.version을 증가시킨다. 삭제 정책/예매 존재/의존 조회 불가를 구분하며 세부 조회 계약은 architecture 14절을 따른다. (원본 복원: performance/api.md:528,544-547,2988,3558, 담당 확인 필요)

### GET /api/admin/performances

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 200.
- Q: `sellerId?:long[판매자 필터.]; publicationStatus?:string[DRAFT / PUBLISHED.]; operationStatus?:string[ACTIVE / CANCELED.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P9.
- E: 400 VALIDATION_ERROR.

### GET /api/admin/performances/{performanceId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 200; 헤더: 응답 ETag.
- R: P10.
- E: 400 VALIDATION_ERROR.

### POST /api/admin/schedules/{scheduleId}/cancellation

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 202; 헤더: If-Match.
- B: `reason:string[취소 사유. 1~500자.]`.
- R: P14.
- E: 400 VALIDATION_ERROR.
- 같은 회차 반복 취소는 기존 결과를 반환하고 환불을 중복 생성하지 않는다.202/PENDING은 공연 취소 접수이며 예매 전달·PG 환불 완료를 보장하지 않는다. (원본 복원: performance/api.md:426-449, 담당 확인 필요)

### PUT /api/admin/schedules/{scheduleId}/sales-suspension

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: MASTER; 성공: 200; 헤더: If-Match.
- B: `suspended:boolean[true 중지, false 재개.]; reason?:string|null[중지(suspended=true) 시 필수이며 trim 후 1~500자. 재개(suspended=false) 시 생략 또는 null이고 저장된 중지 사유를 비운다.]`.
- R: P15.
- E: 400 VALIDATION_ERROR.
- 취소 회차 재개는409다(세부 코드 미정). 재개해도 오픈 전/마감 후/좌석 미준비/취소 조건을 우회하지 않는다. 신규 선점만 차단하며 기존 유효 HOLD 결제는 원래 만료까지 허용한다. (원본 복원: performance/api.md:450-467, 담당 확인 필요)

### GET /api/performances

- 상태: 구현됨(#83).
- 권한: 누구나; 성공: 200.
- Q: `genre?:string[CONCERT / MUSICAL / PLAY / SPORTS.]; region?:string[지역. 생략 시 전체.]; from?:date[공연 시작일 검색 하한. KST, 포함.]; to?:date[공연 시작일 검색 상한. KST, 포함.]; keyword?:string[검색어. 생략 시 필터 없음.]; sort?:string[LATEST / BOOKING_OPEN_ASC. 기본 LATEST.]; page?:integer[0부터 시작, 기본 0.]; size?:integer[1~100, 기본 20.]`.
- R: P16.
- E: 400 VALIDATION_ERROR; 400 INVALID_TIME_RANGE(from이 to보다 늦음).
- 기본 목록은 공개·미삭제·정상 공연이다. 취소 공연 검색 포함은 미합의다. from<=to이며 KST 날짜 양끝을 포함한다. BOOKING_OPEN_ASC는 판매 종료 전 회차의 가장 이른 오픈 시각 기준이고 대상 회차가 없으면 마지막에 둔다. (원본 복원: performance/api.md:291-295,4324, 담당 확인 필요)

### GET /api/performances/{performanceId}

- 상태: 구현됨(#83).
- 권한: 누구나; 성공: 200.
- R: P17.
- E: 400 VALIDATION_ERROR.
- 초안/삭제는404 PERFORMANCE_NOT_FOUND다. 취소된 공개 공연 상세는 취소 안내를 위해 허용한다. 공개 DTO에 sellerId/version/publicationStatus/좌석 준비 내부 오류/제외 사유를 노출하지 않는다. (원본 복원: performance/api.md:97-108,270-285, 담당 확인 필요)

### GET /api/performances/{performanceId}/schedules

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 누구나; 성공: 200.
- R: P18.
- E: 400 VALIDATION_ERROR.

### GET /api/schedules/{scheduleId}

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 누구나; 성공: 200.
- R: P19.
- E: 400 VALIDATION_ERROR.
- 공개 부모의 미삭제 회차만 반환한다. 존재하지 않거나 일반 조회 불가면404 SCHEDULE_NOT_FOUND다. 좌석 선택 가능 여부와 잔여석은 seat API에서 별도로 조회한다. (원본 복원: performance/api.md:388-397,496-513, 담당 확인 필요)

### GET /api/performances/{performanceId}/seat-layout

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 누구나; 성공: 200.
- R: P20.
- E: 400 VALIDATION_ERROR.

### POST /internal/schedules/{scheduleId}/sale-validations

- 상태: 초안(#54 상세 DTO, 구현 전). 공통 정책 합의와 상세 필드 합의를 구분한다.
- 권한: 예매 서비스; 성공: 200.
- B: `configVersion:integer[예매 서비스가 보유한 좌석 구성 버전. 1 이상.]; venueSeatIds:long[][1개 이상, 중복 불가. 공연장 물리 좌석 ID 목록.]`.
- R: P21.
- E: 400 VALIDATION_ERROR.
- 신규 선점용이며 DRAFT 공연은 HTTP200/allowed=false/PERFORMANCE_NOT_PUBLISHED다. policyVersion은 performance.version, 등급은 물리 좌석의 고정 등급, basePrice는 공연별 가격에서 읽는다. 좌석 소속·판매 대상·configVersion을 확인하되 좌석 점유 성공은 보장하지 않는다. (원본 복원: performance/api.md:470-505, 담당 확인 필요)

### 공연 응답 스키마

- P1 = `id:long; name:string; address:string; region:string; version:integer; createdAt:datetime; updatedAt:datetime`.
- P2 = `items:Venue[]; items[].id:long; items[].name:string; items[].address:string; items[].region:string; items[].version:integer; items[].createdAt:datetime; items[].updatedAt:datetime; page:integer; size:integer; totalElements:integer`.
- P3 = `grades:Grade[]; grades[].id:long; grades[].name:string; venueVersion:integer`.
- P4 = `grade:Grade; grade.id:long; grade.name:string; venueVersion:integer`.
- P5 = `createdCount:integer; createdSeatIds:long[]; venueVersion:integer`.
- P6 = `venueId:long; venueVersion:integer; seats:Seat[]; seats[].id:long; seats[].section:string; seats[].rowLabel:string; seats[].seatNumber:integer; seats[].venueSeatGradeId:long; seats[].gradeName:string`.
- P7 = `seat:Seat; seat.id:long; seat.section:string; seat.rowLabel:string; seat.seatNumber:integer; seat.venueSeatGradeId:long; seat.gradeName:string; venueVersion:integer`.
- P8 = `id:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; publicationStatus:string; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; version:integer; createdAt:datetime; updatedAt:datetime; sellerId:long`.
- P9 = `items:PerformanceManageSummary[]; items[].id:long; items[].venue:VenueSummary; items[].venue.id:long; items[].venue.name:string; items[].venue.address:string; items[].venue.region:string; items[].title:string; items[].genre:string; items[].posterUrl:string|null; items[].publicationStatus:string; items[].operationStatus:string; items[].period:Period|null; items[].period.from:date; items[].period.to:date; items[].version:integer; items[].sellerId:long; page:integer; size:integer; totalElements:integer`.
- P10 = `id:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; publicationStatus:string; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; version:integer; createdAt:datetime; updatedAt:datetime; sellerId:long; gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:long; gradePrices[].gradeName:string; gradePrices[].price:integer; excludedSeats:ExcludedSeat[]; excludedSeats[].venueSeatId:long; excludedSeats[].reason:string; schedules:ScheduleManage[]; schedules[].id:long; schedules[].performanceId:long; schedules[].startAt:datetime; schedules[].bookingOpenAt:datetime; schedules[].bookingCloseAt:datetime; schedules[].operationStatus:string; schedules[].seatSetupStatus:string; schedules[].salesSuspended:boolean; schedules[].salesStatus:string; schedules[].canceledAt:datetime|null; schedules[].seatConfigVersion:integer; schedules[].seatSetupRequestId:string; schedules[].seatSetupError:string|null; schedules[].suspensionReason:string|null; schedules[].cancelReason:string|null; schedules[].version:integer`.
- P11 = `gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:long; gradePrices[].gradeName:string; gradePrices[].price:integer; performanceVersion:integer`.
- P12 = `excludedSeats:ExcludedSeat[]; excludedSeats[].venueSeatId:long; excludedSeats[].reason:string; performanceVersion:integer`.
- P13 = `id:long; performanceId:long; startAt:datetime; bookingOpenAt:datetime; bookingCloseAt:datetime; operationStatus:string; seatSetupStatus:string; salesSuspended:boolean; salesStatus:string; canceledAt:datetime|null; seatConfigVersion:integer; seatSetupRequestId:string; seatSetupError:string|null; suspensionReason:string|null; cancelReason:string|null; version:integer`.
- P14 = `scheduleId:long; operationStatus:string; canceledAt:datetime; refundProcessingStatus:string; version:integer`.
- P15 = `scheduleId:long; salesSuspended:boolean; suspensionReason:string|null; version:integer`.
- P16 = `items:PerformanceSummary[]; items[].id:long; items[].venue:VenueSummary; items[].venue.id:long; items[].venue.name:string; items[].venue.address:string; items[].venue.region:string; items[].title:string; items[].genre:string; items[].posterUrl:string|null; items[].operationStatus:string; items[].period:Period|null; items[].period.from:date; items[].period.to:date; page:integer; size:integer; totalElements:integer`.
- P17 = `id:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; title:string; genre:string; ageRating:string; runningTime:integer; castInfo:string|null; description:string|null; posterUrl:string|null; operationStatus:string; publishedAt:datetime|null; period:Period|null; period.from:date; period.to:date; createdAt:datetime; updatedAt:datetime; gradePrices:GradePrice[]; gradePrices[].venueSeatGradeId:long; gradePrices[].gradeName:string; gradePrices[].price:integer`.
- P18 = `performanceId:long; schedules:SchedulePublic[]; schedules[].id:long; schedules[].performanceId:long; schedules[].startAt:datetime; schedules[].bookingOpenAt:datetime; schedules[].bookingCloseAt:datetime; schedules[].operationStatus:string; schedules[].seatSetupStatus:string; schedules[].salesSuspended:boolean; schedules[].salesStatus:string; schedules[].canceledAt:datetime|null`.
- P19 = `id:long; performanceId:long; startAt:datetime; bookingOpenAt:datetime; bookingCloseAt:datetime; operationStatus:string; seatSetupStatus:string; salesSuspended:boolean; salesStatus:string; canceledAt:datetime|null`.
- P20 = `performanceId:long; venue:VenueSummary; venue.id:long; venue.name:string; venue.address:string; venue.region:string; seats:LayoutSeat[]; seats[].id:long; seats[].section:string; seats[].rowLabel:string; seats[].seatNumber:integer; seats[].venueSeatGradeId:long; seats[].gradeName:string; seats[].isSellable:boolean; seats[].basePrice:integer|null`.
- P21 = `allowed:boolean; reasonCode:string|null; scheduleId:long; configVersion:integer; policyVersion:integer; performanceTitle:string; scheduleStartAt:datetime; validatedAt:datetime; seats:ValidationSeat[]; seats[].venueSeatId:long; seats[].venueSeatGradeId:long; seats[].gradeName:string; seats[].basePrice:integer`.


공연 공통 업무 오류: 400 INVALID_TIME_RANGE/VENUE_GRADE_MISMATCH/GRADE_PRICE_MISSING;404 PERFORMANCE_NOT_FOUND/SCHEDULE_NOT_FOUND/VENUE_NOT_FOUND;409 PUBLICATION_REQUIREMENTS_NOT_MET/PERFORMANCE_CONFIGURATION_LOCKED/VENUE_LAYOUT_IN_USE/DUPLICATE_SCHEDULE/DUPLICATE_VENUE_SEAT/DELETE_NOT_ALLOWED/RESERVATION_EXISTS/DUPLICATE_VENUE_GRADE/VENUE_GRADE_IN_USE/SEAT_CONFIG_VERSION_MISMATCH;503 DEPENDENCY_UNAVAILABLE. 각 리소스 명령의 조건에만 적용한다. 등급/좌석 미존재 코드는 미정.

판매 검증 P21.reasonCode: 허용 시 null; 거절 시 PERFORMANCE_NOT_PUBLISHED/PERFORMANCE_CANCELED/SCHEDULE_CANCELED/SALES_SUSPENDED/SEAT_SETUP_NOT_READY/BEFORE_OPEN/SALES_CLOSED/SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH. 업무 거절은 HTTP200,allowed=false,seats=[]; 삭제/미존재는 HTTP404. success HTTP200,allowed=true이면 요청 좌석 가격을 반환한다.

## 좌석 — reservation-service / seat

상태: 외부 API는 초안(구현 전). #34 Facade 계약은 합의이며 별도 절의 구현 상태를 따른다.

| Method URI | 권한 | 성공 | B | R | 추가 오류 |
|---|---|---|---|---|---|
| GET /api/schedules/{scheduleId}/seats | JWT+X-Queue-Token | 200 | 없음 | scheduleId:long,configVersion:long,asOf:datetime,seats:SelectableSeat[] | 403 QUEUE_TOKEN_INVALID |
| GET /api/schedules/{scheduleId}/remaining | 공개 | 200 | 없음 | scheduleId:long,grades:[{venueSeatGradeId:long,gradeName:string,remaining:int}],asOf:datetime,cached:boolean | — |
| POST /api/schedules/{scheduleId}/holds | USER+X-Queue-Token | 201; 동일 유효 선점 200 | scheduleSeatId:long | reservationId:long,scheduleSeatId:long,status:HOLD,holdExpiresAt:datetime,basePrice:int | 404 SEAT_NOT_FOUND;409 SEAT_ALREADY_TAKEN/PURCHASE_LIMIT_EXCEEDED/SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH;503 SERVICE_UNAVAILABLE |
| DELETE /api/schedules/{scheduleId}/holds/{reservationId} | 본인 JWT | 204 | 없음 | 없음 | 403 FORBIDDEN;404 RESERVATION_NOT_FOUND;409 INVALID_STATUS_TRANSITION |

- SelectableSeat = `{scheduleSeatId:long,venueSeatId:long,section:string,rowLabel:string,seatNumber:int,venueSeatGradeId:long,gradeName:string,basePrice:int|null,selectable:boolean}`. 다른 사용자의 예약 ID/만료 시각 제외.
- 선점 판매 거절은 sale-validations의 reasonCode를 외부 code로 반환: 기본 400, SEAT_NOT_SELLABLE/SEAT_CONFIG_VERSION_MISMATCH만 409. 삭제·미존재는 404. 연령 미달 오류는 미합의이며 architecture 14절을 따른다.

- 선점 API도 입장 토큰이 없거나 만료되면 Gateway가 403 QUEUE_TOKEN_INVALID로 거절한다. (원본 복원: seat/api.md:124,191, 담당 확인 필요)
- 요청 좌석이 없거나 경로의 scheduleId 소속이 아니면 404 SEAT_NOT_FOUND다. (원본 복원: seat/api.md:163,192, 담당 확인 필요)
- 다른 회차 좌석을 404로 막는 이유는 다른 회차 좌석으로 판매 기간·매수 제한을 우회하지 못하게 하려는 것이다. (원본 복원: seat/api.md:163, 담당 확인 필요)
- 판매 검증이 허용이어도 좌석의 config_version·base_price가 검증 응답과 다르면 409 SEAT_CONFIG_VERSION_MISMATCH다. (원본 복원: seat/api.md:169,195, 담당 확인 필요)
- is_sellable=false 좌석은 판매 검증 결과와 상관없이 409 SEAT_NOT_SELLABLE이다. (원본 복원: seat/api.md:169,194, 담당 확인 필요)
- 선점 해제는 잠금 없이 BookingFacade.getReservation으로 예약을 먼저 읽어 404·403을 가린다. (원본 복원: seat/api.md:236, 담당 확인 필요)
- 예약이 없거나 예약의 scheduleId가 경로와 다르면 404 RESERVATION_NOT_FOUND, 본인 예약이 아니면 403 FORBIDDEN이다. (원본 복원: seat/api.md:236,256-257, 담당 확인 필요)
- 해제의 회차 확인은 좌석이 아니라 예약으로 한다. 이미 끝난 예약에는 좌석이 남아 있지 않을 수 있다. (원본 복원: seat/api.md:236, 담당 확인 필요)
- 모든 facade가 MANDATORY이므로 호출자 트랜잭션 안에서 비잠금 사전 조회 후 view.scheduleSeatIds 순서로 좌석을 잠그고 BookingFacade.release를 부른다. (원본 복원: seat/api.md:237-238, 담당 확인 필요)
- 좌석 API 응답 시각은 +09:00 오프셋을 포함한 ISO 8601이다. (원본 복원: seat/api.md:15, 담당 확인 필요)

## 예매 — reservation-service / booking

상태: 단건 조회·checkout·confirm의 현행 Controller는 구현됨. checkout/confirm은 결제 미연동이며 목표 필드와 현행 DTO는 아래처럼 다르다. 목록·취소는 초안이다.

| Method URI | 권한 | 성공 | B / Q | R | 추가 오류 |
|---|---|---|---|---|---|
| GET /api/reservations/{reservationId} | 본인 USER 또는 MASTER | 200 | 없음 | Reservation | 404 RESERVATION_NOT_FOUND;403 FORBIDDEN |
| POST /api/reservations/{reservationId}/payments/checkout | 본인 USER | 200 | userCouponId?:long,pgProvider:string(TOSS/KAKAO) | orderId:string,amount:int,checkoutUrl:string\|null | 409 HOLD_EXPIRED/INVALID_STATUS_TRANSITION;쿠폰 오류;503 SERVICE_UNAVAILABLE |
| POST /api/reservations/{reservationId}/payments/confirm | 본인 USER | 200 | orderId:string(1~64),pgAuthToken:string(1~255) | Reservation | 400 VALIDATION_ERROR;409 HOLD_EXPIRED/INVALID_STATUS_TRANSITION |
| GET /api/reservations | 본인 USER | 200 | Q status?:string,page?:int=0,size?:int=10(10/30/50),sort?:string=createdAt,DESC | PageC<ReservationSummary>(항목 필드 미정) | 구현 전 |
| POST /api/reservations/{reservationId}/cancel | 본인 USER | 200 | reason?:string | Reservation(CANCELING/CANCELED) | 취소 정책/세부 오류 미정; 구현 전 |

- Reservation 현행 DTO = `{reservationId:long,scheduleId:long,seatIds:long[],status:string,holdExpiresAt:datetime,orderId:string|null,paymentAmount:long|null}`.
- checkout 현행 DTO는 `couponId`, `paymentWindowUrl`, 선택 pgProvider. 합의 목표는 위 표의 `userCouponId`, `checkoutUrl`, 필수 pgProvider. 응답 좌석명 목표는 `scheduleSeatIds`(MVP 길이 1). 현재 미연동 포트는 503이며 문서 개정만으로 구현된 것으로 보지 않는다.

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
- cancel: CONFIRMED이고 취소 가능 기간 안인 예매만 받는다. 취소할 수 없으면 NOT_CANCELLABLE이다. (원본 복원: reservation/orchestration.md:264,353, 담당 확인 필요)
- cancel: CANCELING에서 같은 취소가 다시 오면 결제를 다시 부르지 않는다. 200과 현재 상태를 준다. (원본 복원: reservation/orchestration.md:274, 담당 확인 필요)
- cancel: 환불 요청 결과를 모르면 200과 CANCELING을 준다. (원본 복원: reservation/api.md:174, reservation/orchestration.md:268, 담당 확인 필요)

## 대기열 — reservation-service / queue

상태: 외부 응답 상세는 초안(구현 전). 공연 단위 입장 토큰 정책과 구현 완료를 구분한다.

| Method URI | 권한 | 성공 | B | R |
|---|---|---|---|---|
| POST /api/schedules/{id}/queue | JWT | 200 | 미기재 | position:int,aheadCount:int,estimatedWaitSeconds:int |
| GET /api/schedules/{id}/queue/me | JWT | 200 | 없음 | 미정 |
| DELETE /api/schedules/{id}/queue/me | JWT | 204 | 없음 | 없음 |

입장 토큰 조회/전달 응답과 세부 오류는 미합의이며 architecture 14절을 따른다. 존재하지 않는 발급 API를 추가하지 않는다.

- 입장 토큰 X-Queue-Token은 공연(performanceId) 단위이고 TTL은 10분이다. (원본 복원: seat/api.md:17, 담당 확인 필요)
- 좌석 선점 여부에 따라 입장 토큰 TTL을 늘리거나 없애지 않는다. (원본 복원: seat/api.md:17, 담당 확인 필요)
- Gateway는 토큰의 회원과 공연 범위를 검증하고, 공연 범위는 경로의 scheduleId로 확인한다. (원본 복원: seat/api.md:17, seat/plan.md:109, 담당 확인 필요)
- 입장 토큰은 좌석 조회(GET /api/schedules/{scheduleId}/seats)와 선점(POST /api/schedules/{scheduleId}/holds)에만 적용한다. (원본 복원: seat/api.md:23-25,93,207, 담당 확인 필요)

## 쿠폰 — reservation-service / coupon

상태: 생성·수정·발급 중단·삭제는 구현됨. 목록·상세 조회, 발급, 내 쿠폰 조회는 초안(구현 전). 동기201이 현재 API 계약이며202 전환은 architecture 14절의 미합의다.

로그인은 USER/SELLER/MASTER 모두. `PageC<T>={content:T[],page:int,size:int,totalElements:long,totalPages:int,sort:형식 미정}`. page=0,size=10(10/30/50).

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
- CouponDetail = Coupon + `{issuedByMe:boolean}`. SELLER의 issuedByMe=false.
- UserCoupon = `{userCouponId:long,couponId:long,name:string,discountAmount:int,status:string,issuedAt:datetime,expiresAt:datetime,usedAt:datetime|null}`.
- 발급 시작 후 수정 가능: name,issueEndAt,totalQuantity(>=issuedQuantity). 삭제는 issuedQuantity=0일 때만. 발급은 1인 1장.
- 발급이 끝난(ENDED) 쿠폰의 issueEndAt은 바꿀 수 없고 409 COUPON_ALREADY_CLOSED다. 종료 시각을 미래로 바꾸면 중단한 쿠폰이 다시 열리기 때문이다. 다시 발급하려면 새 쿠폰을 만든다.

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

상태: HTTP 계약은 합의, Controller 구현 전. 관리자 응답 상세는 미정이다.

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

상태: BookingFacade는 합의·구현됨(쿠폰 호출 제외), SeatFacade는 합의·구현 전, CouponUsage는 초안이다.

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

- BookingFacade 공통: 상태를 바꾸지 못한 경우(0행)는 예외가 아니라 반환값으로 알린다. 다음 행동은 호출자가 고른다. (원본 복원: shared/modules/booking-facade.md:17, 담당 확인 필요)
- hold: HOLD 예매와 좌석마다 티켓을 만든다. 티켓 가격은 넘겨받은 선점 시점 가격이다. (원본 복원: shared/modules/booking-facade.md:40, 담당 확인 필요)
- hold: 1차는 좌석 목록 길이1이다. 같은 좌석이 두 번 들어오거나 가격이 음수면 INVALID_REQUEST이다. (원본 복원: shared/modules/booking-facade.md:41, 담당 확인 필요)
- hold: 활성 예매(HOLD·PAYMENT_IN_PROGRESS·CONFIRMED·CANCELING)가 이미 있으면 부분 UNIQUE 위반을 잡지 않고 그대로 던진다. 이 예외는 호출 안에서 드러난다. (원본 복원: shared/modules/booking-facade.md:42, 담당 확인 필요)
- getReservation: 상태와 상관없이 예매를 읽는다. 없으면 빈 값이다. (원본 복원: shared/modules/booking-facade.md:61, 담당 확인 필요)
- getReservation: scheduleSeatIds는 오름차순이고, 호출자는 이 순서로 좌석을 잠근다. (원본 복원: shared/modules/booking-facade.md:56, 담당 확인 필요)
- release: 없는 예매면 RESERVATION_NOT_FOUND, 남의 예매면 FORBIDDEN 예외다. 그 밖의 경우는 모두 ReleaseResult로 알린다. (원본 복원: shared/modules/booking-facade.md:80, 담당 확인 필요)
- release: 결제 시작과 해제가 동시에 오면 한쪽만 성공한다. 진 쪽의 결과는 NOT_RELEASABLE이다. (원본 복원: shared/modules/booking-facade.md:81, 담당 확인 필요)
- release: 쿠폰 연동 시 잡아 둔 쿠폰은 booking이 같은 트랜잭션에서 CouponUsage.release로 해제한다(현행 미연결). seat는 쿠폰을 다루지 않는다. (원본 복원: shared/modules/booking-facade.md:82, 담당 확인 필요)
- countActiveSeats: 예매 수가 아니라 활성 예매의 티켓 수를 센다. (원본 복원: shared/modules/booking-facade.md:88,97, 담당 확인 필요)
- countActiveSeats: 현행 집계 대상은 HOLD·PAYMENT_IN_PROGRESS·CONFIRMED이다. CANCELING은 취소를 구현할 때 더한다. (원본 복원: shared/modules/booking-facade.md:97, 담당 확인 필요)
- findActiveHold: 이 회원의 이 회차 HOLD 예매를 만료 여부와 상관없이 돌려준다. (원본 복원: shared/modules/booking-facade.md:89, 담당 확인 필요)
- expireIfDue: `status=HOLD AND hold_expires_at<=:now`일 때만 EXPIRED로 바꾼다. 쿠폰 연동 시 같은 트랜잭션에서 해제한다(현행 미연결). (원본 복원: shared/modules/booking-facade.md:90, 담당 확인 필요)
- expireIfDue: PAYMENT_IN_PROGRESS면 선점 시간이 지났어도 false이다. (원본 복원: shared/modules/booking-facade.md:98, 담당 확인 필요)

- findPaymentInProgress에 빈 목록을 넘기면 쿼리 없이 빈 집합을 돌려준다. (원본 복원: shared/modules/booking-facade.md:99, 담당 확인 필요)
- 결제 성공 확정: booking은 lockSeats로 좌석이 이 예약의 HOLD인지 먼저 확인하고, 예약을 PAYMENT_IN_PROGRESS→CONFIRMED(조건부)로 바꾼 뒤 sell을 부른다. (원본 복원: shared/modules/seat-facade.md:51, 담당 확인 필요)
- 확정 때 좌석이 이 예약 것이 아니면 확정하지 않고 CONFIRM_FAILED 보상 대상으로 남긴다. CANCELING 전이와 MVP 구현 범위는 architecture 14절에서 조율한다. (원본 복원: shared/modules/seat-facade.md:51, 담당 확인 필요)
- 선점 만료 스케줄러: 후보를 잠금 없이 조회하고, 예약마다 lockSeats → HOLD→EXPIRED(조건부) → 1건이면 release 순서로 처리한다. (원본 복원: shared/modules/seat-facade.md:52, 담당 확인 필요)
- 결제 실패이고 시간이 지났으면 lockSeats → PAYMENT_IN_PROGRESS→EXPIRED → release 순서다. (원본 복원: shared/modules/seat-facade.md:53, 담당 확인 필요)
- 예매 취소: CONFIRMED→CANCELING 커밋 → 환불 요청 접수 → lockSeats → CANCELING→CANCELED → release 순서다. (원본 복원: shared/modules/seat-facade.md:54, 담당 확인 필요)
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
| GET /fake/admin/transactions | Q orderId?:string,status?:string,limit?:int=100(1~1000 보정) | 200 `TransactionView[]`; 최신순 |
| DELETE /fake/admin/transactions | 없음 | 204 거래/멱등 기록 초기화 |

- provider=TOSS/KAKAO; operation=READY(KAKAO만)/CONFIRM/INQUIRE/CANCEL.
- Fault=`{latencyMs?:long>=0,serverErrorRate?:double[0,1],failRate?:double[0,1],responseLossRate?:double[0,1],delayedApprovalRate?:double[0,1],delayedApprovalMs?:long>=0,holdMs?:long>=0(기본15000),rateLimitPerSecond?:int>=0(0=무제한)}`. 생략은 장애 없음.

- TransactionView=`{provider:string,paymentKey:string,orderId:string,amount:int,status:string,requestedAt:datetime,approvedAt:datetime|null,canceledAt:datetime|null,cancelTransactionKey:string|null,failureCode:string|null}`.
