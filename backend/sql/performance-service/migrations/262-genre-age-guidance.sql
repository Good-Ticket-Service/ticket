-- 기존 등급과 장르는 그대로 보존한다. 신규 값 입력 전에 모든 공연 서버를 새 버전으로 교체한다.
BEGIN;
SET LOCAL search_path TO performance_schema;
ALTER TABLE p_performances
    ADD COLUMN IF NOT EXISTS age_guidance VARCHAR(100),
    ALTER COLUMN age_rating DROP NOT NULL;
ALTER TABLE p_performances DROP CONSTRAINT IF EXISTS ck_p_performances_genre;
-- local의 Hibernate가 만든 인라인 CHECK 이름도 교체한다.
ALTER TABLE p_performances DROP CONSTRAINT IF EXISTS p_performances_genre_check;
ALTER TABLE p_performances ADD CONSTRAINT ck_p_performances_genre CHECK (genre IN
    ('CONCERT', 'MUSICAL', 'PLAY', 'SPORTS', 'CLASSICAL', 'KOREAN_MUSIC', 'DANCE', 'CIRCUS_MAGIC', 'MULTIDISCIPLINARY'));
ALTER TABLE p_performances DROP CONSTRAINT IF EXISTS ck_p_performances_age_guidance;
ALTER TABLE p_performances ADD CONSTRAINT ck_p_performances_age_guidance
    CHECK (age_guidance IS NULL OR btrim(age_guidance) <> '');
COMMIT;
