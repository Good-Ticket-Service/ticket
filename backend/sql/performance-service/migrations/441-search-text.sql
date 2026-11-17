-- 이슈 #441: 수동 실행용 공연 검색용 텍스트(search_text)·trigram 인덱스 이행. 새 코드 앱을 기동하기 전에 적용한다.
-- 반드시 관리자(superuser) 계정으로 실행한다. pg_trgm 확장을 performance_schema에 설치하기 때문이다.
--   공연 서비스 계정(performance_app)에는 DB 생성 권한을 주지 않는다(계획서 3.1, 사용자 결정 2026-11-17).
-- 측정용 원본 DB(ticket_kopis·ticket_cumulative)와 이슈 #441 기준선 DB(ticket_cumulative_438)는 차단한다.
--   측정에는 이 DB들을 복사한 DB에 적용한다.
-- 같은 psql 세션에서 SET performance.search_text_migration_database = '대상 DB명'; 을 먼저 실행한다.
-- 예: psql -U postgres -d 대상DB -v ON_ERROR_STOP=1
--         -c "SET performance.search_text_migration_database = '대상DB'" -f 441-search-text.sql
-- 1부(검사·확장·컬럼·트리거·채우기)는 한 트랜잭션이다. 실패하면 아무것도 바뀌지 않는다.
-- 2부(인덱스)는 쓰기를 막지 않도록 CONCURRENTLY로 만들어 트랜잭션 밖에서 실행한다.
--   2부가 중간에 실패하면 무효 인덱스가 남는다. 같은 이름의 다른 정의 인덱스가 이미 있어도 만들지 않고 넘어간다.
--   마지막 검사가 두 경우를 현재 정의와 함께 알려 준다. 확인 뒤 DROP INDEX CONCURRENTLY 하고 2부를 다시 실행한다.
-- 다시 실행해도 된다. 이미 맞는 행은 갱신하지 않는다.
-- 같은 정의가 schema.sql과 performance-service의 db/search-text-local.sql에도 있다. 바꾸면 세 곳을 함께 고친다.

