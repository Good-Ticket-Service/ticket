-- 모든 payment 실행 역할을 중지하고 002·003 이후 적용한다. 과거 제한 시각을 추정하지 않는다.
BEGIN;
LOCK TABLE p_refunds IN ACCESS EXCLUSIVE MODE;
ALTER TABLE p_refunds ADD COLUMN IF NOT EXISTS restriction_started_at TIMESTAMP;
CREATE TABLE IF NOT EXISTS p_refund_operation_commands (
    command_id VARCHAR(80) PRIMARY KEY,
    input_hash VARCHAR(64) NOT NULL,
    payment_id BIGINT NOT NULL,
    refund_id BIGINT NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('RETRY','RESUME')),
    actor VARCHAR(200) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    result VARCHAR(40) NOT NULL CHECK (result IN ('APPLIED','CONFIRMED_CANCELED','DENIED','STALE','PG_UNKNOWN')),
    explanation VARCHAR(80) NOT NULL,
    expected_state VARCHAR(160) NOT NULL,
    before_state VARCHAR(160) NOT NULL,
    after_state VARCHAR(160) NOT NULL,
    inquiry VARCHAR(40) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS ix_refund_operations_history
    ON p_refund_operation_commands (refund_id, processed_at DESC, command_id DESC);
COMMIT;
