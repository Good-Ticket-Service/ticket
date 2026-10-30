-- Task 20-9: 지급 실행권 점유 만료를 next_attempt_at 하나로 표현한다(visibility timeout).
-- 010에서 추가한 lease_until은 next_attempt_at과 같은 역할이라 지운다. 다시 실행해도 안전하다.
ALTER TABLE p_payouts DROP COLUMN IF EXISTS lease_until;
