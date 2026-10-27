# 공통 규약·코드 컨벤션

기준: develop 7a48c5e의 common-core/common·Gradle·CI와 팀 합의. 문서 진입점은 이 파일이다.

| 읽을 문서 | 단일 책임 |
|---|---|
| [api.md](api.md) | 외부/내부 HTTP, 모듈 인터페이스, 메시지 계약·요청/응답 |
| [tables.md](tables.md) | 필드·타입·제약·enum; 현행 DDL과 추가 설계 구분 |
| 이 문서 | 공통 API 형식·코드·협업 규약 |
| [architecture.md](architecture.md) | 책임·통신·상태 전이·복구·필수 검증·미합의 사항 |
| [infra.md](infra.md) | 서버·포트·배포·관측 환경 |

docs에는 이 5개만 유지한다. 이슈/PR에 작업 계획·진행·결정 근거·실험 결과를 기록하고 확정된 규격만 해당 파일에 반영한다. 기존 문서의 상세 이력·폐기 대안은 Git 이력을 조회한다. 새 도메인도 파일을 추가하지 않고 해당 절을 추가한다.
`backend/payment-service/docs-local/`은 결제 담당의 비추적 로컬 기록이며 공통 계약을 대체하지 않는다.
각 사실은 한 곳에만 정의한다. API 필드/저장 필드의 이름 대응은 필요한 경우 명시한다. 초안·미구현·합의를 구분하며, 미합의 항목은 architecture의 한 표에서 관리한다. 구현/최신 합의가 다르면 차이를 알리고 담당자 합의 없이 계약을 바꾸지 않는다.
서비스·모듈 계약 변경은 **계약 이슈 → 제공/소비 담당 합의 → 계약 PR → 구현**. 합의 이슈·적용 시점은 변경 절 또는 PR에 남긴다. 템플릿은 `.github/ISSUE_TEMPLATE/`, `.github/pull_request_template.md`를 따른다. 기본 PR 대상 develop, 릴리스 main.

## API 공통

| 항목 | 규약 |
|---|---|
| 경로 | 외부 `/api/**`, 내부 `/internal/**`. Fake PG만 외부 제공자 호환 `/v1/**` 등을 유지 |
| JSON | camelCase. Fake KAKAO는 호환용 snake_case |
| ID·금액 | 회원/판매자 포함 BIGINT→Java Long→JSON number. requestId/eventId/orderId는 string. KRW 원 단위 정수, 목표 INT(현행 차이는 tables) |
| 외부 성공 | `ApiResponse<T>={status:int,message:string,data:T}`. HTTP status와 본문 status 일치. `ok(data)`는 200/SUCCESS, `created(message,data)`는 201. 204 본문 없음 |
| 내부 성공 | DTO 원문, ApiResponse 없음. 업무 실패는 계약에 따라 HTTP 200의 status로 전달 가능 |
| 오류 | `ErrorResponse={status:int,code:string,message:string,path:string,timestamp:datetime,errors?:[{field:string,reason:string}]}`. null 필드는 제외. errors는 필드 검증 오류에서만 |
| 공통 오류 | 400 VALIDATION_ERROR/INVALID_REQUEST;401 UNAUTHORIZED/TOKEN_ROLE_MISMATCH;403 FORBIDDEN;404 NOT_FOUND;405 METHOD_NOT_ALLOWED;406 NOT_ACCEPTABLE;409 INVALID_STATUS_TRANSITION;415 UNSUPPORTED_MEDIA_TYPE;500 INTERNAL_SERVER_ERROR;503 SERVICE_UNAVAILABLE |
| 업무 오류 | 서비스 enum이 ErrorCode 구현, code()=enum 이름. 공통 이름 재정의 금지. 계약의 상태·오류를 임의 통합하지 않음 |
| 인증 | 외부 Authorization: Bearer JWT. 현재 사용자 `@CurrentUser UserContext`, 역할 `@RequireRole`, 소유권은 application에서 검사. 요청 본문 사용자 ID 신뢰 금지 |
| 사용자 헤더 | 현행 UserHeaders: X-User-Id, X-Username, X-User-Role. Gateway가 외부 입력을 제거하고 인증 결과로 주입, 내부 Feign 전달. 사용자 컨텍스트와 서비스 인증은 별개 |
| 감사 주체 | 현행 HeaderAuditorAware는 X-Username, 없거나 요청 밖이면 SYSTEM. 공연 명세의 ID 문자열 정책은 구현과 다름; X-Username을 문서만으로 제거하지 않음 |
| 시간 | 업무 기준 Asia/Seoul. API 목표 ISO 8601 +09:00. 현행 ErrorResponse·회원·예매 LocalDateTime은 오프셋 없음; 일괄 적용 미완료. Fake PG 시각은 해당 호환 계약 |
| 페이징 | page는0부터. 공연 PageP(items), 예매·쿠폰 PageC(content)는 api의 현행 계약 유지. 공통 Page DTO·size 통일은 미합의 |
| 재시도 | Feign IdempotentRetryConfig는 조회/명시된 멱등 API에만 적용. 응답 유실을 성공/실패로 단정하지 않고 같은 업무 키 조회 |
| 내부 보호 | Gateway는 /internal 접근 차단. 서비스 포트 외부 비노출. 서비스 간/관리자 인증 방식은 미합의이며 내부망만으로 운영 권한 보장으로 간주하지 않음 |

