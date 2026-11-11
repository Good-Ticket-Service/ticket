-- #357 쿠폰 구분(일반·웰컴). 이미 테이블이 있는 DB(dev 등)에 한 번 적용한다.
-- 기존 쿠폰은 모두 일반(GENERAL)이 되고 동작은 그대로다. 새 버전 서버로 바꾸기 전에 적용한다.
-- local은 Hibernate가 기동할 때 테이블을 새로 만들어(ddl-auto: create) 적용할 필요가 없다.
BEGIN;
SET LOCAL search_path TO reservation_schema;

ALTER TABLE p_coupons ADD COLUMN IF NOT EXISTS category VARCHAR(20) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE p_coupons DROP CONSTRAINT IF EXISTS ck_p_coupons_category;
ALTER TABLE p_coupons ADD CONSTRAINT ck_p_coupons_category CHECK (category IN ('GENERAL', 'WELCOME'));
ALTER TABLE p_coupons DROP CONSTRAINT IF EXISTS ck_p_coupons_welcome_valid_days;
ALTER TABLE p_coupons ADD CONSTRAINT ck_p_coupons_welcome_valid_days CHECK (category <> 'WELCOME' OR valid_days = 7);

COMMIT;
