-- public-performance-fixture.sql 위에 더하는 공연 25개(101~125). 한 페이지(기본 20개)를 넘는 목록을 본다.
-- 기준 시각은 2026-11-01 12:00(KST)이다.
--
--   101~125 PLAY 서울 '대량 공연 N'. 공개 시각은 10-01 + (N-100)시간이라 모두 10~14보다 먼저 공개됐다.
--   모두 지난 회차 하나(10-15 19:00, 예매 마감 10-14)가 있다.
--   101~112는 판매 예정 회차(2027-01-05 19:00)도 있다. 오픈은 11-01 00:00 + ((N-101) % 6)일이라 두 개씩 같다.
--   그래서 101~112의 기간은 10-15 ~ 2027-01-05이고, 113~125의 기간은 10-15 하루다.
--
-- 전체 상태 선택 시 LATEST(30개): 14, 13, 12, 11, 10, 125, 124, …, 101
-- 기본 목록은 15개(12, 11, 10, 112, 111, …, 101)다(이슈 #382).
-- BOOKING_OPEN_ASC: 12(10-25), 107·101(11-01), 108·102, 109·103, 110·104, 111·105(11-05 00:00), 11(11-05 14:00),
--                   112·106(11-06), 10(11-10), 그다음 오픈할 회차 없음 125, 124, …, 113, 14, 13

INSERT INTO p_performances (id, seller_id, venue_id, title, genre, age_rating, running_time, cast_info, description,
                            poster_url, publication_status, operation_status, published_at, version,
                            created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
SELECT n, 503, 1, '대량 공연 ' || n, 'PLAY', 'ALL', 90, NULL, NULL,
       NULL, 'PUBLISHED', 'ACTIVE', TIMESTAMP '2026-10-01 00:00:00' + (n - 100) * INTERVAL '1 hour', 1,
       '2026-09-30 09:00:00', 'SYSTEM', '2026-10-01 00:00:00', 'SYSTEM', NULL, NULL
FROM generate_series(101, 125) AS n;

INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         seat_setup_attempts, version, created_at, created_by, updated_at, updated_by,
                         deleted_at, deleted_by)
SELECT 1000 + n, n, '2026-10-15 19:00:00', '2026-10-01 14:00:00', '2026-10-14 18:00:00', 'ACTIVE', 'READY', 1,
       'req-many-past-' || n, FALSE, 1, 0, '2026-09-30 09:00:00', 'SYSTEM', '2026-09-30 09:00:00', 'SYSTEM', NULL, NULL
FROM generate_series(101, 125) AS n;

INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         seat_setup_attempts, version, created_at, created_by, updated_at, updated_by,
                         deleted_at, deleted_by)
SELECT 2000 + n, n, '2027-01-05 19:00:00', TIMESTAMP '2026-11-01 00:00:00' + ((n - 101) % 6) * INTERVAL '1 day',
       '2027-01-05 18:00:00', 'ACTIVE', 'READY', 1,
       'req-many-next-' || n, FALSE, 1, 0, '2026-09-30 09:00:00', 'SYSTEM', '2026-09-30 09:00:00', 'SYSTEM', NULL, NULL
FROM generate_series(101, 112) AS n;
