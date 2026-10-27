# Codex 연결 안내

설정·연결을 점검할 때만 읽는다. 일반 개발의 필수 읽기 목록이 아니다.

| 구성 | 연결 |
|---|---|
| 공통 진입 | AGENTS.md → CLAUDE.md → docs/conventions.md의 작업별 선택 표 |
| 경로 안내 | .claude/rules/docs-first.md는 Claude용 문서 안내. Codex는 같은 선택 표를 사용하며 규칙 파일을 일괄 읽지 않음 |
| 스킬 | .agents/skills/{이름}/SKILL.md → .claude/skills/{이름}/SKILL.md 원본 |
| 동시성 리뷰 | .codex/agents/concurrency-reviewer.toml → .claude/agents/concurrency-reviewer.md 원본 |

새 세션에서 스킬·역할 발견 여부를 확인한다. 기존 세션에는 변경된 진입점을 다시 읽도록 요청할 수 있지만 파일 형식 확인만으로 자동 발견을 검증했다고 하지 않는다. 역할을 지원하지 않으면 원본 지침에 따라 직접 읽기 전용 리뷰한다.
공통 규칙은 conventions만, 작업 절차·리뷰 기준은 해당 원본만 수정하고 연결을 확인한다. 개인 설정은 사용자 홈이나 로컬 파일에 둔다. 실제 실행 권한은 각 도구의 설정과 세션 권한을 따르며 도구 간 자동 이식으로 간주하지 않는다.
