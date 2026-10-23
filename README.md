# Ticket

공연 티켓 예매 MSA 프로젝트. 플랫폼 구성(Config·Eureka·Gateway·공통 모듈·Compose)은 logistics-management를 바탕으로 했다.

## 기술 스택

- Java 17, Spring Boot 3.5.16, Spring Cloud 2025.0.x, Gradle 8.14 멀티 모듈
- PostgreSQL 16(서비스별 스키마), Redis 7.4, QueryDSL(OpenFeign 포크), springdoc
- 인증: user-service가 HS256 JWT 발급, Gateway가 검증 후 `X-User-*` 헤더 주입

## 폴더 구조

```
ticket/
├── backend/            # Gradle 루트: 모듈, gradlew, Dockerfile, config-repo, sql(서비스별 DDL)
├── infra/              # Compose가 쓰는 실행 환경(PostgreSQL 초기화 등)
├── script/run.sh       # 로컬 전체 기동
├── docker-compose.yml  # 전체 시스템 정의(빌드 컨텍스트는 backend/)
└── .env.example
```

프런트를 추가하면 `frontend/`를 같은 높이에 둔다. Gradle 빌드와 섞지 않는다.

## 모듈과 포트

모두 `backend/` 아래에 있다.

| 모듈 | 포트 | 역할 |
|---|---|---|
| `config-server` | 8888 | `backend/config-repo/`의 설정을 서비스에 내려준다 |
| `eureka-server` | 8761 | 서비스 등록·조회 |
| `gateway-service` | 8080 | 외부 진입점. JWT 검증, 라우팅, Swagger UI 통합 |
| `user-service` | 8081 | 회원가입·로그인(JWT 발급), 내 정보 |
| `event-service` | 8082 | 공연·회차·좌석 |
| `reservation-service` | 8083 | 좌석 선점·예매 (Redis 사용) |
| `payment-service` | 8084 | 결제 |
| `common-core` | - | 웹 스택 무관 공통 계약: 에러 코드·응답 형식, 사용자 헤더 이름 |
| `common` | - | MVC 서비스 공통: 예외 처리, `@CurrentUser`, `@RequireRole`, BaseEntity(Audit), Feign 헤더 전달·에러 변환, 페이징 |

event·reservation·payment는 뼈대만 있다. 도메인은 각 담당자가 채운다.

## 구조 규칙

- 패키지: `com.sparta.ticket.{서비스}`, 내부는 `presentation / application / domain / infrastructure`
- 설정: 서비스 설정은 `backend/config-repo/{서비스}.yml`, 공통은 `backend/config-repo/application.yml`에 둔다. 모듈의 `application.yml`에는 이름과 `spring.config.import`만 둔다.
- DB: 서비스마다 계정과 스키마가 따로 있다(`infra/postgres/init.sh`). 다른 서비스 테이블을 직접 조회하지 않고 API(Feign)로 받는다.
- 스키마: 서비스별 DDL은 `backend/sql/{서비스}/schema.sql`에 둔다. 프로필별 처리는 아래 "스키마 관리"를 본다.
- 서비스 간 API: `/internal/**` 경로에 두고 응답을 `ApiResponse`로 감싸지 않는다. Gateway가 외부 접근을 404로 막는다.
- 응답: 성공은 `ApiResponse`(`status`, `message`, `data`), 실패는 `ErrorResponse`(`status`, `code`, `message`, `path`, `timestamp`, `errors`).
- 서비스 에러 코드는 `ErrorCode`를 구현한 enum으로 둔다(예: `UserErrorCode`). 공통 코드는 `CommonErrorCode`.

## 스키마 관리

