-- 이슈 #438: 수동 실행용 지역 코드 이행. 새 코드 전용 앱을 기동하기 전에 적용한다.
-- 측정용 원본 DB(ticket_kopis·ticket_cumulative)는 옛 지역 값을 보존하므로 차단한다.
-- 측정에는 원본을 복사한 DB에 적용한다. 팀 개발 DB ticket은 접속 대상 확인 뒤 수동 이행할 수 있다.
-- 같은 psql 세션에서 SET performance.region_migration_database = '대상 DB명'; 을 먼저 실행한다.
-- 예: psql -v ON_ERROR_STOP=1 -c "SET performance.region_migration_database = 'ticket'" -f 438-region-codes.sql
-- 반드시 팀 개발 DB로 접속한다. 미대응 값이면 전체 롤백한다.
BEGIN;
SET LOCAL search_path TO performance_schema;
DO $$
BEGIN
    IF current_database() IN ('ticket_kopis', 'ticket_cumulative') THEN
        RAISE EXCEPTION '측정용 원본 DB에는 지역 이행을 실행할 수 없습니다. 복사한 DB에 적용하십시오: %', current_database();
    END IF;
    IF current_setting('performance.region_migration_database', true) IS DISTINCT FROM current_database() THEN
        RAISE EXCEPTION '지역 이행 중단: 실행 대상 DB를 명시적으로 확인해야 합니다. 현재 DB: %', current_database()
            USING HINT = '같은 세션에서 SET performance.region_migration_database = ''대상 DB명''; 을 실행하십시오.';
    END IF;
END $$;

-- 검사와 갱신 사이에 자유 입력 행이 추가되는 것을 막는다. 삭제 행도 동일하게 검사·이행한다.
LOCK TABLE p_venues IN ACCESS EXCLUSIVE MODE;
CREATE TEMP TABLE region_mapping_438 (original text PRIMARY KEY, code varchar(20) NOT NULL) ON COMMIT DROP;
INSERT INTO region_mapping_438 (original, code) VALUES
    -- 동기화 대상: domain/venue/Region.java의 별칭과 script/kopis-seed/generate.py의 REGION_ALIASES.
    -- 이행 표는 검색 별칭에 코드 자체를 더한다. 이름·코드를 바꾸면 세 곳을 함께 갱신한다.
    ('서울', 'SEOUL'), ('서울특별시', 'SEOUL'), ('SEOUL', 'SEOUL'),
    ('경기', 'GYEONGGI'), ('경기도', 'GYEONGGI'), ('GYEONGGI', 'GYEONGGI'),
    ('인천', 'INCHEON'), ('인천광역시', 'INCHEON'), ('INCHEON', 'INCHEON'),
    ('강원', 'GANGWON'), ('강원도', 'GANGWON'), ('강원특별자치도', 'GANGWON'), ('GANGWON', 'GANGWON'),
    ('충북', 'CHUNGBUK'), ('충청북도', 'CHUNGBUK'), ('CHUNGBUK', 'CHUNGBUK'),
    ('충남', 'CHUNGNAM'), ('충청남도', 'CHUNGNAM'), ('CHUNGNAM', 'CHUNGNAM'),
    ('대전', 'DAEJEON'), ('대전광역시', 'DAEJEON'), ('DAEJEON', 'DAEJEON'),
    ('세종', 'SEJONG'), ('세종특별자치시', 'SEJONG'), ('SEJONG', 'SEJONG'),
    ('전북', 'JEONBUK'), ('전라북도', 'JEONBUK'), ('전북특별자치도', 'JEONBUK'), ('JEONBUK', 'JEONBUK'),
    ('전남광주', 'JEONNAM_GWANGJU'), ('광주', 'JEONNAM_GWANGJU'), ('전남', 'JEONNAM_GWANGJU'),
    ('전남광주통합특별시', 'JEONNAM_GWANGJU'), ('광주광역시', 'JEONNAM_GWANGJU'),
    ('전라남도', 'JEONNAM_GWANGJU'), ('JEONNAM_GWANGJU', 'JEONNAM_GWANGJU'),
    ('경북', 'GYEONGBUK'), ('경상북도', 'GYEONGBUK'), ('GYEONGBUK', 'GYEONGBUK'),
    ('경남', 'GYEONGNAM'), ('경상남도', 'GYEONGNAM'), ('GYEONGNAM', 'GYEONGNAM'),
    ('대구', 'DAEGU'), ('대구광역시', 'DAEGU'), ('DAEGU', 'DAEGU'),
    ('울산', 'ULSAN'), ('울산광역시', 'ULSAN'), ('ULSAN', 'ULSAN'),
    ('부산', 'BUSAN'), ('부산광역시', 'BUSAN'), ('BUSAN', 'BUSAN'),
    ('제주', 'JEJU'), ('제주도', 'JEJU'), ('제주특별자치도', 'JEJU'), ('JEJU', 'JEJU');

-- 갱신 전에 대응되지 않는 모든 값을 보여 준다. 빈 문자열·임의 값은 기본 지역으로 바꾸지 않는다.
SELECT DISTINCT v.region AS unmapped_region FROM p_venues v
LEFT JOIN region_mapping_438 m ON m.original = btrim(v.region)
WHERE m.original IS NULL ORDER BY v.region;
DO $$
DECLARE
    unknown_values text;
BEGIN
    SELECT string_agg(quote_nullable(region), ', ' ORDER BY region) INTO unknown_values
    FROM (SELECT DISTINCT v.region FROM p_venues v
          LEFT JOIN region_mapping_438 m ON m.original = btrim(v.region)
          WHERE m.original IS NULL) unknown_regions;
    IF unknown_values IS NOT NULL THEN
        RAISE EXCEPTION '지역 이행 중단: 대응되지 않는 값: %', unknown_values;
    END IF;
END $$;

-- 표현 정규화이므로 ID·감사 이력·version·삭제 표시를 보존한다. 이미 이행된 행은 갱신하지 않는다.
UPDATE p_venues v SET region = m.code FROM region_mapping_438 m
WHERE m.original = btrim(v.region) AND v.region IS DISTINCT FROM m.code;
ALTER TABLE p_venues DROP CONSTRAINT IF EXISTS ck_p_venues_region;
ALTER TABLE p_venues ADD CONSTRAINT ck_p_venues_region CHECK (region IN
    ('SEOUL', 'GYEONGGI', 'INCHEON', 'GANGWON', 'CHUNGBUK', 'CHUNGNAM', 'DAEJEON', 'SEJONG',
     'JEONBUK', 'JEONNAM_GWANGJU', 'GYEONGBUK', 'GYEONGNAM', 'DAEGU', 'ULSAN', 'BUSAN', 'JEJU'));
-- CHECK가 삭제 행을 포함한 전 행을 검사한다. 실패하면 위 UPDATE도 커밋되지 않는다.
SELECT count(*) AS remaining_non_code_regions FROM p_venues v
WHERE NOT EXISTS (SELECT 1 FROM region_mapping_438 m WHERE m.code = v.region);
COMMIT;
