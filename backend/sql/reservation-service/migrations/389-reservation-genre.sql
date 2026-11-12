-- #389 예약의 공연 장르(장르별 쿠폰, 계약 #383). 이미 테이블이 있는 DB(dev 등)에 한 번 적용한다.
-- 기존 예약은 장르를 모르므로 NULL로 남고 장르 쿠폰만 쓸 수 없다. 새 버전 서버로 바꾸기 전에 적용한다.
-- local은 Hibernate가 기동할 때 테이블을 새로 만들어(ddl-auto: create) 적용할 필요가 없다.
BEGIN;
SET LOCAL search_path TO reservation_schema;

ALTER TABLE p_reservations ADD COLUMN IF NOT EXISTS genre VARCHAR(30);

COMMIT;
