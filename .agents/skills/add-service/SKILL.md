---
name: add-service
description: 이 저장소에 새 업무 서비스를 추가할 때 기존 Claude 절차로 Gradle, 설정, DB, Compose, Gateway와 문서를 연결한다.
---

# 서비스 추가

저장소 루트의 `AGENTS.md`와 `.claude/skills/add-service/SKILL.md`를 읽고 원본 절차 전체를 따른다. 경로는 현재 셸 위치가 아니라 저장소 루트 기준이다.

원본의 Claude `/add-service` 호출은 Codex에서 `$add-service`에 대응한다. 서비스 이름·포트가 없으면 먼저 확인한다. 실행·안전 규칙은 `docs/conventions.md`를 따르며, 이 스킬 호출만으로 DB 초기화·커밋을 승인받은 것으로 보지 않는다.
