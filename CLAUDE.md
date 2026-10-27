# CLAUDE.md

공연 티켓 예매 MSA. 상세 설명은 `README.md`에 있다.

## 구조
- `backend/`가 Gradle 루트다. Gradle 명령은 `backend/`에서 실행한다.
- 모듈: `config-server`(8888), `eureka-server`(8761), `gateway-service`(8080), `user-service`(8081), `performance-service`(8082), `reservation-service`(8083), `payment-service`(8084), `fake-pg`(8090, 테스트용 PG. common·Config·Eureka를 쓰지 않는다), `common-core`(웹 무관 공통 계약), `common`(MVC 서비스 공통)
- 서비스 설정은 `backend/config-repo/{서비스}.yml`, `local` 프로필 전용 값은 `backend/config-repo/application-local.yml`에 둔다. 모듈의 `application.yml`에는 이름과 `spring.config.import`만 둔다.
- `docker-compose.yml`, `infra/`, `script/`는 루트에 있다. Compose의 빌드 컨텍스트는 `backend/`다.

## 문서
- 문서 목록과 도메인·담당 표는 `docs/README.md`에 있다. 도메인 기능을 구현하기 전에 `docs/{도메인}/`의 `plan.md`, `api.md`, `tables.md`를 읽는다.
- 서비스·모듈 간 계약은 `docs/shared/`에 있다. `/internal/**` API나 Feign 클라이언트를 만들 때 계약 문서와 대조하고, 다르면 구현을 바꾸지 말고 알린다. 계약은 `docs/shared/README.md`의 흐름으로만 바꾼다.

## 명령
- 빌드·테스트: `cd backend && ./gradlew build` (포맷·Checkstyle·계층 검사 포함, 통합 테스트는 Docker 필요)
- 포맷: `cd backend && ./gradlew spotlessApply`. 손으로 정렬하지 않는다.
- 계층 의존 규칙은 `common` 테스트 모듈의 `LayeredArchitectureRules`가 검사한다. 규칙을 어기는 코드는 고치고, 규칙이 틀렸다고 판단되면 규칙 변경을 먼저 제안한다.
- 모듈 하나: `cd backend && ./gradlew :user-service:test`
- 로컬 기동: `script/run.sh [서비스...]`, DB 초기화는 `script/run.sh --reset`
- Gradle toolchain이 JDK 17로 고정한다. JAVA_HOME을 바꾸지 않는다.

## 코드 규칙
- 패키지는 `com.sparta.ticket.{서비스}`, 내부는 `presentation / application / domain / infrastructure`
- Entity는 `BaseEntity`를 상속하고, 논리 삭제 조회 제외는 Entity마다 `@SQLRestriction("deleted_at IS NULL")`을 붙인다.
- 스키마 DDL은 `backend/sql/{서비스}/schema.sql`에 둔다. Entity를 바꾸면 이 파일도 같이 고친다. `local` 프로필(기본)은 `ddl-auto: create`, 그 밖은 `validate`이고, 통합 테스트는 `schema.sql`을 실행한 뒤 Entity와 대조한다.
- 에러 코드는 서비스별 enum이 `ErrorCode`를 구현한다. `CommonErrorCode`에 있는 이름을 다시 정의하지 않는다.
- 외부 API는 `/api/**`(버전 `v1`을 붙이지 않는다), 응답은 `ApiResponse`. 새 경로는 `backend/config-repo/gateway-service.yml` 라우트를 확인한다.
- 서비스 간 API는 `/internal/**`에 두고 응답을 감싸지 않는다. 다른 서비스 스키마는 직접 조회하지 않고 Feign(`infrastructure/client`)으로 부른다. 재시도(`IdempotentRetryConfig`)는 GET·멱등 API에만 붙인다.
- 현재 사용자는 `@CurrentUser UserContext`, 역할 제한은 `@RequireRole`, 소유자 검사는 application 계층에서 한다.
- 통합 테스트는 `PostgresIntegrationTest`(Testcontainers PostgreSQL 16)를 상속한다. H2를 쓰지 않는다.

## 작업 원칙
- 주석·문서·커밋은 한국어 평서체로 쓴다. 주석은 학습 가치가 큰 곳에만 `Note: `로 단다.
- `.env`, 토큰, 실제 비밀번호는 커밋하지 않는다.
- 완료 보고는 실행한 빌드·테스트 결과를 근거로 한다. 돌리지 못한 테스트는 그 사실과 이유를 적는다.
- 개인 지침은 `CLAUDE.local.md`(gitignore 대상)에 둔다.

## 어디를 볼지
| 도메인 | 모듈 | 문서 | 담당 |
|---|---|---|---|
| 회원 | `user-service` | `docs/user/` | 김태현 |
| 공연 | `performance-service` | `docs/performance/` | 박의연 |
| 좌석 | `reservation-service` seat | `docs/seat/` | 변정민 |
| 예매 | `reservation-service` booking | `docs/reservation/` | 지한얼 |
| 대기열 | `reservation-service` queue | `docs/queue/` | 김태현 |
| 결제 | `payment-service` payment, `fake-pg` | `docs/payment/` | 강현민 |
| 쿠폰 | `reservation-service` coupon | `docs/coupon/` | 성윤범 |

- 서비스·모듈 간 계약: `docs/shared/` (계약 변경 흐름은 `docs/shared/README.md`)
- 전체 그림: 서비스 책임·통신·예매 흐름은 `docs/project/architecture.md`, 서버 배치·포트·배포는 `docs/project/infra.md`. 계약·도메인 문서와 다르면 계약·도메인 문서가 기준이다
- 경로별 규칙: `.claude/rules/` (도메인 문서 먼저 읽기, 계약, API, 도메인·영속성)
- 스킬: `/add-service` (서비스 추가 절차). `/add-internal-api`, `/load-test`는 아직 틀이다
- 동시성·트랜잭션 리뷰: `concurrency-reviewer` 에이전트
