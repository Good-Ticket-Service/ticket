-- #317 실행 관리. 금융 원천은 바꾸지 않고 DB 완료 마커와 검사 이력을 같은 트랜잭션에 남긴다.
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS batch_job_instance_id BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS mode VARCHAR(10);
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS db_valid BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS technical_failure BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS pending_excluded BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS db_duration_ms BIGINT;
ALTER TABLE p_settlement_reconcile_runs ADD COLUMN IF NOT EXISTS http_duration_ms BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_reconcile_db_accepted
    ON p_settlement_reconcile_runs(batch_job_instance_id) WHERE db_valid;
CREATE INDEX IF NOT EXISTS ix_reconcile_batch_instance ON p_settlement_reconcile_runs(batch_job_instance_id,id DESC);
