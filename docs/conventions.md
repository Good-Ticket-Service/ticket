# 공통 규약·코드 컨벤션

기준: develop 7a48c5e의 common-core/common·Gradle·CI와 팀 합의. 공통 규칙의 원본은 이 파일이다.

## 문서 선택

세션 시작에는 이 절과 「안전·에이전트 작업」만 한 번 읽는다. **5개 문서 전체를 일괄로 읽지 않는다.** 작업을 분류한 뒤 아래의 관련 절만 찾아 읽고, 같은 세션에서 이미 읽은 내용은 변경되었거나 작업 범위가 달라졌을 때만 다시 읽는다.

| 작업 | 반드시 확인할 범위 |
|---|---|
| 코드 구현·수정 | 이 파일의 코드·영속성. 검증 시 품질 검사 |
| API·Controller·DTO·Feign·모듈 인터페이스 | 이 파일의 API 공통 + [api.md](api.md)의 해당 엔드포인트·공유 DTO. 연동은 제공/소비 양쪽 계약 |
| Entity·쿼리·DDL | [tables.md](tables.md)의 해당 테이블·제약·참조 enum |
| 상태 전이·금액·동시성·트랜잭션·외부 연동 | [architecture.md](architecture.md)의 해당 흐름·불변식·미합의 항목 + 연결된 API/테이블 |
| 배포·라우팅·포트·서비스 설정 | [infra.md](infra.md)의 해당 항목 + 실제 설정 파일 |
| 이슈·커밋·PR·리뷰 | 이 파일의 협업. 작업 종류의 이슈 템플릿 또는 PR 템플릿·CODEOWNERS·CI |
| 질문·조회·문서 수정 | 답변/수정에 필요한 절과 실제 코드만. 구현용 문서를 전부 읽지 않음 |

**구현 전 연관 계약 탐색**

1. 요청을 도메인 이름이 아닌 업무 흐름으로 분류하고 **호출자 → 구현 대상 → 호출 대상 → 결과 소비자**를 식별한다. 동기 응답뿐 아니라 이벤트·폴링·스케줄러·보상 처리의 소비자도 포함한다.
2. 대상 도메인 절로 검색을 제한하지 않는다. `docs/api.md`·`docs/architecture.md` 전체 범위에서 제목·URI·메서드/메시지·상태·공유 식별자를 검색해 연결 관계를 찾는다. 검색 결과 주변의 관련 절만 읽으며 문서 전체를 일괄 로드하지 않는다.
3. 실제 코드의 Controller·호출 포트·Feign/PG 어댑터·이벤트 발행/소비·복구 작업에서 호출 관계를 대조한다. 확인되지 않은 연결은 필요한 범위만 추가 추적하고, 같은 서비스의 다른 모듈도 빠뜨리지 않는다.
4. 연결된 제공/소비 계약의 필드·타입·필수값·결과/오류 의미·멱등성·시간 경계를 함께 확인하고, 관련 테이블 제약과 상태 전이·보상 불변식까지 읽는다. 새 연관 관계를 발견하면 그 부분만 추가 확인한다.
5. 구현 착수 전 확인한 흐름·관련 계약 위치·남은 미확정 사항을 짧게 정리한다. 필요한 계약이 없거나 코드/문서가 충돌하면 기존 미합의 항목과 담당 근거를 확인하고 차이를 알린다. 임의의 API·필드·상태를 만들어 빈칸을 채우지 않으며, 합의가 필요한 부분의 구현은 보류한다.

예: 결제 승인은 결제 API만으로 판단하지 않고 예매의 승인 요청·결과 해석, PG 호출, 결과 불명 복구·확정 실패 보상까지 연결해 확인한다. 관련 없는 도메인의 본문은 읽지 않는다. 기존 코드와 최신 합의가 다른 항목은 현행/설계 구분을 따른다.

## 안전·에이전트 작업