-- 1부
BEGIN;
SET LOCAL search_path TO performance_schema;
DO $$
BEGIN
    IF current_database() IN ('ticket_kopis', 'ticket_cumulative', 'ticket_cumulative_438') THEN
        RAISE EXCEPTION '측정 기준 DB에는 검색용 텍스트 이행을 실행할 수 없습니다. 복사한 DB에 적용하십시오: %', current_database();
    END IF;
    IF current_setting('performance.search_text_migration_database', true) IS DISTINCT FROM current_database() THEN
        RAISE EXCEPTION '검색용 텍스트 이행 중단: 실행 대상 DB를 명시적으로 확인해야 합니다. 현재 DB: %', current_database()
            USING HINT = '같은 세션에서 SET performance.search_text_migration_database = ''대상 DB명''; 을 실행하십시오.';
    END IF;
    -- C·POSIX 문자 설정이면 pg_trgm이 한글 조각을 만들지 못해 인덱스가 한글 검색을 좁히지 못한다(이슈 #441 C1 확인)
    IF (SELECT datctype FROM pg_database WHERE datname = current_database()) IN ('C', 'POSIX') THEN
        RAISE EXCEPTION '검색용 텍스트 이행 중단: DB 문자 설정(datctype)이 %이면 한글 trigram이 동작하지 않습니다.',
            (SELECT datctype FROM pg_database WHERE datname = current_database());
    END IF;
END $$;

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA performance_schema;

-- 문자 설정 이름만으로는 알 수 없는 경우를 위해 실제로 한글 조각을 만드는지 확인한다. 실패하면 확장 설치도 롤백된다.
DO $$
BEGIN
    IF coalesce(array_length(show_trgm('피아노'), 1), 0) = 0 THEN
        RAISE EXCEPTION '검색용 텍스트 이행 중단: pg_trgm이 한글 조각을 만들지 못합니다. DB 문자 설정을 확인하십시오.';
    END IF;
END $$;

ALTER TABLE p_performances ADD COLUMN IF NOT EXISTS search_text TEXT;

CREATE OR REPLACE FUNCTION p_performance_search_text(title TEXT, cast_info TEXT, venue_name TEXT)
    RETURNS TEXT LANGUAGE sql IMMUTABLE PARALLEL SAFE AS '
SELECT lower(replace(title, '' '', '''')) || chr(31)
    || lower(replace(coalesce(cast_info, ''''), '' '', '''')) || chr(31)
    || lower(replace(coalesce(venue_name, ''''), '' '', ''''))';

-- SET search_path FROM CURRENT: 위 SET LOCAL의 performance_schema를 함수에 고정한다
-- 새 공연이거나 공연장을 바꾸면 공연장 행을 공유 잠금(FOR SHARE)으로 읽는다. 커밋 전인 공연장명 변경이 있으면 끝날 때까지
-- 기다려 바뀐 이름을 읽는다. 공연장 트리거의 일괄 갱신에는 아직 보이지 않는 이 행이 빠지기 때문이다(이슈 #441 리뷰 R1-1).
-- 제목·출연진만 바꿀 때는 잠그지 않는다. 같은 공연 행의 행 잠금으로 공연장 트리거와 이미 직렬화되고,
-- 잠그면 공연 행 → 공연장 순서가 공연장 트리거의 공연장 → 공연 행 순서와 엇갈려 교착이 날 수 있다.
CREATE OR REPLACE FUNCTION p_performances_fill_search_text() RETURNS trigger LANGUAGE plpgsql
    SET search_path FROM CURRENT AS '
DECLARE
    venue_name TEXT;
    lock_venue BOOLEAN := TG_OP = ''INSERT'';
BEGIN
    IF NOT lock_venue THEN
        lock_venue := NEW.venue_id IS DISTINCT FROM OLD.venue_id;
    END IF;
    IF lock_venue THEN
        SELECT v.name INTO venue_name FROM p_venues v WHERE v.id = NEW.venue_id FOR SHARE;
    ELSE
        SELECT v.name INTO venue_name FROM p_venues v WHERE v.id = NEW.venue_id;
    END IF;
    NEW.search_text := p_performance_search_text(NEW.title, NEW.cast_info, venue_name);
    RETURN NEW;
END';

CREATE OR REPLACE FUNCTION p_venues_refresh_search_text() RETURNS trigger LANGUAGE plpgsql
    SET search_path FROM CURRENT AS '
BEGIN
    UPDATE p_performances p
    SET search_text = p_performance_search_text(p.title, p.cast_info, NEW.name)
    WHERE p.venue_id = NEW.id;
    RETURN NULL;
END';

-- 트리거를 먼저 만든다. 트리거 생성이 두 테이블의 쓰기를 커밋까지 막으므로, 채우는 동안 바뀐 행이 빠지지 않는다
DROP TRIGGER IF EXISTS p_performances_search_text ON p_performances;
CREATE TRIGGER p_performances_search_text
    BEFORE INSERT OR UPDATE OF title, cast_info, venue_id ON p_performances
    FOR EACH ROW EXECUTE FUNCTION p_performances_fill_search_text();

DROP TRIGGER IF EXISTS p_venues_search_text ON p_venues;
CREATE TRIGGER p_venues_search_text
    AFTER UPDATE OF name ON p_venues
    FOR EACH ROW WHEN (OLD.name IS DISTINCT FROM NEW.name) EXECUTE FUNCTION p_venues_refresh_search_text();

-- 삭제 행도 채운다. 파생 값이므로 감사 컬럼·version은 바꾸지 않는다
UPDATE p_performances p
SET search_text = p_performance_search_text(p.title, p.cast_info, v.name)
FROM p_venues v
WHERE v.id = p.venue_id
  AND p.search_text IS DISTINCT FROM p_performance_search_text(p.title, p.cast_info, v.name);

ALTER TABLE p_performances ALTER COLUMN search_text SET NOT NULL;

SELECT count(*) AS filled_performances FROM p_performances;
COMMIT;

-- 2부: 트랜잭션 밖에서 실행한다
SET search_path TO performance_schema;
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_p_performances_search_text
    ON p_performances USING gin (search_text gin_trgm_ops);
-- IF NOT EXISTS는 이름만 보므로, 같은 이름의 다른 인덱스가 있어도 넘어간다. 정의까지 대조하고 다르면 지우지 않고 중단한다
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM pg_index i
                   JOIN pg_class ic ON ic.oid = i.indexrelid
                   JOIN pg_am am ON am.oid = ic.relam
                   JOIN pg_opclass oc ON oc.oid = i.indclass[0]
                   JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                   WHERE i.indexrelid = to_regclass('ix_p_performances_search_text')
                     AND i.indrelid = to_regclass('p_performances')
                     AND i.indisvalid
                     AND i.indnatts = 1
                     AND i.indpred IS NULL
                     AND i.indexprs IS NULL
                     AND am.amname = 'gin'
                     AND oc.opcname = 'gin_trgm_ops'
                     AND a.attname = 'search_text') THEN
        RAISE EXCEPTION '인덱스 ix_p_performances_search_text가 없거나, 무효이거나, 정의가 다릅니다. 현재 정의: %',
            coalesce(pg_get_indexdef(to_regclass('ix_p_performances_search_text')), '없음')
            USING HINT = '확인 뒤 DROP INDEX CONCURRENTLY ix_p_performances_search_text; 를 실행하고 2부를 다시 실행하십시오.';
    END IF;
END $$;
ANALYZE p_performances;
RESET search_path;
