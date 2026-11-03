-- 기존 상태·요청·시도 이력은 보존한다. 과거 실패 원인은 추정하지 않는다.
BEGIN;
SET LOCAL search_path TO performance_schema;
ALTER TABLE p_schedules
    ADD COLUMN IF NOT EXISTS seat_setup_failure_kind VARCHAR(32),
    ADD COLUMN IF NOT EXISTS seat_setup_recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS seat_setup_recovery_limit INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS seat_setup_recovery_next_at TIMESTAMP(3);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='p_schedules'::regclass AND conname='ck_p_schedules_recovery_counts') THEN
        ALTER TABLE p_schedules ADD CONSTRAINT ck_p_schedules_recovery_counts
            CHECK (seat_setup_recovery_attempts >= 0 AND seat_setup_recovery_limit >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='p_schedules'::regclass AND conname='ck_p_schedules_failure_kind') THEN
        ALTER TABLE p_schedules ADD CONSTRAINT ck_p_schedules_failure_kind
            CHECK (seat_setup_failure_kind IN ('RESULT_UNKNOWN','INVALID_REQUEST','INVALID_CONFIGURATION','SEAT_IN_USE','STALE_VERSION'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_p_schedules_recovery_due ON p_schedules (seat_setup_recovery_next_at,id)
    WHERE seat_setup_status='FAILED' AND seat_setup_failure_kind='RESULT_UNKNOWN' AND deleted_at IS NULL;
COMMIT;
