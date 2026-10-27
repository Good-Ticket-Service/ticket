# Codex 개발 환경

Claude와 Codex는 같은 저장소 문서를 기준으로 개발한다. Codex는 루트 `AGENTS.md`를 진입점으로 사용하고 기존 원본을 읽는다. 별도 복사본으로 업무 규칙을 관리하지 않는다.

| 기존 구성 | Codex 적용 |
|---|---|
| PR #16: `docs/`, 도메인 명세, `docs/shared/` 계약 | AGENTS.md에서 문서 탐색·선행 읽기·계약 변경 흐름을 지시한다 |
| PR #17: GitHub 이슈·PR 양식, CODEOWNERS, CI | 기존 파일을 함께 사용한다. GitHub 설정을 복제하지 않는다 |
| PR #18: `CLAUDE.md` | AGENTS.md에서 공통 지침 원본으로 읽는다 |
| `.claude/rules/*.md` | 규칙을 읽고 paths에 해당하는 작업에 적용한다. Claude 경로 매칭 엔진 자체를 실행하는 것은 아니다 |
| `.claude/skills/` | `.agents/skills/`의 Codex 진입점에서 원본을 읽는다. 명시 호출 정책을 유지한다 |
| `.claude/agents/concurrency-reviewer.md` | `.codex/agents/concurrency-reviewer.toml`에서 원본을 읽는다 |
| `.claude/settings*.json` | Codex 설정으로 자동 변환되지 않는다. 저장소 작업 제한은 AGENTS.md에 명시하고 실제 권한은 Codex 세션 설정을 따른다 |

## 사용

이 저장소에서 새 Codex 세션을 시작한다. 기존 세션에서는 `AGENTS.md를 읽고 적용해줘`라고 요청할 수 있지만 스킬 목록과 역할 등록 갱신은 새 세션에서 확인한다.

- 서비스 추가: `$add-service`와 서비스 이름·포트를 입력한다.
- 내부 API: `$add-internal-api`와 합의된 계약 경로를 입력한다. 현재는 절차의 틀이다.
- 부하 테스트: `$load-test`와 측정 대상·시나리오를 입력한다. 현재는 절차의 틀이다.
- 리뷰: `concurrency-reviewer로 현재 변경을 읽기 전용 리뷰해줘`라고 요청한다. 사용자 지정 역할을 지원하지 않는 환경에서는 원본 리뷰 지침으로 직접 리뷰한다.

확인 시 Codex에 먼저 읽은 문서 경로와 적용할 경로 규칙을 설명하도록 요청한다. 스킬 선택 목록에 세 스킬이 나타나는지 확인한다. 파일 형식 검증만으로 실제 세션의 자동 발견·권한 적용까지 검증됐다고 판단하지 않는다.

## 권한 차이

Claude의 allow/ask/deny는 Codex로 그대로 이식할 수 없다. 특히 AGENTS.md의 비밀 파일 읽기 금지와 리뷰 역할의 읽기 전용 지시는 행동 지침이며 파일 접근을 차단하는 보안 장벽이 아니다. 강제 차단은 실행 환경의 파일 접근 정책과 샌드박스로 설정한다. 이 저장소 설정은 사용자 전역 승인·네트워크·모델 설정을 변경하지 않는다.

기존 `CLAUDE.local.md`, `.claude/settings.local.json`은 개인 설정이다. Codex 전용 개인 설정은 사용자 홈의 Codex 설정에서 관리하고 저장소에 비밀값을 추가하지 않는다.

## 유지보수

공통 개발 규칙은 `CLAUDE.md`, 경로 규칙은 `.claude/rules/`, 작업 절차와 리뷰 기준은 기존 `.claude/skills/`와 `.claude/agents/` 원본을 고친다. 원본을 이동·삭제하거나 새 스킬·역할을 추가하면 Codex 진입점도 확인한다. 새 경로 규칙은 AGENTS.md의 전체 규칙 읽기 지침으로 포함된다.

공식 문서: [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md), [스킬](https://learn.chatgpt.com/docs/build-skills), [사용자 지정 에이전트](https://learn.chatgpt.com/docs/agent-configuration/subagents).
