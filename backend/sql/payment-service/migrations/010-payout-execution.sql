-- 지급 실행 상태·계좌 스냅샷·지급대행 결과 열과 정산서 지급 완료를 추가한다(Task 20-8). 기존 지급은 모두 READY라 새 CHECK를 지킨다. 다시 실행해도 안전하다.
BEGIN;
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS bank_code VARCHAR(10);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS account_ref VARCHAR(100);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS holder_masked VARCHAR(100);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS account_version BIGINT;
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS provider_status VARCHAR(20);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS external_payout_id VARCHAR(100);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS failure_code VARCHAR(40);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS inquiry_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS verification_reason VARCHAR(80);
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS requested_at TIMESTAMP;
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS done_at TIMESTAMP;
ALTER TABLE p_payouts ADD COLUMN IF NOT EXISTS last_error VARCHAR(80);
ALTER TABLE p_settlement_statements ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;

-- 상태 CHECK는 이름이 같아 지우고 다시 만든다
ALTER TABLE p_payouts DROP CONSTRAINT IF EXISTS ck_p_payouts_status;
ALTER TABLE p_payouts ADD CONSTRAINT ck_p_payouts_status CHECK (status IN ('READY', 'REQUESTED', 'DONE', 'FAILED'));
ALTER TABLE p_settlement_statements DROP CONSTRAINT IF EXISTS ck_p_settlement_statements_status;
ALTER TABLE p_settlement_statements ADD CONSTRAINT ck_p_settlement_statements_status CHECK (
    (status = 'ISSUED' AND payable_amount > 0 AND paid_at IS NULL)
    OR (status = 'NO_PAYOUT' AND payable_amount = 0 AND paid_at IS NULL)
    OR (status = 'PAID' AND payable_amount > 0 AND paid_at IS NOT NULL));

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_p_payouts_provider_status') THEN
        ALTER TABLE p_payouts ADD CONSTRAINT ck_p_payouts_provider_status
            CHECK (provider_status IS NULL OR provider_status IN ('ACCEPTED', 'PROCESSING', 'UNKNOWN'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_p_payouts_claimed') THEN
        ALTER TABLE p_payouts ADD CONSTRAINT ck_p_payouts_claimed CHECK (status = 'READY' OR (
            idempotency_key IS NOT NULL AND bank_code IS NOT NULL AND account_ref IS NOT NULL
            AND holder_masked IS NOT NULL AND account_version IS NOT NULL AND requested_at IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_p_payouts_done') THEN
        ALTER TABLE p_payouts ADD CONSTRAINT ck_p_payouts_done
            CHECK (status <> 'DONE' OR (external_payout_id IS NOT NULL AND done_at IS NOT NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_p_payouts_failed') THEN
        ALTER TABLE p_payouts ADD CONSTRAINT ck_p_payouts_failed CHECK ((status = 'FAILED') = (failure_code IS NOT NULL));
    END IF;
END
$$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_p_payouts_idempotency_key ON p_payouts (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_p_payouts_ready ON p_payouts (next_attempt_at) WHERE status = 'READY';
COMMIT;
