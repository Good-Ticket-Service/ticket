---
name: add-service
description: 이 저장소에 새 업무 서비스를 추가할 때 기존 Claude 절차로 Gradle, 설정, DB, Compose, Gateway와 문서를 연결한다.
---

# 서비스 추가

저장소 루트의 `AGENTS.md`와 `.claude/skills/add-service/SKILL.md`를 읽고 원본 절차 전체를 따른다. 경로는 현재 셸 위치가 아니라 저장소 루트 기준이다.

원본의 Claude `/add-service` 호출은 Codex에서 `$add-service`에 대응한다. 서비스 이름·포트가 없으면 먼저 확인한다. 실제 `.env`를 읽거나 고치지 않고 DB 초기화와 커밋은 실행하지 않는다. Windows 명령은 AGENTS.md의 실행 규칙에 맞춘다.