| 프로필 | `ddl-auto` | 테이블을 만드는 것 |
|---|---|---|
| `local`(기본) | `create` | Hibernate가 기동할 때마다 Entity 기준으로 지우고 다시 만든다. 재시작하면 데이터가 사라진다 |
| 그 밖(dev 등) | `validate` | `backend/sql/{서비스}/schema.sql`을 서비스 계정으로 DB에 직접 적용한다. Hibernate는 검증만 한다 |
| 통합 테스트 | `validate` | `PostgresIntegrationTest`가 `schema.sql`을 실행한 뒤 Entity와 대조한다 |

- 프로필을 지정하지 않으면 `local`로 뜬다(각 모듈 `application.yml`의 `spring.profiles.default`). 바꾸려면 `SPRING_PROFILES_ACTIVE`를 준다.
- `local` 전용 값은 `backend/config-repo/application-local.yml`에 있다.
- Entity를 바꾸면 `schema.sql`도 같이 고친다. 어긋나면 통합 테스트가 실패한다.
- `schema.sql`은 현재 스키마 전체를 담는다. 이미 테이블이 있는 dev DB에는 바뀐 부분만 `ALTER`로 직접 적용한다.

## 인증 흐름

1. `POST /api/v1/auth/login` → user-service가 JWT 발급(`sub`=userId, `username`, `role`)
2. Gateway가 서명·만료·필수 claim 검증
3. Gateway가 user-service 내부 API(`/internal/users/{id}`, 60초 캐시)로 저장된 role·탈퇴 여부를 다시 확인
4. 클라이언트가 보낸 `X-User-*` 헤더를 지우고 검증된 값(`X-User-Id`, `X-Username`, `X-User-Role`)을 넣어 전달
5. 서비스는 컨트롤러 파라미터 `@CurrentUser UserContext user`로 받고, 역할 제한은 `@RequireRole(Role.MASTER)`로 건다

## 실행

```bash
cp .env.example .env          # script/run.sh는 .env가 없으면 만든다
script/run.sh                 # 전체 빌드·기동
script/run.sh user-service    # 지정한 서비스와 의존 서비스만
script/run.sh --reset         # DB 볼륨까지 지우고 새로 시작
```

- Swagger: http://localhost:8080/swagger-ui.html
- Eureka: http://localhost:8761
- 초기 MASTER 계정: `.env`의 `MASTER_USERNAME` / `MASTER_PASSWORD`
- logistics-management와 함께 띄우면 호스트 포트가 겹친다. `.env`에서 포트와 `COMPOSE_PROJECT_NAME`을 바꾼다. Docker 네트워크 대역(10.220.10.0/24)은 logistics(10.210.10.0/24)와 겹치지 않게 잡았다.

IDE로 서비스 하나만 띄울 때는 `docker compose up -d postgres redis config-server eureka-server`로 기반만 올리고, 실행 설정에 `JWT_SECRET`(user-service)을 넣는다. 서비스보다 config-server를 먼저 띄운다.

## 빌드·테스트

```bash
cd backend
./gradlew build                    # 전체
./gradlew :user-service:test       # 모듈 하나
```

- 통합 테스트(`PostgresIntegrationTest`를 상속한 테스트)는 Testcontainers로 PostgreSQL 16을 띄우므로 Docker가 실행 중이어야 한다.
- 셸 기본 JDK가 17이 아니어도 Gradle toolchain이 17로 컴파일·실행한다.

## 새 업무 기능을 추가할 때

1. Entity를 만들고 같은 테이블을 `backend/sql/{서비스}/schema.sql`에 DDL로 적는다. Audit 컬럼 6개(`created_at`, `created_by`, `updated_at`, `updated_by`, `deleted_at`, `deleted_by`)를 넣고 Entity는 `BaseEntity`를 상속한다.
2. 외부 API는 `/api/v1/...`로 만들고, Gateway 라우트(`backend/config-repo/gateway-service.yml`)에 경로가 있는지 확인한다.
3. 다른 서비스 호출은 `infrastructure/client`에 `@FeignClient`로 둔다. GET·멱등 API만 `IdempotentRetryConfig`를 붙인다.
# ticket