## 코드·영속성

- Gradle 루트 `backend/`, toolchain JDK17, UTF-8. JAVA_HOME 변경 금지. PowerShell은 `./gradlew.bat`, PowerShell5.1에서 `&&` 사용 금지.
- 패키지 `com.sparta.ticket.{서비스}`의 presentation/application/domain/infrastructure. Controller는 검증·변환, application은 유스케이스·트랜잭션, domain은 상태/금액 규칙, infrastructure는 DB/원격 구현.
- domain은 application/infrastructure/presentation에 의존하지 않는다. 다른 서비스 스키마 직접 조회 금지. Feign은 infrastructure/client, PG는 별도 어댑터. 모듈 간 Entity/Repository 접근 금지, 합의한 facade 패키지만 사용.
- 모듈 경계 검사는 `SEAT_USES_BOOKING_ONLY_THROUGH_FACADE`, `BOOKING_USES_SEAT_ONLY_THROUGH_FACADE`를 따른다. `booking.application.facade`, `seat.application.facade`의 요청·응답 타입으로만 교차 의존한다.
- 업무 Entity는 BaseEntity 상속, 소프트 삭제 대상에 `@SQLRestriction("deleted_at IS NULL")`. Fake PG는 독립 모의 시스템으로 공통 Entity 예외. 금융 기록은 임의 삭제/재계산하지 않는다.
- Entity 변경 시 `backend/sql/{서비스}/schema.sql`(booking은 booking.sql 포함)과 tables를 같은 PR에서 수정. local은 ddl-auto:create, 나머지 validate. 부분 UNIQUE는 ddl-auto만으로 생성되지 않아 SQL 적용 DB에서 검증한다.
- 상태 전이는 Entity 메서드, 조건부 UPDATE/잠금으로 동시성 보호. bulk UPDATE는 감사 컬럼을 직접 갱신한다. 네트워크 호출 중 DB 트랜잭션/잠금 유지 금지.
- 통합 시험은 `PostgresIntegrationTest`(PostgreSQL16 Testcontainers). H2 사용 금지. common의 LayeredArchitectureRules와 모듈 ArchUnit 규칙을 준수한다.
- 주석·문서·커밋은 한국어 평서체. 필요한 설명 주석은 `Note: `. 실제 .env·키·토큰·비밀번호를 읽거나 산출물/로그/이벤트에 남기지 않는다. 예시는 .env.example 사용.

## 품질 검사

| 검사 | 현재 설정/명령(backend에서 실행) |
|---|---|
| 포맷 | `./gradlew.bat spotlessApply`; 검증 `spotlessCheck`. PalantirJavaFormat2.102.0, unused import 제거, 뒤 공백 제거, 마지막 개행 |
| 스타일 | `checkstyleMain checkstyleTest`. Checkstyle10.26.1, 경고0. 이름 규칙, wildcard/redundant/JDK내부/JUnit4 import 금지, 빈 문장/부적절한 빈 catch/equals-hashCode 검사 |
| 단위·통합·계층 | `./gradlew.bat :{모듈}:test`; 전체 `./gradlew.bat build`(Spotless/Checkstyle/ArchUnit 포함). 통합 시험은 Docker 필요 |
| CI | quality=JDK17 Gradle build, smoke=Compose 기동+script/smoke.sh, 최종 build-and-smoke가 둘 다 성공 요구 |
| 검증 보고 | 실제 실행한 검사·환경·결과만 기록. 미실행은 이유 명시. 체크박스/계획을 측정 결과로 보고하지 않음 |

commit/push/PR 생성은 명시 요청 때만. 기존 워킹트리 변경 보존. 강제 초기화·재귀 삭제·볼륨 삭제 등은 루트 AGENTS 안전 지침을 따른다.
