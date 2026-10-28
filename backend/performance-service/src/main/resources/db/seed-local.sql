-- performance-service 로컬 실행용 시드 데이터. 등록 API가 생기기 전까지 공개 조회를 이 데이터로 확인한다.
-- local 프로필에서 Hibernate가 테이블을 만든 뒤 자동으로 실행된다(config-repo/performance-service-local.yml).
-- 직접 넣을 때는 빈 테이블에 performance_app 계정으로 실행한다. 이미 데이터가 있으면 ID가 겹쳐 실패한다.
-- 회차 시각은 실행한 날 0시(LOCALTIMESTAMP 기준) 상대값이라 언제 실행해도 판매 전·판매 중·종료 회차가 고르게 나온다.
--
-- 공연별 조회 시나리오(공개 목록에는 1~5만 나온다)
--   1 MUSICAL 서울 판매 중 회차 3개(판매 제외 좌석 1개)    2 CONCERT 부산 예매 오픈 전
--   3 PLAY 대구 예매 오픈 전, 취소·삭제 회차는 기간에서 제외  4 SPORTS 서울 회차 없음(period=null)
--   5 MUSICAL 부산 공연 종료(BOOKING_OPEN_ASC에서 마지막)    6 DRAFT: 목록 제외, 상세 404
--   7 공개 후 삭제: 목록 제외, 상세 404                      8 공개 후 취소: 목록 제외, 상세 200

