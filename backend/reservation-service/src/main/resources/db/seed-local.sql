-- reservation-service 로컬 실행용 회차 좌석 시드(좌석 Task 13 1단계, #124).
--
-- 사용 범위
--   - local 프로필 전용이다. config-repo/reservation-service-local.yml이 Hibernate가 테이블을 만든 뒤 이 파일을 실행한다.
--   - dev·운영 DB에는 넣지 않는다. 운영 코드 경로(좌석 준비)와 무관한 확인용 데이터다.
--   - local은 기동마다 테이블을 새로 만들므로(application-local.yml의 ddl-auto: create) 매번 빈 테이블에 들어가 중복되지 않는다.
--     직접 실행할 때는 p_schedule_seats가 빈 상태에서 reservation_app 계정으로 실행한다. 다시 넣으면 UK(schedule_id, venue_seat_id) 위반이다.
--
-- 용도
--   - 회차 좌석은 원래 공연 서비스의 좌석 준비로 만들어지지만 아직 구현 전이다. 그동안 local에서 좌석 조회
--     (GET /api/seats/schedules/{scheduleId})와 이후 좌석 선점(판매 검증 포함)을 Swagger·curl로 끝까지 확인할 수 있게 한다.
--   - 부하 테스트용 대량 시드는 이 파일이 아니다(Checkpoint B 때 공연 쪽 시드와 함께 따로 만든다).
--
-- 기준: 공연 local 시드(performance-service/src/main/resources/db/seed-local.sql)
--   - 회차 1~3: 공연 1 '오페라의 유령', 서울 공연장(venue_id 1), 판매 중. 좌석 구성 버전(seat_config_version) 1
--   - 배치 40석: 열마다 10석. A구역 1열 VIP(등급 1), A구역 2열 R(등급 2), B구역 1·2열 S(등급 3)
--   - 공연 1의 등급 가격: VIP 170,000원, R 140,000원, S 110,000원
--   - 공연 1의 공통 판매 제외 좌석: A구역 1열 1번(운영석). 판매 제외지만 VIP 가격이 있어 base_price는 채운다
--   좌석 서비스는 공연 테이블을 조회할 수 없어(서비스마다 스키마·계정이 다르다) 위 값을 숫자로 직접 적는다.
--   공연 시드의 회차·배치·가격·판매 제외를 바꾸면 이 파일과 SeatSeedLocalSqlIntegrationTest도 함께 바꾼다.
--
-- 공연장 좌석 ID(venue_seat_id) 계산
--   공연 시드는 p_venue_seats의 ID를 DB가 만든다. local DB에서 실제로 조회한 결과(2026-10-29), 서울 공연장 좌석 ID는
--   좌석 번호가 바깥, 배치(열)가 안쪽인 순서로 매겨져 있다.
--     id = (seat_number - 1) * 4 + k   (k = 1: A구역 1열, 2: A구역 2열, 3: B구역 1열, 4: B구역 2열)
--     예) A-1-1 = 1, A-2-1 = 2, B-1-1 = 3, B-2-1 = 4, A-1-2 = 5, ..., B-2-10 = 40
--   이 순서는 공연 시드 INSERT의 결과 순서에 기댄 값이라 바뀔 수 있다. 공연 시드에서 ID를 직접 넣어 고정해 달라고 요청했다(#124).
--   고정되면 그 ID에 맞춰 k와 공식을 고친다.

INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,
                              section, row_label, seat_number, is_sellable, status, reservation_id, hold_expires_at,
                              config_version, created_at, created_by, updated_at, updated_by)
SELECT schedule.id,
       -- 공연 시드의 실제 공연장 좌석 ID(위 공식)
       (seat.number - 1) * 4 + layout.k,
       layout.grade_id,
       layout.grade_name,
       layout.base_price,
       layout.section,
       layout.row_label,
       seat.number,
       -- 공연 1의 판매 제외 좌석(A구역 1열 1번)만 false다. 판매 제외는 회차와 상관없이 공연 단위라 세 회차 모두 같다
       NOT (layout.section = 'A' AND layout.row_label = '1' AND seat.number = 1),
       -- 새로 준비된 좌석이라 모두 비어 있다. 선점·판매 상태는 API로 만든다.
       -- Hibernate가 만든 local 테이블에는 DEFAULT가 없을 수 있어 status와 비어 있는 값까지 모두 적는다
       'AVAILABLE',
       NULL,
       NULL,
       1,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
-- 판매 중인 회차 1~3
FROM (VALUES (1), (2), (3)) AS schedule (id)
         -- 배치 순서(k)·등급·가격·위치. k는 공연 시드의 좌석 ID 순서와 같아야 한다
         CROSS JOIN (VALUES (1, 1, 'VIP', 170000, 'A', '1'),
                            (2, 2, 'R', 140000, 'A', '2'),
                            (3, 3, 'S', 110000, 'B', '1'),
                            (4, 3, 'S', 110000, 'B', '2')) AS layout (k, grade_id, grade_name, base_price, section, row_label)
         -- 열마다 좌석 1~10번
         CROSS JOIN generate_series(1, 10) AS seat (number);
