---
paths:
  - "backend/**/presentation/**/internal/**"
  - "backend/**/*Internal*Controller*.java"
  - "backend/**/infrastructure/client/**"
  - "backend/**/application/port/**"
  - "docs/api.md"
  - "docs/architecture.md"
---

# 서비스·모듈 간 계약

`/internal/**` API, Feign 클라이언트, 다른 모듈이 쓰는 포트 인터페이스, Kafka 메시지는 `docs/api.md`의 계약을 따른다.

- 구현 전에 `docs/api.md`의 계약 절에서 해당 계약을 찾아 읽는다. 공통 규약은 `docs/conventions.md`다.
- 경로·필드 이름·타입·오류 코드·멱등 기준이 계약과 다르면 구현을 바꾸지 말고 차이를 알린다.
- 계약에 없는 필드·API·메시지가 필요하면 코드를 먼저 고치지 않는다. `docs/conventions.md`의 계약 변경 흐름(계약 이슈 → 합의 → 계약 PR)을 안내한다.
- 계약 상태가 `초안`이면 쓰는 쪽은 그 내용에 기대지 않는다. 가짜 구현으로 테스트하고 합의를 기다린다.
- Feign 재시도(`IdempotentRetryConfig`)는 계약에 멱등하다고 적힌 API에만 붙인다.
- 계약 변경 시 합의 상태·이슈 근거를 해당 절 또는 PR에 남기고, 미합의 표는 `docs/architecture.md` 한 곳에서 갱신한다.
