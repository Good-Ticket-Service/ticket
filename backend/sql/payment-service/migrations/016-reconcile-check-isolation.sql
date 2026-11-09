-- 정산 대조 항목별 미완료 기록(#310). 항목 하나가 제한 시간을 넘어도 나머지 결과를 남긴다. 다시 실행해도 안전하다.
-- 앱 계정(payment_app)으로 실행한다(테이블 소유자).
ALTER TABLE p_settlement_reconcile_results ADD COLUMN IF NOT EXISTS completed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE p_settlement_reconcile_results ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
ALTER TABLE p_settlement_reconcile_results ADD COLUMN IF NOT EXISTS error VARCHAR(80);
ALTER TABLE p_settlement_reconcile_results ALTER COLUMN violations DROP NOT NULL;
ALTER TABLE p_settlement_reconcile_results DROP CONSTRAINT IF EXISTS ck_p_settlement_reconcile_results_completed;
ALTER TABLE p_settlement_reconcile_results
    ADD CONSTRAINT ck_p_settlement_reconcile_results_completed CHECK (completed = (violations IS NOT NULL));
