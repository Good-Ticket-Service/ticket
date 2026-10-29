-- 공개 회차 조회 테스트용 추가 회차. public-performance-fixture.sql 다음에 실행한다. 기준 시각은 2026-11-01 12:00(KST)이다.
-- 기존 회차의 판매 상태: 1·2 BEFORE_OPEN(오픈 11-10), 3 CANCELED(회차 취소), 5 BEFORE_OPEN(오픈 11-05), 6 ON_SALE, 7 CLOSED(10-30 마감)

-- 판매 기간 중인 공연 12의 회차: 판매 중지(11), 좌석 준비 중(12) → 둘 다 UNAVAILABLE
INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         suspension_reason, seat_setup_attempts, version, created_at, created_by, updated_at,
                         updated_by)
VALUES (11, 12, '2027-01-11 19:00:00', '2026-10-25 14:00:00', '2027-01-11 18:00:00', 'ACTIVE', 'READY', 1,
        'req-11', TRUE, '운영 점검', 1, 1, '2026-10-21 09:00:00', 'SYSTEM', '2026-10-30 09:00:00', 'SYSTEM');

INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, seat_setup_error,
                         sales_suspended, seat_setup_attempts, version, created_at, created_by, updated_at,
                         updated_by)
VALUES (12, 12, '2027-01-12 19:00:00', '2026-10-25 14:00:00', '2027-01-12 18:00:00', 'ACTIVE', 'PENDING', 2,
        'req-12', '좌석 서비스 응답 지연', FALSE, 2, 0, '2026-10-21 09:00:00', 'SYSTEM', '2026-10-21 09:00:00',
        'SYSTEM');

-- 취소된 공개 공연 22의 정상 회차 → 공연 취소라 CANCELED
-- 취소 회차 14는 취소 시각·사유가 있다
INSERT INTO p_schedules (id, performance_id, start_at, booking_open_at, booking_close_at, operation_status,
                         seat_setup_status, seat_config_version, seat_setup_request_id, sales_suspended,
                         canceled_at, cancel_reason, seat_setup_attempts, version, created_at, created_by,
                         updated_at, updated_by)
VALUES (13, 22, '2026-12-10 19:00:00', '2026-10-25 14:00:00', '2026-12-10 18:00:00', 'ACTIVE', 'READY', 1,
        'req-13', FALSE, NULL, NULL, 1, 0, '2026-10-25 09:00:00', 'SYSTEM', '2026-10-25 09:00:00', 'SYSTEM'),
       (14, 12, '2027-01-13 19:00:00', '2026-10-25 14:00:00', '2027-01-13 18:00:00', 'CANCELED', 'READY', 1,
        'req-14', FALSE, '2026-10-31 10:00:00', '공연장 사정', 1, 1, '2026-10-21 09:00:00', 'SYSTEM',
        '2026-10-31 10:00:00', 'SYSTEM');
