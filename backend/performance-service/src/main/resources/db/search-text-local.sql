-- local 프로필 전용: 공연 검색용 텍스트(search_text) 트리거(이슈 #441).
-- local은 Hibernate가 Entity로 테이블을 만들어(ddl-auto: create) schema.sql의 함수·트리거가 없다.
-- 시드보다 먼저 실행해 시드 INSERT와 등록 API가 search_text(NOT NULL)를 채우게 한다.
-- trigram 확장·인덱스는 만들지 않는다. local 데이터는 작아 LIKE 검색이 인덱스 없이도 동작한다.
-- 같은 정의가 backend/sql/performance-service/schema.sql과 migrations/441-search-text.sql에도 있다. 바꾸면 세 곳을 함께 고친다.
-- 함수 본문은 작은따옴표로 감싼다. Spring SQL 초기화가 $$ 인용을 모른다.

CREATE OR REPLACE FUNCTION p_performance_search_text(title TEXT, cast_info TEXT, venue_name TEXT)
    RETURNS TEXT LANGUAGE sql IMMUTABLE PARALLEL SAFE AS '
SELECT lower(replace(title, '' '', '''')) || chr(31)
    || lower(replace(coalesce(cast_info, ''''), '' '', '''')) || chr(31)
    || lower(replace(coalesce(venue_name, ''''), '' '', ''''))';

CREATE OR REPLACE FUNCTION p_performances_fill_search_text() RETURNS trigger LANGUAGE plpgsql
    SET search_path FROM CURRENT AS '
BEGIN
    NEW.search_text := p_performance_search_text(
        NEW.title, NEW.cast_info, (SELECT v.name FROM p_venues v WHERE v.id = NEW.venue_id));
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

-- 테이블은 기동마다 새로 만들어져 트리거도 함께 사라지지만, 다시 실행해도 되게 먼저 지운다
DROP TRIGGER IF EXISTS p_performances_search_text ON p_performances;
CREATE TRIGGER p_performances_search_text
    BEFORE INSERT OR UPDATE OF title, cast_info, venue_id ON p_performances
    FOR EACH ROW EXECUTE FUNCTION p_performances_fill_search_text();

DROP TRIGGER IF EXISTS p_venues_search_text ON p_venues;
CREATE TRIGGER p_venues_search_text
    AFTER UPDATE OF name ON p_venues
    FOR EACH ROW WHEN (OLD.name IS DISTINCT FROM NEW.name) EXECUTE FUNCTION p_venues_refresh_search_text();
