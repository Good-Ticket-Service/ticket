-- performance-service 부하 측정용 시드(#173). 좌석 서비스의 부하용 회차 좌석 시드와 짝이다.
-- local 프로필에서 seed-local.sql 다음에 자동으로 실행된다(config-repo/performance-service-local.yml).
-- 직접 넣을 때는 seed-local.sql을 넣은 DB에 performance_app 계정으로 실행한다. 이미 들어 있으면 ID가 겹쳐 실패한다.
--
-- 용도: 좌석 선점 부하 측정. 좌석 선점은 이 서비스의 판매 검증을 거치므로, 좌석 서비스 시드와 같은 ID·등급·가격·판매 제외로
--       공연장·공연·회차를 넣어야 선점이 끝까지 통과한다. 공개 목록에 함께 나오므로 이름에 [부하]를 붙여 구분한다.
--
-- 좌석 서비스 시드와 맞춰야 하는 값(reservation-service db/seed-load.sql, SeatSeedLoadSqlIntegrationTest)
--   - 회차 900, 좌석 구성 버전(seat_config_version) 1
--   - 배치 1,000석: 구역 A~D × 열 1~10 × 좌석 1~25. 등급은 열 단위로 1~3열 VIP, 4~7열 R, 8~10열 S
--   - 공연장 좌석 ID = 100000 + 구역 순번(A=0 … D=3) × 250 + (열 - 1) × 25 + 좌석 번호  예) A-1-1 = 100001, D-10-25 = 101000
--   - 등급 가격: VIP 150,000원, R 120,000원, S 90,000원
--   - 판매 제외: 구역마다 1열 1번(운영석) 4석
--   하나를 바꾸면 두 시드와 두 시드 테스트를 함께 고친다.
--
-- ID는 local 시드(1~)와 겹치지 않게 900번대, 공연장 좌석은 100001부터 쓴다.

-- 공연장
INSERT INTO p_venues (id, name, address, region, version, created_at, created_by, updated_at, updated_by)
VALUES (900, '[부하] 측정 공연장', '서울특별시 중구 측정로 900', '서울', 0, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM');

-- 공연장 좌석 등급
INSERT INTO p_venue_seat_grades (id, venue_id, name, created_at, created_by, updated_at, updated_by)
VALUES (900, 900, 'VIP', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (901, 900, 'R', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (902, 900, 'S', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM');

-- 공연장 좌석 1,000석. ID는 위 공식으로 직접 넣는다(좌석 서비스 시드가 같은 공식을 쓴다)
INSERT INTO p_venue_seats (id, venue_id, venue_seat_grade_id, section, row_label, seat_number,
                           created_at, created_by, updated_at, updated_by)
SELECT 100000 + section.k * 250 + (seat_row.number - 1) * 25 + seat.number,
       900,
       CASE WHEN seat_row.number <= 3 THEN 900 WHEN seat_row.number <= 7 THEN 901 ELSE 902 END,
       section.name, seat_row.number::text, seat.number,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
FROM (VALUES (0, 'A'), (1, 'B'), (2, 'C'), (3, 'D')) AS section (k, name)
         CROSS JOIN generate_series(1, 10) AS seat_row (number)
         CROSS JOIN generate_series(1, 25) AS seat (number);

-- 공연. 공개·운영 중이어야 판매 검증을 통과한다. 판매자 ID 1001은 seed-local.sql과 같은 예시 값이다
INSERT INTO p_performances (id, seller_id, venue_id, title, genre, age_rating, running_time, cast_info, description,
                            poster_url, publication_status, operation_status, published_at, version,
                            created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (900, 1001, 900, '[부하] 좌석 선점 측정 공연', 'CONCERT', 'ALL', 120, NULL, '좌석 선점 부하 측정용 공연입니다',
        NULL, 'PUBLISHED', 'ACTIVE', LOCALTIMESTAMP - INTERVAL '2 days', 1,
        LOCALTIMESTAMP - INTERVAL '3 days', 'SYSTEM', LOCALTIMESTAMP - INTERVAL '2 days', 'SYSTEM', NULL, NULL);

-- 공연별 등급 가격. 판매 대상 좌석이 쓰는 등급마다 있어야 한다(없으면 판매 검증이 거절이 아니라 500)
INSERT INTO p_performance_grade_prices (id, performance_id, venue_seat_grade_id, price,
                                        created_at, created_by, updated_at, updated_by)
VALUES (900, 900, 900, 150000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (901, 900, 901, 120000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'),
       (902, 900, 902, 90000, LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM');

-- 회차 900. 판매 중(운영 중·좌석 준비 READY·판매 중지 아님)이어야 판매 검증을 통과한다.
-- 시각은 실행한 날 0시 기준 상대값이다. 예매 오픈 어제 14시 < 마감(시작 1시간 전) ≤ 시작(30일 뒤 19시). DB CHECK가 이 순서를 요구한다
INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         canceled_at, cancel_reason, seat_setup_attempts, version,
                         created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
VALUES (900, 900,
        date_trunc('day', LOCALTIMESTAMP) + make_interval(days => 30, hours => 19),
        date_trunc('day', LOCALTIMESTAMP) + make_interval(days => -1, hours => 14),
        date_trunc('day', LOCALTIMESTAMP) + make_interval(days => 30, hours => 18),
        'ACTIVE', 'READY', 1, gen_random_uuid()::text, FALSE,
        NULL, NULL, 1, 0,
        LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM', NULL, NULL);

-- 공연 900의 공통 판매 제외 좌석: 구역마다 1열 1번. 좌석 서비스 시드의 is_sellable = false 좌석과 같아야 한다
INSERT INTO p_performance_seat_policies (id, performance_id, venue_seat_id, exclusion_reason,
                                         created_at, created_by, updated_at, updated_by)
SELECT 900 + section.k, 900, 100000 + section.k * 250 + 1, '운영석',
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
FROM generate_series(0, 3) AS section (k);

-- ID를 직접 넣은 테이블은 다음 자동 ID가 겹치지 않게 시퀀스를 맞춘다. seed-local.sql 뒤에 실행되므로 여기서 다시 맞춰야
-- 공연장 등록 API 등으로 만든 데이터가 이 시드의 ID와 겹치지 않는다(seed-local.sql 마지막 줄과 같은 형식)
SELECT setval(pg_get_serial_sequence('p_venues', 'id'), (SELECT max(id) FROM p_venues));
SELECT setval(pg_get_serial_sequence('p_venue_seat_grades', 'id'), (SELECT max(id) FROM p_venue_seat_grades));
SELECT setval(pg_get_serial_sequence('p_venue_seats', 'id'), (SELECT max(id) FROM p_venue_seats));
SELECT setval(pg_get_serial_sequence('p_performances', 'id'), (SELECT max(id) FROM p_performances));
SELECT setval(pg_get_serial_sequence('p_performance_grade_prices', 'id'), (SELECT max(id) FROM p_performance_grade_prices));
SELECT setval(pg_get_serial_sequence('p_schedules', 'id'), (SELECT max(id) FROM p_schedules));
SELECT setval(pg_get_serial_sequence('p_performance_seat_policies', 'id'), (SELECT max(id) FROM p_performance_seat_policies));
