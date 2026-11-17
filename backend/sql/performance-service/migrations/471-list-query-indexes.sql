-- 이슈 #471: 공개 공연 목록 조회 인덱스(후보). 기존 DB에 수동으로 적용한다. 새 DB는 schema.sql로 만들어진다.
-- CONCURRENTLY는 트랜잭션 블록 안에서 실행할 수 없다. BEGIN 없이 psql로 문장마다 실행한다.
-- 예: psql -v ON_ERROR_STOP=1 -d <대상 DB> -f 471-list-query-indexes.sql
-- 실패하면 INVALID 인덱스가 남을 수 있다. IF NOT EXISTS가 그 인덱스를 건너뛰므로,
-- SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid; 로 확인하고 DROP INDEX CONCURRENTLY 뒤 다시 실행한다.
-- 되돌리기: DROP INDEX CONCURRENTLY IF EXISTS performance_schema.<인덱스 이름>;
SET search_path TO performance_schema;

-- 공개 목록 최신순
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_p_performances_public_latest
    ON p_performances (published_at DESC, id DESC)
    WHERE deleted_at IS NULL AND publication_status = 'PUBLISHED' AND operation_status = 'ACTIVE';
