-- #348 쿠폰 할인 정책(정률 할인, 최대 할인액, 최소 적용 금액). 이미 테이블이 있는 DB(dev 등)에 한 번 적용한다.
-- 기존 쿠폰과 회원 쿠폰은 모두 정액(FIXED)이 되고 할인액·동작은 그대로다. 새 버전 서버로 바꾸기 전에 적용한다.
-- local은 Hibernate가 기동할 때 테이블을 새로 만들어(ddl-auto: create) 적용할 필요가 없다.
BEGIN;
SET LOCAL search_path TO reservation_schema;

ALTER TABLE p_coupons
    ADD COLUMN IF NOT EXISTS discount_type VARCHAR(10) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN IF NOT EXISTS discount_rate INT,
    ADD COLUMN IF NOT EXISTS max_discount_amount INT,
    ADD COLUMN IF NOT EXISTS min_base_amount INT,
    ALTER COLUMN discount_amount DROP NOT NULL;
ALTER TABLE p_coupons DROP CONSTRAINT IF EXISTS ck_p_coupons_discount;
ALTER TABLE p_coupons ADD CONSTRAINT ck_p_coupons_discount CHECK (
    (discount_type = 'FIXED' AND discount_amount > 0 AND discount_rate IS NULL AND max_discount_amount IS NULL)
    OR (discount_type = 'RATE' AND discount_amount IS NULL AND discount_rate BETWEEN 1 AND 99
        AND (max_discount_amount IS NULL OR max_discount_amount > 0)));
ALTER TABLE p_coupons DROP CONSTRAINT IF EXISTS ck_p_coupons_min_base;
ALTER TABLE p_coupons ADD CONSTRAINT ck_p_coupons_min_base CHECK (min_base_amount IS NULL OR min_base_amount > 0);

ALTER TABLE p_user_coupons
    ADD COLUMN IF NOT EXISTS discount_type VARCHAR(10) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN IF NOT EXISTS discount_rate INT,
    ADD COLUMN IF NOT EXISTS max_discount_amount INT,
    ADD COLUMN IF NOT EXISTS min_base_amount INT,
    ALTER COLUMN discount_amount DROP NOT NULL;
ALTER TABLE p_user_coupons DROP CONSTRAINT IF EXISTS ck_p_user_coupons_discount;
ALTER TABLE p_user_coupons ADD CONSTRAINT ck_p_user_coupons_discount CHECK (
    (discount_type = 'FIXED' AND discount_amount > 0 AND discount_rate IS NULL AND max_discount_amount IS NULL)
    OR (discount_type = 'RATE' AND discount_amount IS NULL AND discount_rate BETWEEN 1 AND 99
        AND (max_discount_amount IS NULL OR max_discount_amount > 0)));
ALTER TABLE p_user_coupons DROP CONSTRAINT IF EXISTS ck_p_user_coupons_min_base;
ALTER TABLE p_user_coupons ADD CONSTRAINT ck_p_user_coupons_min_base CHECK (min_base_amount IS NULL OR min_base_amount > 0);

COMMIT;
