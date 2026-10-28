-- 모든 payment 실행 역할을 중지한 후 적용한다. 기존 금융 기록과 취소 세대는 유지한다.
BEGIN;
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS execution_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_p_refunds_execution_version;
ALTER TABLE p_refunds ADD CONSTRAINT ck_p_refunds_execution_version CHECK (execution_version >= 0);
COMMIT;
