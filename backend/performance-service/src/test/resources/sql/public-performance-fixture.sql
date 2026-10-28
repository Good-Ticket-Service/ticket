-- 공개 공연 조회 테스트 데이터. 기준 시각은 2026-11-01 12:00(KST, PerformanceIntegrationTest.NOW)이다.
--
-- 공개 목록 대상(LATEST 순서: 14, 13, 12, 11, 10)
--   10 MUSICAL 서울 '뮤지컬 Alpha'   공개 10-20, 활성 회차 12-01·12-02(오픈 11-10). 취소 회차 11-25, 삭제 회차 12-31은 기간에서 제외
--   11 CONCERT 부산 '콘서트 Beta'    공개 10-21, 회차 12-20(오픈 11-05)
--   12 PLAY    서울 '연극 Gamma'     공개 10-22, 회차 2027-01-10(오픈 10-25, 이미 판매 중)
--   13 SPORTS  서울 '스포츠 Delta'   공개 10-23, 회차 없음(period=null, 포스터 없음)
--   14 MUSICAL 부산 '뮤지컬 Epsilon' 공개 10-24, 회차 10-30(예매 마감 지남)
-- 공개 목록 제외
--   20 DRAFT, 21 공개 후 삭제, 22 공개 후 취소(상세는 조회됨), 23 삭제된 공연장의 공개 공연

INSERT INTO p_venues (id, name, address, region, version, created_at, created_by, updated_at, updated_by,
                      deleted_at, deleted_by)
