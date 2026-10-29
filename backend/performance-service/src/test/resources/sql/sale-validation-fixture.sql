-- 판매 검증 전용 fixture. 다른 기능의 fixture에 의존하지 않는다. 기준 시각은 2026-11-01 12:00(KST)이다.
-- 공연장 1: 좌석 1(A-1-1 VIP), 2(A-1-2 VIP, 공연 10의 판매 제외), 3(A-2-1 R), 4(A-2-2 삭제 등급), 6(A-1-3 삭제 좌석)
-- 공연장 2: 좌석 5. 공연장 3: 삭제된 공연장

INSERT INTO p_venues (id, name, address, region, version, created_at, created_by, updated_at, updated_by, deleted_at,
                      deleted_by)
VALUES (1, '검증 공연장', '서울 주소', '서울', 0, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (2, '다른 공연장', '부산 주소', '부산', 0, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (3, '삭제 공연장', '대구 주소', '대구', 0, '2026-10-01', 'SYSTEM', '2026-10-02', 'SYSTEM', '2026-10-02',
        'SYSTEM');

INSERT INTO p_venue_seat_grades (id, venue_id, name, created_at, created_by, updated_at, updated_by, deleted_at,
                                 deleted_by)
VALUES (1, 1, 'VIP', '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (2, 1, 'R', '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (3, 1, '삭제 등급', '2026-10-01', 'SYSTEM', '2026-10-02', 'SYSTEM', '2026-10-02', 'SYSTEM'),
       (4, 2, 'VIP', '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (5, 3, 'R', '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL);

INSERT INTO p_venue_seats (id, venue_id, venue_seat_grade_id, section, row_label, seat_number, created_at, created_by,
                           updated_at, updated_by, deleted_at, deleted_by)
VALUES (1, 1, 1, 'A', '1', 1, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (2, 1, 1, 'A', '1', 2, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (3, 1, 2, 'A', '2', 1, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (4, 1, 3, 'A', '2', 2, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (5, 2, 4, 'A', '1', 1, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL),
       (6, 1, 1, 'A', '1', 3, '2026-10-01', 'SYSTEM', '2026-10-02', 'SYSTEM', '2026-10-02', 'SYSTEM'),
       (7, 3, 5, 'A', '1', 1, '2026-10-01', 'SYSTEM', '2026-10-01', 'SYSTEM', NULL, NULL);

-- 10 판매 공연(version 3), 11 초안, 12 취소, 13 삭제, 14 삭제된 공연장, 15 가격 누락(불변식 위반)
INSERT INTO p_performances (id, seller_id, venue_id, title, genre, age_rating, running_time, publication_status,
                            operation_status, published_at, version, created_at, created_by, updated_at, updated_by,
                            deleted_at, deleted_by)
VALUES (10, 501, 1, '판매 공연', 'MUSICAL', '12', 120, 'PUBLISHED', 'ACTIVE', '2026-10-20', 3, '2026-10-19', 'SYSTEM',
        '2026-10-20', 'SYSTEM', NULL, NULL),
       (11, 502, 1, '초안 공연', 'MUSICAL', '12', 120, 'DRAFT', 'ACTIVE', NULL, 0, '2026-10-19', 'SYSTEM',
        '2026-10-19', 'SYSTEM', NULL, NULL),
       (12, 503, 1, '취소 공연', 'MUSICAL', '12', 120, 'PUBLISHED', 'CANCELED', '2026-10-20', 4, '2026-10-19', 'SYSTEM',
        '2026-10-25', 'SYSTEM', NULL, NULL),
       (13, 504, 1, '삭제 공연', 'MUSICAL', '12', 120, 'PUBLISHED', 'ACTIVE', '2026-10-20', 1, '2026-10-19', 'SYSTEM',
        '2026-10-25', 'SYSTEM', '2026-10-25', 'SYSTEM'),
       (14, 505, 3, '삭제 공연장 공연', 'MUSICAL', '12', 120, 'PUBLISHED', 'ACTIVE', '2026-10-20', 1, '2026-10-19',
        'SYSTEM', '2026-10-20', 'SYSTEM', NULL, NULL),
       (15, 506, 1, '가격 누락 공연', 'MUSICAL', '12', 120, 'PUBLISHED', 'ACTIVE', '2026-10-20', 1, '2026-10-19',
        'SYSTEM', '2026-10-20', 'SYSTEM', NULL, NULL);

-- 공연 10: VIP 150000, R 100000. 삭제된 이전 R 가격 행은 무시된다. 공연 15: VIP만 있고 R 가격이 없다
INSERT INTO p_performance_grade_prices (id, performance_id, venue_seat_grade_id, price, created_at, created_by,
                                        updated_at, updated_by, deleted_at, deleted_by)
VALUES (1, 10, 1, 150000, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (2, 10, 2, 100000, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (3, 10, 2, 90000, '2026-10-18', 'SYSTEM', '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM'),
       (4, 15, 1, 150000, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL);

-- 공연 10의 판매 제외: 좌석 2. 삭제된 제외 행(좌석 3)은 무시된다
INSERT INTO p_performance_seat_policies (id, performance_id, venue_seat_id, exclusion_reason, created_at, created_by,
                                         updated_at, updated_by, deleted_at, deleted_by)
VALUES (1, 10, 2, '운영석', '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (2, 10, 3, '이전 제외', '2026-10-18', 'SYSTEM', '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM');

-- 100 판매 중(구성 버전 2), 101 회차 취소, 102 판매 중지, 103 좌석 준비 중, 104 오픈 전, 105 마감,
-- 106 초안 공연, 107 취소 공연, 108 삭제 회차, 109 삭제 공연, 110 삭제된 공연장, 111 가격 누락 공연
INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         suspension_reason, canceled_at, cancel_reason, seat_setup_attempts, version, created_at,
                         created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (100, 10, '2026-12-01 19:00:00', '2026-10-25 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 2, 'req-100',
        FALSE, NULL, NULL, NULL, 1, 1, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (101, 10, '2026-12-02 19:00:00', '2026-10-25 14:00:00', '2026-12-02 18:00:00', 'CANCELED', 'READY', 1,
        'req-101', FALSE, NULL, '2026-10-30 10:00:00', '공연장 사정', 1, 1, '2026-10-19', 'SYSTEM', '2026-10-30',
        'SYSTEM', NULL, NULL),
       (102, 10, '2026-12-03 19:00:00', '2026-10-25 14:00:00', '2026-12-03 18:00:00', 'ACTIVE', 'READY', 1, 'req-102',
        TRUE, '운영 점검', NULL, NULL, 1, 1, '2026-10-19', 'SYSTEM', '2026-10-30', 'SYSTEM', NULL, NULL),
       (103, 10, '2026-12-04 19:00:00', '2026-10-25 14:00:00', '2026-12-04 18:00:00', 'ACTIVE', 'PENDING', 1,
        'req-103', FALSE, NULL, NULL, NULL, 0, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (104, 10, '2026-12-05 19:00:00', '2026-11-10 14:00:00', '2026-12-05 18:00:00', 'ACTIVE', 'READY', 1, 'req-104',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (105, 10, '2026-10-30 19:00:00', '2026-10-01 14:00:00', '2026-10-30 18:00:00', 'ACTIVE', 'READY', 1, 'req-105',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-09-30', 'SYSTEM', '2026-09-30', 'SYSTEM', NULL, NULL),
       (106, 11, '2026-12-01 19:00:00', '2026-10-25 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 1, 'req-106',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (107, 12, '2026-12-01 19:00:00', '2026-10-25 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 1, 'req-107',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (108, 10, '2026-12-06 19:00:00', '2026-10-25 14:00:00', '2026-12-06 18:00:00', 'ACTIVE', 'READY', 1, 'req-108',
        FALSE, NULL, NULL, NULL, 1, 1, '2026-10-19', 'SYSTEM', '2026-10-28', 'SYSTEM', '2026-10-28', 'SYSTEM'),
       (109, 13, '2026-12-01 19:00:00', '2026-10-25 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 1, 'req-109',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (110, 14, '2026-12-01 19:00:00', '2026-10-25 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 1, 'req-110',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL),
       (111, 15, '2026-12-01 19:00:00', '2026-10-25 14:00:00', '2026-12-01 18:00:00', 'ACTIVE', 'READY', 1, 'req-111',
        FALSE, NULL, NULL, NULL, 1, 0, '2026-10-19', 'SYSTEM', '2026-10-19', 'SYSTEM', NULL, NULL);
