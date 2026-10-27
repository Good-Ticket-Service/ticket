---
paths:
  - "backend/*-service/**"
  - "backend/sql/**"
---

# 공통 문서 먼저 읽기

- `docs/conventions.md`를 진입점으로 읽는다. `docs/api.md`, `docs/tables.md`, `docs/architecture.md`, `docs/infra.md`의 관련 도메인 절만 읽는다.
- docs에는 이 5개 파일만 둔다. 작업 목록·측정 결과는 이슈/PR에 기록한다.
- 구현이 계약과 다르면 먼저 차이를 알린다. 초안을 합의된 계약으로 취급하지 않는다.
- 엔티티·DDL 변경 시 tables를, 외부/내부/모듈 계약 변경 시 api를 같은 PR에서 갱신한다.
