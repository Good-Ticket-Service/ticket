-- #389 쿠폰 적용 장르(장르별 쿠폰). 이미 테이블이 있는 DB(dev 등)에 한 번 적용한다.
-- 기존 쿠폰은 모두 NULL(모든 장르)이 되고 동작은 그대로다. 새 버전 서버로 바꾸기 전에 적용한다.
-- local은 Hibernate가 기동할 때 테이블을 새로 만들어(ddl-auto: create) 적용할 필요가 없다.
BEGIN;
SET LOCAL search_path TO reservation_schema;

ALTER TABLE p_coupons ADD COLUMN IF NOT EXISTS genre VARCHAR(30);
ALTER TABLE p_coupons DROP CONSTRAINT IF EXISTS ck_p_coupons_genre;
ALTER TABLE p_coupons ADD CONSTRAINT ck_p_coupons_genre CHECK (genre IS NULL OR genre IN ('CONCERT', 'MUSICAL', 'PLAY',
    'SPORTS', 'CLASSICAL', 'KOREAN_MUSIC', 'DANCE', 'CIRCUS_MAGIC', 'MULTIDISCIPLINARY'));

COMMIT;
