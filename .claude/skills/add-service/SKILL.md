---
name: add-service
description: 이 저장소에 새 업무 서비스 모듈을 추가한다. Gradle 모듈, Config Server 설정, DB 계정·스키마, Compose, Gateway 라우트, 문서까지 한 번에 연결한다.
disable-model-invocation: true
---

# 서비스 추가

인자로 서비스 이름(`{name}`, 예: `ticket`)과 포트(예: `8085`)를 받는다. 없으면 먼저 묻는다. 모듈 이름은 `{name}-service`, 패키지는 `com.sparta.ticket.{name}`, DB 계정은 `{name}_app`, 스키마는 `{name}_schema`, 비밀번호 변수는 `{NAME}_DB_PASSWORD`다.

기존 서비스(`payment-service`)를 기준으로 복사하되, 업무 코드는 복사하지 않는다. 각 단계에서 기존 서비스가 그 파일에 어떻게 들어가 있는지 먼저 읽고 같은 모양으로 추가한다.

## 1. Gradle 모듈

- `backend/settings.gradle`에 `include '{name}-service'`를 추가한다.
- `backend/{name}-service/build.gradle`: `backend/payment-service/build.gradle`과 같은 의존성으로 만든다.
- `backend/{name}-service/src/main/resources/application.yml`: `spring.application.name`, `profiles.default: local`, `spring.config.import`, `cloud.config.fail-fast`만 둔다. 다른 설정은 넣지 않는다.
- 메인 클래스 `com.sparta.ticket.{name}.{Name}ServiceApplication`과 빈 계층 패키지(`presentation`, `application`, `domain`, `infrastructure`)를 만든다.
- 테스트에 `ArchitectureTest`를 둔다. `backend/user-service/src/test/java/com/sparta/ticket/user/ArchitectureTest.java`와 같은 모양이다.

## 2. 설정 (Config Server)

- `backend/config-repo/{name}-service.yml`: 포트, datasource(`currentSchema={name}_schema`, 계정 `{name}_app`, 비밀번호 `${NAME_DB_PASSWORD:{name}_local_pw}`), `hibernate.default_schema`.

## 3. DB와 환경 변수

- `infra/postgres/init.sh`: 필수 변수 검사 한 줄과 `create_service_schema {name}_schema {name}_app "${NAME_DB_PASSWORD}"`를 추가한다.
- `docker-compose.yml`
  - `postgres.environment`에 `{NAME}_DB_PASSWORD: ${NAME_DB_PASSWORD:?...}`
  - 새 서비스 블록: `<<: *db-service`, `build.args.MODULE`, `environment`에 `<<: *service-env`와 `{NAME}_DB_PASSWORD`
- `.env.example`에 `{NAME}_DB_PASSWORD={name}_local_pw`를 추가한다. 실제 `.env`는 읽거나 고치지 않는다.
- `backend/sql/{name}-service/schema.sql`: 비어 있는 DDL 파일과 상단 주석.
- init.sh는 빈 볼륨을 처음 띄울 때만 실행된다. 기존 로컬 DB에는 `script/run.sh --reset`이 필요하다고 사용자에게 알린다. `--reset`을 직접 실행하지 않는다.

## 4. Gateway·모니터링

- `backend/config-repo/gateway-service.yml`
  - 외부 경로 라우트(`/api/v1/{경로}/**` → `lb://{name}-service`). 경로는 사용자에게 확인한다.
  - OpenAPI 문서 라우트(`/{name}-service/v3/api-docs`)와 Swagger `urls` 항목
- `script/smoke.sh`의 `SERVICES` 배열에 추가한다.
- Prometheus는 Eureka 등록 목록에서 찾으므로 고치지 않는다.

## 5. 문서

- `README.md` "모듈과 포트" 표, `CLAUDE.md` "구조"의 모듈 목록에 추가한다.
- `docs/architecture.md` 책임 표와 `docs/api.md`, `docs/tables.md`, `docs/infra.md`의 해당 절을 갱신한다. docs 파일/폴더를 추가하지 않는다.
- `.claude/rules/docs-first.md`의 서비스·문서 표에 추가한다.
- `.github/CODEOWNERS`에 담당이 정해졌으면 추가한다.

## 6. 확인

- `cd backend && ./gradlew :{name}-service:build`가 통과한다.
- `docker compose config`로 Compose 문법을 확인한다.
- 바꾼 파일 목록과, 사용자가 해야 할 일(`.env`에 비밀번호 추가, `script/run.sh --reset`)을 보고한다. 커밋은 하지 않는다.
