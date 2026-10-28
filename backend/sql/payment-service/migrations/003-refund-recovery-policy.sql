-- 모든 payment 실행 역할을 중지한 뒤 적용한다. 002 적용 후 실행하며 운영 DB를 자동 변경하지 않는다.
BEGIN;
LOCK TABLE p_refunds IN ACCESS EXCLUSIVE MODE;
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS recovery_mode VARCHAR(20);
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS verification_reason VARCHAR(80);
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS slow_started_at TIMESTAMP;
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS slow_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS cancel_key_created_at TIMESTAMP;
-- 이미 새 정책이 있는 행은 재실행 시 수정하지 않는다.
UPDATE p_refunds SET
    recovery_mode = CASE
        WHEN status IN ('DONE','FAILED') THEN 'PAUSED'
        WHEN recovery_blocked AND status = 'REQUESTED' AND last_error = 'REFUND_RECOVERY_DEADLINE' THEN 'SLOW'
        WHEN recovery_blocked THEN 'PAUSED'
        ELSE 'ACTIVE' END,
    cancel_key_created_at = LEAST(requested_at, recovery_started_at)
WHERE recovery_mode IS NULL;
-- 시간 초과로 중단한 건만 조회를 재개한다. 알 수 없는 중단 사유·업무 거절은 해제하지 않는다.
UPDATE p_refunds SET recovery_blocked = FALSE,
    slow_started_at = COALESCE(slow_started_at, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'),
    next_attempt_at = COALESCE(next_attempt_at, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
WHERE status = 'REQUESTED' AND recovery_mode = 'SLOW' AND recovery_blocked
    AND last_error = 'REFUND_RECOVERY_DEADLINE';
-- 키 생성 근거가 없으면 자동 쓰기를 허용하지 않는다.
UPDATE p_refunds SET recovery_mode = 'VERIFY_ONLY', verification_reason = 'PG_CANCEL_KEY_TIME_UNKNOWN'
WHERE status = 'REQUESTED' AND recovery_mode IN ('ACTIVE','SLOW') AND cancel_key_created_at IS NULL;
ALTER TABLE p_refunds ALTER COLUMN recovery_mode SET DEFAULT 'ACTIVE';
ALTER TABLE p_refunds ALTER COLUMN recovery_mode SET NOT NULL;
ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_p_refunds_recovery_mode;
ALTER TABLE p_refunds ADD CONSTRAINT ck_p_refunds_recovery_mode CHECK (recovery_mode IN ('ACTIVE','SLOW','VERIFY_ONLY','PAUSED'));
ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_p_refunds_slow_attempts;
ALTER TABLE p_refunds ADD CONSTRAINT ck_p_refunds_slow_attempts CHECK (slow_attempts >= 0);
ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_p_refunds_blocked_mode;
ALTER TABLE p_refunds ADD CONSTRAINT ck_p_refunds_blocked_mode CHECK (NOT recovery_blocked OR recovery_mode = 'PAUSED');
ALTER TABLE p_refunds DROP CONSTRAINT IF EXISTS ck_p_refunds_failed_mode;
ALTER TABLE p_refunds ADD CONSTRAINT ck_p_refunds_failed_mode CHECK (status <> 'FAILED' OR recovery_mode IN ('VERIFY_ONLY','PAUSED'));
DROP INDEX IF EXISTS ix_p_refunds_requested_next_attempt;
CREATE INDEX ix_p_refunds_requested_next_attempt ON p_refunds(next_attempt_at)
    WHERE status IN ('REQUESTED','FAILED') AND recovery_mode <> 'PAUSED' AND recovery_blocked = FALSE;
COMMIT;
