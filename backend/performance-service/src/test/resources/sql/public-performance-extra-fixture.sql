-- public-performance-fixture.sql 위에 더하는 경계 사례. 기준 시각은 2026-11-01 12:00(KST)이다.
--
--   15 PLAY    서울 '연극 Only Deleted'  공개 10-18, 삭제 회차(12-10)만 있음 → 목록에 남고 period=null
--   16 PLAY    서울 '연극 Only Canceled' 공개 10-19, 취소 회차(12-11)만 있음 → 목록에 남고 period=null
--   17 CONCERT 서울 '콘서트 Tie'         공개 10-17, 활성 회차 12-25(오픈 11-05, 11과 같은 오픈 시각)
--
-- LATEST: 14, 13, 12, 11, 10, 16, 15, 17
-- BOOKING_OPEN_ASC: 12(10-25), 17·11(11-05, 동률은 ID 역순), 10(11-10), 그다음 오픈할 회차 없음 16, 15, 14, 13

INSERT INTO p_performances (id, seller_id, venue_id, title, genre, age_rating, running_time, cast_info, description,
                            poster_url, publication_status, operation_status, published_at, version,
                            created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (15, 502, 1, '연극 Only Deleted', 'PLAY', 'ALL', 90, NULL, NULL,
        NULL, 'PUBLISHED', 'ACTIVE', '2026-10-18 10:00:00', 1,
        '2026-10-17 09:00:00', 'SYSTEM', '2026-10-18 10:00:00', 'SYSTEM', NULL, NULL),
       (16, 502, 1, '연극 Only Canceled', 'PLAY', 'ALL', 90, NULL, NULL,
        NULL, 'PUBLISHED', 'ACTIVE', '2026-10-19 10:00:00', 1,
        '2026-10-17 09:00:00', 'SYSTEM', '2026-10-19 10:00:00', 'SYSTEM', NULL, NULL),
       (17, 502, 1, '콘서트 Tie', 'CONCERT', 'ALL', 90, NULL, NULL,
        NULL, 'PUBLISHED', 'ACTIVE', '2026-10-17 10:00:00', 1,
        '2026-10-16 09:00:00', 'SYSTEM', '2026-10-17 10:00:00', 'SYSTEM', NULL, NULL);

INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         seat_setup_attempts, version, created_at, created_by, updated_at, updated_by,
                         deleted_at, deleted_by)
VALUES (11, 15, '2026-12-10 19:00:00', '2026-10-30 14:00:00', '2026-12-10 18:00:00', 'ACTIVE', 'READY', 1,
        'req-11', FALSE, 1, 1, '2026-10-17 09:00:00', 'SYSTEM', '2026-10-28 09:00:00', 'SYSTEM',
        '2026-10-28 09:00:00', 'SYSTEM'),
       (12, 16, '2026-12-11 19:00:00', '2026-10-30 14:00:00', '2026-12-11 18:00:00', 'CANCELED', 'READY', 1,
        'req-12', FALSE, 1, 1, '2026-10-17 09:00:00', 'SYSTEM', '2026-10-28 09:00:00', 'SYSTEM', NULL, NULL),
       (13, 17, '2026-12-25 19:00:00', '2026-11-05 14:00:00', '2026-12-25 18:00:00', 'ACTIVE', 'READY', 1,
        'req-13', FALSE, 1, 0, '2026-10-16 09:00:00', 'SYSTEM', '2026-10-16 09:00:00', 'SYSTEM', NULL, NULL);
