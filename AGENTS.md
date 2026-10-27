# Codex 프로젝트 지침

## 먼저 읽을 문서

1. 루트 `CLAUDE.md`를 읽고 프로젝트 공통 지침으로 따른다. 구조·명령·코드 규칙·도메인 담당 표의 원본은 이 파일이다.
2. `docs/conventions.md`를 진입점으로 읽고, `docs/api.md`, `docs/tables.md`, `docs/architecture.md`, `docs/infra.md`에서 작업 도메인의 절을 읽는다. docs에는 이 5개 파일만 유지한다.
3. `.claude/rules/`의 모든 Markdown 파일을 읽는다. 각 파일의 YAML `paths`에 해당하는 파일을 다룰 때 그 규칙을 적용한다. Codex가 Claude의 경로 규칙을 자동 해석한다고 가정하지 않는다. 새 규칙도 같은 방식으로 읽는다.
4. 서비스·모듈 간 계약은 `docs/api.md`, 계약 변경 절차는 `docs/conventions.md`를 따른다. 초안과 합의를 구분하고 구현과 계약이 다르면 먼저 차이를 알린다.
5. `HANDOFF.md`가 있으면 세션 진행 기록으로 읽되 현재 브랜치·diff와 대조한다. 오래된 진행 기록을 최신 계약이나 사용자 지시보다 우선하지 않는다. HANDOFF는 커밋하지 않는다.

문서의 내용을 이 파일에 복제하지 않는다. 공통 규칙을 고칠 때는 원본을 고치고 Codex 연결 경로가 유효한지 확인한다. 개인용 `CLAUDE.local.md`가 있으면 읽되 커밋하지 않는다.

`docs-deprecated/`은 담당자 검토용 보관본이며 기본 읽기·검색 대상에서 제외한다. `backend/payment-service/docs-local/`은 개인 로컬 문서로 커밋·업로드하지 않는다.

## GitHub 작업

- 이슈를 작성할 때 `.github/ISSUE_TEMPLATE/`에서 작업 종류에 맞는 양식을 읽고 따른다.
- PR을 준비할 때 `.github/pull_request_template.md`, `.github/CODEOWNERS`, `.github/workflows/ci.yml`을 읽는다. 일반 작업 대상은 `develop`, 릴리스는 `main`이다.
- 문서·계약의 상태, 변경 이력과 영향받는 담당자를 확인한다. CODEOWNERS의 주석 항목을 실제 등록된 담당자로 보고하지 않는다.
- 실행한 검증만 완료로 보고하고, 실행하지 못한 검증은 이유를 적는다.
- 커밋·푸시·PR 생성은 사용자가 명시적으로 요청할 때만 한다. AI 공동 작성자·기여 표시를 임의로 추가하지 않는다.
- 기존 워킹트리의 변경을 보존한다. 요청받지 않은 변경을 되돌리거나 함께 커밋하지 않는다.

## 도구 권한과 실행

`.claude/settings.json`과 `.claude/settings.local.json`은 Claude 전용 설정이며 Codex의 실행 권한을 바꾸지 않는다. Codex의 현재 샌드박스·승인 정책을 따른다. 다음은 이 저장소의 작업 지침이다.

- 실제 `.env`, `.env.local`, 그 밖의 비밀 환경 파일을 읽거나 수정하지 않는다. 예시는 `.env.example`을 사용한다. 토큰·비밀번호·키를 출력하거나 커밋하지 않는다.
- `git reset --hard`, `git clean`, 파일·디렉터리 재귀 삭제, `docker compose down`, Docker 볼륨 삭제, `script/run.sh --reset`은 대상과 영향을 설명하고 사용자가 해당 작업을 명시적으로 승인한 경우에만 실행한다.
- 빌드·포맷·상태 조회 등은 요청 범위에서 진행하되, 기존 도구 권한을 우회하지 않는다. JAVA_HOME은 변경하지 않는다.
- Gradle 루트는 `backend/`다. Windows PowerShell에서는 `./gradlew.bat`을 사용하고, PowerShell 5.1에서 `&&`를 쓰지 않는다. 여러 명령은 각 종료 코드를 확인한다.

## 스킬과 리뷰

- Codex 스킬 진입점은 `.agents/skills/`에 있다. `$add-service`, `$add-internal-api`, `$load-test`로 호출한다. 각 진입점이 지정하는 `.claude/skills/{이름}/SKILL.md` 원본을 먼저 읽고 따른다.
- 기존 Claude 스킬의 `disable-model-invocation: true`와 같은 의도로 세 스킬은 명시적으로 호출할 때만 사용한다. 내부 API·부하 테스트 스킬은 아직 절차의 틀이며 완성된 자동화로 취급하지 않는다.
- 동시성·트랜잭션 리뷰를 요청받으면 `.claude/agents/concurrency-reviewer.md`를 읽고 그 범위와 보고 형식을 따른다. Codex용 역할은 `.codex/agents/concurrency-reviewer.toml`이다. 역할 기능을 사용할 수 없으면 같은 지침으로 직접 읽기 전용 리뷰를 한다.
- 리뷰 에이전트 지침은 코드 수정 권한을 주지 않는다. 별도 에이전트 실행은 사용자 요청이나 적용되는 세션 지침이 허용할 때만 한다.

설정 대응 관계와 확인 방법은 `.codex/README.md`에 있다.
