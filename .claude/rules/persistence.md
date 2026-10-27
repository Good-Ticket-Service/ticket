---
paths:
  - "backend/**/domain/**"
  - "backend/**/infrastructure/persistence/**"
  - "backend/sql/**"
---

# 도메인·영속성 규칙

- Entity는 `BaseEntity`를 상속하고, 논리 삭제 조회 제외는 Entity마다 `@SQLRestriction("deleted_at IS NULL")`을 붙인다.
- Entity를 바꾸면 `backend/sql/{서비스}/schema.sql`과 `docs/tables.md`를 같은 PR에서 고친다. `local` 프로필은 `ddl-auto: create`라 로컬에서는 차이가 드러나지 않고, 통합 테스트가 `schema.sql`과 Entity를 대조할 때 드러난다.
- 다른 서비스의 스키마를 직접 조회하지 않는다. 다른 서비스의 ID는 FK 없이 값만 둔다.
- 상태 전이는 Entity 메서드로 하고, 허용하지 않는 전이는 예외로 막는다.
- 동시에 같은 행을 바꿀 수 있는 곳은 잠금(비관적 락, 조건부 UPDATE, 낙관적 락) 방식을 정하고 이유를 남긴다.
- 통합 테스트는 `PostgresIntegrationTest`(Testcontainers PostgreSQL 16)를 상속한다. H2를 쓰지 않는다.
