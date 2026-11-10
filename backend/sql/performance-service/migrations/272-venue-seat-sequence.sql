-- 이슈 #272: 기존 물리 좌석 ID와 참조를 보존하고 SEQUENCE 발급으로 전환한다.
-- 모든 공연 서비스 인스턴스와 직접 좌석 쓰기를 중지한 뒤 performance_app으로 실행한다.
-- 기존 DB 전용이다. 새 DB는 schema.sql을 사용한다. 볼륨을 지우지 않는다.
-- 이행 후에는 새 코드로만 재기동한다. 실행 중인 JVM의 ID 예약 구간은 재사용하지 않는다.
BEGIN;
SET LOCAL search_path TO performance_schema;
LOCK TABLE p_venue_seats IN ACCESS EXCLUSIVE MODE;

CREATE SEQUENCE IF NOT EXISTS p_venue_seats_seq START WITH 1 INCREMENT BY 50;

DO $$
DECLARE
    old_sequence text;
    old_last bigint := 0;
    high_water bigint;
BEGIN
    old_sequence := pg_get_serial_sequence('p_venue_seats', 'id');
    IF old_sequence IS NOT NULL THEN
        EXECUTE format('SELECT last_value FROM %s', old_sequence) INTO old_last;
    END IF;
    SELECT greatest(coalesce(max(id), 0), old_last,
                    (SELECT last_value FROM p_venue_seats_seq))
      INTO high_water FROM p_venue_seats;
    ALTER TABLE p_venue_seats ALTER COLUMN id DROP IDENTITY IF EXISTS;
    -- pooled는 nextval을 구간의 최댓값으로 해석한다. 첫 구간 전체가 기존 ID보다 커야 한다.
    EXECUTE format('ALTER SEQUENCE p_venue_seats_seq INCREMENT BY 50 RESTART WITH %s', high_water + 50);
END $$;

ALTER TABLE p_venue_seats ALTER COLUMN id SET DEFAULT nextval('p_venue_seats_seq');
ALTER SEQUENCE p_venue_seats_seq OWNED BY p_venue_seats.id;
COMMIT;
