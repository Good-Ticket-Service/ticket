-- 판매자 수취 계좌 등록 컬럼 추가(Task 20-7). 계좌번호 원문은 저장하지 않는다. 기존 기준 행은 등록 전(모두 NULL)이다. 다시 실행해도 안전하다.
BEGIN;
ALTER TABLE p_seller_payout_accounts ADD COLUMN IF NOT EXISTS bank_code VARCHAR(10);
ALTER TABLE p_seller_payout_accounts ADD COLUMN IF NOT EXISTS account_masked VARCHAR(40);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_p_seller_payout_accounts_registered') THEN
        ALTER TABLE p_seller_payout_accounts ADD CONSTRAINT ck_p_seller_payout_accounts_registered CHECK (
            (account_ref IS NULL AND bank_code IS NULL AND account_masked IS NULL AND holder_masked IS NULL)
            OR (account_ref IS NOT NULL AND bank_code IS NOT NULL AND account_masked IS NOT NULL AND holder_masked IS NOT NULL));
    END IF;
END
$$;
COMMIT;