-- 공연장
INSERT INTO p_venues (id, name, address, region, version, created_at, created_by, updated_at, updated_by)
VALUES (1, '서울 아트센터', '서울특별시 중구 예시로 10', '서울', 0, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (2, '부산 문화회관', '부산광역시 해운대구 예시로 20', '부산', 0, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (3, '대구 소극장', '대구광역시 중구 예시로 30', '대구', 0, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM');

-- 공연장 좌석 등급
INSERT INTO p_venue_seat_grades (id, venue_id, name, created_at, created_by, updated_at, updated_by)
VALUES (1, 1, 'VIP', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (2, 1, 'R', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (3, 1, 'S', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (4, 2, 'VIP', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (5, 2, 'R', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (6, 3, 'R', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (7, 3, 'S', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM');

-- 공연장 좌석 80석. 열마다 10석이며 등급은 열 단위로 정한다. ID는 DB가 만든다.
-- 서울: A구역 1열 VIP, A구역 2열 R, B구역 1·2열 S
INSERT INTO p_venue_seats (venue_id, venue_seat_grade_id, section, row_label, seat_number,
                           created_at, created_by, updated_at, updated_by)
SELECT 1, layout.grade_id, layout.section, layout.row_label, seat.number,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
FROM (VALUES (1, 'A', '1'), (2, 'A', '2'), (3, 'B', '1'), (3, 'B', '2')) AS layout (grade_id, section, row_label)
         CROSS JOIN generate_series(1, 10) AS seat (number);

-- 부산: A구역 1열 VIP, 2열 R
INSERT INTO p_venue_seats (venue_id, venue_seat_grade_id, section, row_label, seat_number,
                           created_at, created_by, updated_at, updated_by)
SELECT 2, layout.grade_id, layout.section, layout.row_label, seat.number,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
FROM (VALUES (4, 'A', '1'), (5, 'A', '2')) AS layout (grade_id, section, row_label)
         CROSS JOIN generate_series(1, 10) AS seat (number);

-- 대구: A구역 1열 R, 2열 S
INSERT INTO p_venue_seats (venue_id, venue_seat_grade_id, section, row_label, seat_number,
                           created_at, created_by, updated_at, updated_by)
SELECT 3, layout.grade_id, layout.section, layout.row_label, seat.number,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
FROM (VALUES (6, 'A', '1'), (7, 'A', '2')) AS layout (grade_id, section, row_label)
         CROSS JOIN generate_series(1, 10) AS seat (number);

-- 공연. 판매자 ID 1001은 회원 서비스와 연결하지 않은 예시 값이다.
INSERT INTO p_performances (id, seller_id, venue_id, title, genre, age_rating, running_time, cast_info, description,
                            poster_url, publication_status, operation_status, published_at, version,
                            created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (1, 1001, 1, '오페라의 유령', 'MUSICAL', '12', 150, '배우 A, 배우 B', '가면 뒤에 숨은 사랑 이야기',
        'https://example.com/posters/1.jpg', 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '10 days', 1,
        LOCALTIMESTAMP - INTERVAL '12 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '10 days', 'SYSTEM', NULL, NULL),
       (2, 1001, 2, '겨울 밤 콘서트', 'CONCERT', 'ALL', 120, '가수 C', '해운대에서 열리는 겨울 콘서트',
        'https://example.com/posters/2.jpg', 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '5 days', 1,
        LOCALTIMESTAMP - INTERVAL '6 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '5 days', 'SYSTEM', NULL, NULL),
       (3, 1002, 3, '햄릿', 'PLAY', '15', 140, '배우 D', '셰익스피어 4대 비극',
        'https://example.com/posters/3.jpg', 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '3 days', 1,
        LOCALTIMESTAMP - INTERVAL '4 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '3 days', 'SYSTEM', NULL, NULL),
       (4, 1002, 1, '농구 올스타전', 'SPORTS', 'ALL', 180, NULL, '회차 등록 전인 공개 공연',
        NULL, 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '1 day', 1,
        LOCALTIMESTAMP - INTERVAL '2 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '1 day', 'SYSTEM', NULL, NULL),
       (5, 1001, 2, '지난 뮤지컬 갈라', 'MUSICAL', '12', 100, '배우 E', '공연이 끝난 공개 공연',
        'https://example.com/posters/5.jpg', 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '60 days', 1,
        LOCALTIMESTAMP - INTERVAL '61 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '60 days', 'SYSTEM', NULL, NULL),
       (6, 1001, 1, '준비 중인 뮤지컬', 'MUSICAL', '12', 120, NULL, NULL,
        NULL, 'DRAFT', 'ACTIVE', NULL, 0,
        LOCALTIMESTAMP - INTERVAL '1 day', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '1 day', 'SYSTEM', NULL, NULL),
       (7, 1002, 3, '삭제된 콘서트', 'CONCERT', 'ALL', 90, NULL, '공개 후 삭제된 공연',
        'https://example.com/posters/7.jpg', 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '20 days', 1,
        LOCALTIMESTAMP - INTERVAL '21 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '2 days', 'SYSTEM',
        LOCALTIMESTAMP - INTERVAL '2 days', 'SYSTEM'),
       (8, 1001, 1, '취소된 콘서트', 'CONCERT', '19', 110, '가수 F', '공연 사정으로 모든 회차가 취소됐습니다',
        'https://example.com/posters/8.jpg', 'PUBLISHED', 'CANCELED', LOCALTIMESTAMP - INTERVAL '15 days', 2,
        LOCALTIMESTAMP - INTERVAL '16 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '1 day', 'SYSTEM', NULL, NULL);

-- 공연별 등급 가격
INSERT INTO p_performance_grade_prices (id, performance_id, venue_seat_grade_id, price,
                                        created_at, created_by, updated_at, updated_by)
VALUES (1, 1, 1, 170000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (2, 1, 2, 140000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (3, 1, 3, 110000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (4, 2, 4, 132000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (5, 2, 5, 99000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (6, 3, 6, 60000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (7, 3, 7, 40000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (8, 4, 1, 90000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (9, 4, 2, 70000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (10, 4, 3, 50000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (11, 5, 4, 120000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (12, 5, 5, 90000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (13, 6, 1, 150000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (14, 7, 6, 55000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (15, 8, 1, 154000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (16, 8, 2, 121000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (17, 8, 3, 99000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM');

-- 회차. day는 오늘 0시 기준 날짜 차이, hour는 시작 시각(시)이다. 예매 마감은 시작 1시간 전이다.
INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         canceled_at, cancel_reason, seat_setup_attempts, version,
                         created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
SELECT s.id, s.performance_id,
       date_trunc('day', LOCALTIMESTAMP) + make_interval(days => s.day, hours => s.hour),
       date_trunc('day', LOCALTIMESTAMP) + make_interval(days => s.open_day, hours => 14),
       date_trunc('day', LOCALTIMESTAMP) + make_interval(days => s.day, hours => s.hour - 1),
       s.operation_status, s.seat_setup_status, 1, gen_random_uuid()::text, FALSE,
       CASE WHEN s.operation_status = 'CANCELED' THEN LOCALTIMESTAMP - INTERVAL '1 day' END,
       CASE WHEN s.operation_status = 'CANCELED' THEN '공연 사정으로 취소' END,
       CASE WHEN s.seat_setup_status = 'PENDING' THEN 0 ELSE 1 END, 0,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM',
       CASE WHEN s.deleted THEN LOCALTIMESTAMP - INTERVAL '1 day' END,
       CASE WHEN s.deleted THEN 'SYSTEM' END
FROM (VALUES
          -- 1: 판매 중(예매 오픈 어제)
          (1, 1, 30, 19, -1, 'ACTIVE', 'READY', FALSE),
          (2, 1, 31, 14, -1, 'ACTIVE', 'READY', FALSE),
          (3, 1, 31, 19, -1, 'ACTIVE', 'READY', FALSE),
          -- 2: 예매 오픈 10일 뒤
          (4, 2, 60, 19, 10, 'ACTIVE', 'READY', FALSE),
          -- 3: 예매 오픈 20일 뒤. 취소 회차(80일)와 삭제 회차(120일)는 공연 기간에서 빠진다
          (5, 3, 90, 19, 20, 'ACTIVE', 'READY', FALSE),
          (6, 3, 92, 15, 20, 'ACTIVE', 'READY', FALSE),
          (7, 3, 80, 19, 20, 'CANCELED', 'READY', FALSE),
          (8, 3, 120, 19, 20, 'ACTIVE', 'READY', TRUE),
          -- 5: 이미 끝난 회차
          (9, 5, -10, 19, -40, 'ACTIVE', 'READY', FALSE),
          -- 6: 초안 공연의 좌석 준비 중 회차
          (10, 6, 100, 19, 50, 'ACTIVE', 'PENDING', FALSE),
          -- 7: 삭제된 공연의 회차
          (11, 7, 40, 19, 5, 'ACTIVE', 'READY', FALSE),
          -- 8: 취소된 공연의 취소 회차
          (12, 8, 45, 19, -5, 'CANCELED', 'READY', FALSE)
     ) AS s (id, performance_id, day, hour, open_day, operation_status, seat_setup_status, deleted);

-- 공연 1의 공통 판매 제외 좌석: 서울 A구역 1열 1번
INSERT INTO p_performance_seat_policies (id, performance_id, venue_seat_id, exclusion_reason,
                                         created_at, created_by, updated_at, updated_by)
SELECT 1, 1, seat.id, '운영석', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
FROM p_venue_seats seat
WHERE seat.venue_id = 1 AND seat.section = 'A' AND seat.row_label = '1' AND seat.seat_number = 1;

-- ID를 직접 넣은 테이블은 다음 자동 ID가 겹치지 않게 시퀀스를 맞춘다
SELECT setval(pg_get_serial_sequence('p_venues', 'id'), (SELECT max(id) FROM p_venues));
SELECT setval(pg_get_serial_sequence('p_venue_seat_grades', 'id'), (SELECT max(id) FROM p_venue_seat_grades));
SELECT setval(pg_get_serial_sequence('p_performances', 'id'), (SELECT max(id) FROM p_performances));
SELECT setval(pg_get_serial_sequence('p_performance_grade_prices', 'id'), (SELECT max(id) FROM p_performance_grade_prices));
SELECT setval(pg_get_serial_sequence('p_schedules', 'id'), (SELECT max(id) FROM p_schedules));
SELECT setval(pg_get_serial_sequence('p_performance_seat_policies', 'id'), (SELECT max(id) FROM p_performance_seat_policies));