- 실제 .env·.env.local 등 비밀 환경 파일을 읽거나 수정하지 않는다. .env.example만 사용하며 키·토큰·비밀번호를 출력/커밋/업로드하지 않는다.
- 기존 워킹트리 변경을 보존한다. 요청받지 않은 변경을 되돌리거나 함께 커밋하지 않는다. 커밋·푸시·PR 생성은 명시 요청 때만 수행하고 AI 공동 작성자 표시는 임의로 추가하지 않는다.
- git reset --hard, git clean, 파일/디렉터리 재귀 삭제, docker compose down, 볼륨 삭제, script/run.sh --reset은 대상·영향을 설명하고 해당 작업을 명시 승인받았을 때만 실행한다.
- JAVA_HOME을 바꾸지 않는다. 도구의 실제 샌드박스·승인 정책을 따르며 .claude/settings*.json은 Codex 실행 권한으로 간주하지 않는다.
- 개인 CLAUDE.local.md가 있으면 적용하되 커밋하지 않는다. HANDOFF.md는 세션 인계 확인이 필요할 때만 읽고 현재 브랜치/diff·계약과 대조하며 커밋하지 않는다. backend/payment-service/docs-local/은 비추적 개인 기록이며 공용 계약을 대체하지 않는다.
- add-service/add-internal-api/load-test 스킬은 명시 호출 때만 해당 .claude/skills/{이름}/SKILL.md를 읽는다. Codex 진입점은 .agents/skills/다. 뒤 두 스킬은 절차의 틀이며 완성된 자동화가 아니다.
- 동시성 리뷰 요청 때만 .claude/agents/concurrency-reviewer.md를 읽고 읽기 전용으로 따른다. Codex 역할 연결은 .codex/agents/concurrency-reviewer.toml이다. 별도 에이전트 실행은 사용자 또는 적용 지침의 명시 허용이 필요하다.

## 문서 운영·계약 변경

docs에는 api.md/tables.md/conventions.md/architecture.md/infra.md 5개만 둔다. 각 사실은 한 곳에만 정의하고 다른 파일은 참조한다. 상세 작업 계획·진행·실험 결과·결정 근거는 이슈/PR, 과거 이력은 Git에서 조회한다. 새 도메인도 기존 파일의 해당 절에 추가한다.
서비스·모듈 계약 변경은 **계약 이슈 → 제공/소비 담당 합의 → 계약 PR → 구현**. 초안은 구현 근거로 확정하지 않는다. 합의·구현 상태와 근거를 구분하고, 미합의 목록은 architecture 한 곳에서 관리한다. API/Entity/DDL 변경과 해당 명세 갱신은 같은 PR에 포함한다.

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
| 업무 오류 | 서비스 enum이 ErrorCode 구현, code()=enum 이름. CommonErrorCode의 공통 이름 재정의 금지. 계약의 상태·오류를 임의 통합하지 않음 |
| 인증 | 외부 Authorization: Bearer JWT. 현재 사용자 `@CurrentUser UserContext`, 역할 `@RequireRole`, 소유권은 application에서 검사. 요청 본문 사용자 ID 신뢰 금지 |
| 사용자 헤더 | 현행 UserHeaders: X-User-Id, X-Username, X-User-Role. Gateway가 외부 입력을 제거하고 인증 결과로 주입, 내부 Feign 전달. 사용자 컨텍스트와 서비스 인증은 별개 |
| 감사 주체 | 현행 HeaderAuditorAware는 X-Username, 없거나 요청 밖이면 SYSTEM. 공연 명세의 ID 문자열 정책은 구현과 다름; X-Username을 문서만으로 제거하지 않음 |
| 시간 | 업무 기준 Asia/Seoul. API 목표 ISO 8601 +09:00. 현행 ErrorResponse·회원·예매 LocalDateTime은 오프셋 없음; 일괄 적용 미완료. Fake PG 시각은 해당 호환 계약 |
| 페이징 | page는0부터. 공연 PageP(items), 예매·쿠폰 PageC(content)는 api의 현행 계약 유지. 공통 Page DTO·size 통일은 미합의 |
| 재시도 | Feign IdempotentRetryConfig는 조회/명시된 멱등 API에만 적용. 응답 유실을 성공/실패로 단정하지 않고 같은 업무 키 조회 |
| 내부 보호 | Gateway는 /internal 접근 차단. 서비스 포트 외부 비노출. 서비스 간/관리자 인증 방식은 미합의이며 내부망만으로 운영 권한 보장으로 간주하지 않음 |

## 코드·영속성