VALUES (1, '서울 공연장', '서울특별시 중구 예시로 10', '서울', 0,
        '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL),
       (2, '부산 공연장', '부산광역시 해운대구 예시로 20', '부산', 0,
        '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL),
       (3, '삭제된 공연장', '서울특별시 종로구 예시로 30', '서울', 0,
        '2026-10-01 09:00:00', 'SYSTEM', '2026-10-02 09:00:00', 'SYSTEM', '2026-10-02 09:00:00', 'SYSTEM');

INSERT INTO p_venue_seat_grades (id, venue_id, name, created_at, created_by, updated_at, updated_by,
                                 deleted_at, deleted_by)
VALUES (1, 1, 'VIP', '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL),
       (2, 1, 'R', '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL),
       (3, 2, 'VIP', '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL),
       (4, 1, 'OLD', '2026-10-01 09:00:00', 'SYSTEM', '2026-10-02 09:00:00', 'SYSTEM', '2026-10-02 09:00:00',
        'SYSTEM'),
       (5, 3, 'R', '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL);

INSERT INTO p_performances (id, seller_id, venue_id, title, genre, age_rating, running_time, cast_info, description,
                            poster_url, publication_status, operation_status, published_at, version,
                            created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (10, 501, 1, '뮤지컬 Alpha', 'MUSICAL', '12', 150, '배우 A, 배우 B', '공연 상세 안내',
        'https://example.com/10.jpg', 'PUBLISHED', 'ACTIVE', '2026-10-20 10:00:00', 3,
        '2026-10-19 09:00:00', 'SYSTEM', '2026-10-20 10:00:00', 'SYSTEM', NULL, NULL),
       (11, 501, 2, '콘서트 Beta', 'CONCERT', 'ALL', 120, NULL, '콘서트 안내',
        'https://example.com/11.jpg', 'PUBLISHED', 'ACTIVE', '2026-10-21 10:00:00', 1,
        '2026-10-20 09:00:00', 'SYSTEM', '2026-10-21 10:00:00', 'SYSTEM', NULL, NULL),
       (12, 502, 1, '연극 Gamma', 'PLAY', '15', 100, NULL, '연극 안내',
        'https://example.com/12.jpg', 'PUBLISHED', 'ACTIVE', '2026-10-22 10:00:00', 1,
        '2026-10-21 09:00:00', 'SYSTEM', '2026-10-22 10:00:00', 'SYSTEM', NULL, NULL),
       (13, 502, 1, '스포츠 Delta', 'SPORTS', 'ALL', 180, NULL, NULL,
        NULL, 'PUBLISHED', 'ACTIVE', '2026-10-23 10:00:00', 1,
        '2026-10-22 09:00:00', 'SYSTEM', '2026-10-23 10:00:00', 'SYSTEM', NULL, NULL),
       (14, 501, 2, '뮤지컬 Epsilon', 'MUSICAL', '12', 110, NULL, '지난 공연',
        'https://example.com/14.jpg', 'PUBLISHED', 'ACTIVE', '2026-10-24 10:00:00', 1,
        '2026-10-01 09:00:00', 'SYSTEM', '2026-10-24 10:00:00', 'SYSTEM', NULL, NULL),
       (20, 501, 1, '뮤지컬 Draft', 'MUSICAL', '12', 120, NULL, NULL,
        NULL, 'DRAFT', 'ACTIVE', NULL, 0,
        '2026-10-25 09:00:00', 'SYSTEM', '2026-10-25 09:00:00', 'SYSTEM', NULL, NULL),
       (21, 501, 1, '뮤지컬 Deleted', 'MUSICAL', '12', 120, NULL, '삭제된 공연',
        'https://example.com/21.jpg', 'PUBLISHED', 'ACTIVE', '2026-10-25 10:00:00', 1,
        '2026-10-25 09:00:00', 'SYSTEM', '2026-10-26 09:00:00', 'SYSTEM', '2026-10-26 09:00:00', 'SYSTEM'),
       (22, 501, 1, '뮤지컬 Canceled', 'MUSICAL', '19', 120, NULL, '취소된 공연',
        'https://example.com/22.jpg', 'PUBLISHED', 'CANCELED', '2026-10-26 10:00:00', 2,
        '2026-10-25 09:00:00', 'SYSTEM', '2026-10-27 09:00:00', 'SYSTEM', NULL, NULL),
       (23, 501, 3, '뮤지컬 Lost Venue', 'MUSICAL', '12', 120, NULL, '삭제된 공연장의 공연',
        'https://example.com/23.jpg', 'PUBLISHED', 'ACTIVE', '2026-10-27 10:00:00', 1,
        '2026-10-26 09:00:00', 'SYSTEM', '2026-10-27 10:00:00', 'SYSTEM', NULL, NULL);

-- 공연 10: 미삭제 가격 2개 + 삭제된 가격 1개(같은 등급의 옛 가격) + 삭제된 등급의 가격 1개
INSERT INTO p_performance_grade_prices (id, performance_id, venue_seat_grade_id, price,
                                        created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (1, 10, 1, 150000, '2026-10-19 09:00:00', 'SYSTEM', '2026-10-19 09:00:00', 'SYSTEM', NULL, NULL),
       (2, 10, 2, 100000, '2026-10-19 09:00:00', 'SYSTEM', '2026-10-19 09:00:00', 'SYSTEM', NULL, NULL),
       (3, 10, 1, 999000, '2026-10-18 09:00:00', 'SYSTEM', '2026-10-19 09:00:00', 'SYSTEM', '2026-10-19 09:00:00',
        'SYSTEM'),
       (4, 10, 4, 50000, '2026-10-18 09:00:00', 'SYSTEM', '2026-10-18 09:00:00', 'SYSTEM', NULL, NULL),
       (5, 11, 3, 120000, '2026-10-20 09:00:00', 'SYSTEM', '2026-10-20 09:00:00', 'SYSTEM', NULL, NULL),
       (6, 22, 1, 130000, '2026-10-25 09:00:00', 'SYSTEM', '2026-10-25 09:00:00', 'SYSTEM', NULL, NULL);

INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         seat_setup_attempts, version, created_at, created_by, updated_at, updated_by,
                         deleted_at, deleted_by)
VALUES (1, 10, '2026-12-01 19:00:00', '2026-11-10 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 1,
        'req-1', FALSE, 1, 0, '2026-10-19 09:00:00', 'SYSTEM', '2026-10-19 09:00:00', 'SYSTEM', NULL, NULL),
       (2, 10, '2026-12-02 19:00:00', '2026-11-10 14:00:00', '2026-12-02 18:00:00', 'ACTIVE', 'READY', 1,
        'req-2', FALSE, 1, 0, '2026-10-19 09:00:00', 'SYSTEM', '2026-10-19 09:00:00', 'SYSTEM', NULL, NULL),
       -- 취소 회차: 기간·날짜 필터·예매 오픈 정렬에서 빠진다. 오픈 시각이 가장 일러도 무시된다
       (3, 10, '2026-11-25 19:00:00', '2026-10-02 14:00:00', '2026-11-25 18:00:00', 'CANCELED', 'READY', 1,
        'req-3', FALSE, 1, 1, '2026-10-19 09:00:00', 'SYSTEM', '2026-10-28 09:00:00', 'SYSTEM', NULL, NULL),
       -- 삭제 회차: 기간·날짜 필터에서 빠진다
       (4, 10, '2026-12-31 19:00:00', '2026-10-02 14:00:00', '2026-12-31 18:00:00', 'ACTIVE', 'READY', 1,
        'req-4', FALSE, 1, 1, '2026-10-19 09:00:00', 'SYSTEM', '2026-10-28 09:00:00', 'SYSTEM',
        '2026-10-28 09:00:00', 'SYSTEM'),
       (5, 11, '2026-12-20 19:00:00', '2026-11-05 14:00:00', '2026-12-20 18:00:00', 'ACTIVE', 'READY', 1,
        'req-5', FALSE, 1, 0, '2026-10-20 09:00:00', 'SYSTEM', '2026-10-20 09:00:00', 'SYSTEM', NULL, NULL),
       (6, 12, '2027-01-10 19:00:00', '2026-10-25 14:00:00', '2027-01-10 18:00:00', 'ACTIVE', 'READY', 1,
        'req-6', FALSE, 1, 0, '2026-10-21 09:00:00', 'SYSTEM', '2026-10-21 09:00:00', 'SYSTEM', NULL, NULL),
       (7, 14, '2026-10-30 19:00:00', '2026-10-01 14:00:00', '2026-10-30 18:00:00', 'ACTIVE', 'READY', 1,
        'req-7', FALSE, 1, 0, '2026-10-01 09:00:00', 'SYSTEM', '2026-10-01 09:00:00', 'SYSTEM', NULL, NULL),
       (8, 20, '2026-12-05 19:00:00', '2026-11-02 14:00:00', '2026-12-05 18:00:00', 'ACTIVE', 'PENDING', 1,
        'req-8', FALSE, 0, 0, '2026-10-25 09:00:00', 'SYSTEM', '2026-10-25 09:00:00', 'SYSTEM', NULL, NULL),
       (9, 21, '2026-12-06 19:00:00', '2026-11-02 14:00:00', '2026-12-06 18:00:00', 'ACTIVE', 'READY', 1,
        'req-9', FALSE, 1, 0, '2026-10-25 09:00:00', 'SYSTEM', '2026-10-25 09:00:00', 'SYSTEM', NULL, NULL),
       (10, 23, '2026-12-07 19:00:00', '2026-11-02 14:00:00', '2026-12-07 18:00:00', 'ACTIVE', 'READY', 1,
        'req-10', FALSE, 1, 0, '2026-10-26 09:00:00', 'SYSTEM', '2026-10-26 09:00:00', 'SYSTEM', NULL, NULL);
