-- 결제에 회차 시작 시각을 추가한다(Task 20-3). 기존 결제는 값이 없고(NULL) 정산에서 보류된다. 다시 실행해도 안전하다.
BEGIN;
ALTER TABLE p_payments ADD COLUMN IF NOT EXISTS schedule_start_at TIMESTAMP;
COMMIT;