- Gradle 루트 `backend/`, toolchain JDK17, UTF-8. PowerShell은 `./gradlew.bat`, PowerShell5.1에서 `&&` 사용 금지; 여러 명령의 종료 코드를 각각 확인한다.
- 패키지 `com.sparta.ticket.{서비스}`의 presentation/application/domain/infrastructure. Controller는 검증·변환, application은 유스케이스·트랜잭션, domain은 상태/금액 규칙, infrastructure는 DB/원격 구현.
- domain은 application/infrastructure/presentation에 의존하지 않는다. 다른 서비스 스키마 직접 조회 금지. Feign은 infrastructure/client, PG는 별도 어댑터. 모듈 간 Entity/Repository 접근 금지, 합의한 facade 패키지만 사용.
- 모듈 경계 검사는 `SEAT_USES_BOOKING_ONLY_THROUGH_FACADE`, `BOOKING_USES_SEAT_ONLY_THROUGH_FACADE`를 따른다. `booking.application.facade`, `seat.application.facade`의 요청·응답 타입으로만 교차 의존한다.
- 업무 Entity는 BaseEntity 상속, 소프트 삭제 대상에 `@SQLRestriction("deleted_at IS NULL")`. Fake PG는 독립 모의 시스템으로 공통 Entity 예외. 금융 기록은 임의 삭제/재계산하지 않는다.
- Entity 변경 시 `backend/sql/{서비스}/schema.sql`(booking은 booking.sql 포함)과 tables를 같은 PR에서 수정. local은 ddl-auto:create, 나머지 validate. 부분 UNIQUE는 ddl-auto만으로 생성되지 않아 SQL 적용 DB에서 검증한다.
- 상태 전이는 Entity 메서드, 조건부 UPDATE/잠금으로 동시성 보호. bulk UPDATE는 감사 컬럼을 직접 갱신한다. 네트워크 호출 중 DB 트랜잭션/잠금 유지 금지.
- 통합 시험은 `PostgresIntegrationTest`(PostgreSQL16 Testcontainers). H2 사용 금지. common의 LayeredArchitectureRules와 모듈 ArchUnit 규칙을 준수한다.
- 주석·문서·커밋은 한국어 평서체. 필요한 설명 주석은 `Note: `. 포맷은 손으로 맞추지 않고 Spotless를 사용한다.

## 품질 검사

| 검사 | 현재 설정/명령(backend에서 실행) |
|---|---|
| 포맷 | `./gradlew.bat spotlessApply`; 검증 `spotlessCheck`. PalantirJavaFormat2.102.0, unused import 제거, 뒤 공백 제거, 마지막 개행 |
| 스타일 | `checkstyleMain checkstyleTest`. Checkstyle10.26.1, 경고0. 이름 규칙, wildcard/redundant/JDK내부/JUnit4 import 금지, 빈 문장/부적절한 빈 catch/equals-hashCode 검사 |
| 단위·통합·계층 | `./gradlew.bat :{모듈}:test`; 전체 `./gradlew.bat build`(Spotless/Checkstyle/ArchUnit 포함). 통합 시험은 Docker 필요 |
| CI | quality=JDK17 Gradle build, smoke=Compose 기동+script/smoke.sh, 최종 build-and-smoke가 둘 다 성공 요구 |

## 협업

| 항목 | 규칙 |
|---|---|
| 이슈 | .github/ISSUE_TEMPLATE/ 중 작업 종류의 양식 사용. 범위·완료 조건을 정의하고 1~2일을 넘는 큰 작업은 분리 |
| 브랜치 | 최신 develop에서 생성. 이름 `<type>/<scope>/<이슈번호>-<설명>`. 일반 PR은 develop, 릴리스는 main. 두 통합 브랜치에 직접/강제 push 금지 |
| scope/type | scope=user/performance/reservation/payment/gateway/config/eureka/common/infra/project. type=feat/fix/refactor/docs/test/style/build/ci/chore |
| 커밋·PR 제목 | `<type>(<scope>): <한국어 변경 내용>`. 한 PR은 한 목적, 의미 단위 커밋. 무관한 포맷/생성물 포함 금지 |
| 선행 PR | 의존 작업만 임시 base 사용. 선행 머지 뒤 develop으로 rebase/base 변경·CI/승인 재확인. 공유 이력 변경은 협업자와 먼저 맞추고 force-with-lease 사용 |
| PR 작성 | .github/pull_request_template.md의 이유·변경·검증·연결 이슈 기재, 해당할 때 Closes #번호. .github/CODEOWNERS와 .github/workflows/ci.yml은 PR 작업 때만 확인 |
| 리뷰·머지 | 작성자 외 1명 이상 승인, API/DB/메시지/공통 변경은 영향받는 제공·소비 담당 리뷰. CODEOWNERS 주석은 등록 담당 아님. 최종 커밋의 build-and-smoke·승인·대화 해결 확인 후 Rebase and merge, 머지 커밋 금지. 머지된 작업 브랜치 삭제 |
| 검증 보고 | 실제 실행한 검사·환경·결과만 완료로 기록. 미실행은 이유 명시. 기존 체크/계획을 새 검증 결과로 간주하지 않음 |
