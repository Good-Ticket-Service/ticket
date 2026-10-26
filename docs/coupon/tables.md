# 쿠폰 테이블 명세

- 상태: 작성 전
- 담당:
- DDL: `backend/sql/payment-service/schema.sql`

## 1. ERD

```mermaid
erDiagram
    {table_a} ||--o{ {table_b} : "관계 설명"

    {table_a} {
        bigint id PK
    }
```

- 다른 서비스의 ID는 FK 없이 값만 둔다.

## 2. 공통 사항

- 소유 서비스와 책임 경계
- DB·타입 규칙 (식별자, 시각, 금액)
- 감사 컬럼

## 3. {table} — {한글 이름}

{이 테이블이 무엇을 담는지, 상태 전이}

| 컬럼(영문) | 컬럼(한글) | 타입 | 제약·기본값 | 설명 |
| --- | --- | --- | --- | --- |
| id | 식별자 | BIGINT | PK, NOT NULL | |

**키·인덱스**

UK(...): 이유.
INDEX(...): 쓰는 조회.

**상태와 의미**

| 상태 | 의미 |
| --- | --- |
| | |
