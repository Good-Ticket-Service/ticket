-- 기존 DB 전용. 새 DB는 schema.sql만 적용한다. 기본 목록은 1,5,15,60,1440분이다.
-- Payment API/모든 잡 중지 → 백업 → 이 스크립트 → 새 버전만 기동(롤링 혼용 금지).
-- 최초 실패 시각이 없던 옛 행은 approve_requested_at(없으면 updated_at)을 보수적 기준으로 쓴다.
-- 옛 reconciled_at 한 번은 새 5단계 완료로 간주하지 않고 다시 확인한다.
-- 옛 2단계 완료(count=2,next=NULL)만 새 일정 완료로 매핑한다. 진행 중인 옛 단계는
-- count 숫자를 새 단계로 해석하지 않고 첫 단계부터 다시 조회한다(환불은 업무 멱등성으로 보호).
-- 새 일정 스냅샷이 있는 행은 재실행으로 초기화하지 않는다. 기존 일정 변경도 스냅샷별로 적용한다.
-- 기존 Unknown 횟수/시각이 없으면 복원했다고 주장하지 않는다. 첫 새 Unknown부터 기록한다.
-- 실패하면 트랜잭션 전체 롤백. 이미 배포된 다른 목록으로 이행하려면 매핑을 별도 검토한다.
BEGIN;
LOCK TABLE p_payments IN ACCESS EXCLUSIVE MODE;
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP;
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS reconcile_count INT NOT NULL DEFAULT 0;
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS next_reconcile_at TIMESTAMP;
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS reconcile_checkpoints_minutes VARCHAR(1024);
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS reconcile_retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS reconcile_unknown_since TIMESTAMP;
ALTER TABLE p_payments DROP CONSTRAINT IF EXISTS ck_p_payments_reconcile_count;
ALTER TABLE p_payments DROP CONSTRAINT IF EXISTS ck_p_payments_reconcile_state;
ALTER TABLE p_payments DROP CONSTRAINT IF EXISTS ck_p_payments_reconcile_retry;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = 'p_payments'::regclass AND attname = 'reconciled_at' AND NOT attisdropped) THEN
        UPDATE p_payments
        SET failed_at = COALESCE(failed_at, approve_requested_at, updated_at),
            reconcile_count = 0,
            next_reconcile_at = CASE WHEN approve_requested_at IS NOT NULL
                THEN COALESCE(failed_at, approve_requested_at, updated_at) + INTERVAL '1 minute' ELSE NULL END,
            reconcile_checkpoints_minutes = CASE WHEN approve_requested_at IS NOT NULL
                THEN '1,5,15,60,1440' ELSE NULL END
        WHERE status = 'FAILED' AND reconcile_checkpoints_minutes IS NULL;
    END IF;
END $$;

UPDATE p_payments
SET failed_at = COALESCE(failed_at, approve_requested_at, updated_at),
    reconcile_checkpoints_minutes = '1,5,15,60,1440',
    next_reconcile_at = CASE WHEN reconcile_count = 2 AND next_reconcile_at IS NULL THEN NULL
        ELSE COALESCE(failed_at, approve_requested_at, updated_at) + INTERVAL '1 minute' END,
    reconcile_count = CASE WHEN reconcile_count = 2 AND next_reconcile_at IS NULL THEN 5 ELSE 0 END
WHERE status = 'FAILED' AND approve_requested_at IS NOT NULL AND reconcile_checkpoints_minutes IS NULL;

UPDATE p_payments SET failed_at = COALESCE(approve_requested_at, updated_at)
WHERE status = 'FAILED' AND failed_at IS NULL;

DROP INDEX IF EXISTS ix_p_payments_reconcile;
ALTER TABLE p_payments DROP COLUMN IF EXISTS reconciled_at;
ALTER TABLE p_payments ADD CONSTRAINT ck_p_payments_reconcile_count CHECK (reconcile_count >= 0 AND
    ((reconcile_checkpoints_minutes IS NULL AND reconcile_count = 0) OR
     (reconcile_checkpoints_minutes IS NOT NULL AND reconcile_count <= cardinality(string_to_array(reconcile_checkpoints_minutes, ',')))));
ALTER TABLE p_payments ADD CONSTRAINT ck_p_payments_reconcile_state CHECK (
    next_reconcile_at IS NULL OR (status = 'FAILED' AND approve_requested_at IS NOT NULL
        AND failed_at IS NOT NULL AND reconcile_checkpoints_minutes IS NOT NULL
        AND reconcile_count < cardinality(string_to_array(reconcile_checkpoints_minutes, ','))));
ALTER TABLE p_payments ADD CONSTRAINT ck_p_payments_reconcile_retry CHECK (reconcile_retry_count >= 0 AND
    ((reconcile_retry_count = 0 AND reconcile_unknown_since IS NULL) OR
     (reconcile_retry_count > 0 AND reconcile_unknown_since IS NOT NULL)));
CREATE INDEX ix_p_payments_reconcile ON p_payments (next_reconcile_at, id)
    WHERE status = 'FAILED' AND approve_requested_at IS NOT NULL AND next_reconcile_at IS NOT NULL;
COMMIT;
