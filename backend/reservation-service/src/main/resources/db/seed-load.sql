-- reservation-service 부하 측정용 회차 좌석 시드(#173). 공연 서비스의 부하용 공연·회차 시드와 짝이다.
--
-- 사용 범위
--   - local 프로필 전용이다. config-repo/reservation-service-local.yml이 seed-local.sql 다음에 이 파일을 실행한다.
--   - dev·운영 DB에는 넣지 않는다.
--
-- 용도
--   - 좌석 선점 부하 측정(script/seat-load). local 시드(회차당 40석)로는 많은 회원이 서로 다른 좌석을 잡는 처리량과
--     좌석이 많을 때의 조회 부하를 잴 수 없어, 회차 하나에 1,000석을 넣는다.
--   - 측정 사이 초기화: docker compose restart reservation-service
--     local은 기동마다 테이블을 새로 만들고(ddl-auto: create) 이 시드를 다시 넣으므로, 좌석은 모두 AVAILABLE, 예약·티켓은 빈 상태가 된다.
--     공연 쪽은 판매 검증이 읽기만 해서 초기화가 필요 없다.
--
-- 기준: 공연 부하 시드(performance-service/src/main/resources/db/seed-load.sql)
--   - 회차 900(공연 900 '[부하] 좌석 선점 측정 공연', 공연장 900), 판매 중. 좌석 구성 버전(seat_config_version) 1
--   - 배치 1,000석: 구역 A~D × 열 1~10 × 좌석 1~25. 등급은 열 단위로 1~3열 VIP(등급 900), 4~7열 R(901), 8~10열 S(902)
--   - 등급 가격: VIP 150,000원, R 120,000원, S 90,000원
--   - 판매 제외: 구역마다 1열 1번(운영석) 4석. 판매 제외지만 VIP 가격이 있어 base_price는 채운다
--   좌석 서비스는 공연 테이블을 조회할 수 없어(서비스마다 스키마·계정이 다르다) 위 값을 숫자로 직접 적는다.
--   하나를 바꾸면 두 시드와 두 시드 테스트(SeedLoadSqlIntegrationTest, SeatSeedLoadSqlIntegrationTest)를 함께 고친다.
--
-- 공연장 좌석 ID(venue_seat_id): 공연 시드가 같은 공식으로 직접 넣는다
--   id = 100000 + 구역 순번(A=0 … D=3) × 250 + (열 - 1) × 25 + 좌석 번호   예) A-1-1 = 100001, D-10-25 = 101000

INSERT INTO p_schedule_seats (schedule_id, venue_seat_id, venue_seat_grade_id, grade_name, base_price,
                              section, row_label, seat_number, is_sellable, status, reservation_id, hold_expires_at,
                              config_version, created_at, created_by, updated_at, updated_by)
SELECT 900,
       -- 공연 시드의 실제 공연장 좌석 ID(위 공식)
       100000 + section.k * 250 + (seat_row.number - 1) * 25 + seat.number,
       grade.id,
       grade.name,
       grade.price,
       section.name,
       seat_row.number::text,
       seat.number,
       -- 공연 900의 판매 제외 좌석(구역마다 1열 1번)만 false다
       NOT (seat_row.number = 1 AND seat.number = 1),
       -- 새로 준비된 좌석이라 모두 비어 있다. Hibernate가 만든 local 테이블에는 DEFAULT가 없을 수 있어 모두 적는다
       'AVAILABLE',
       NULL,
       NULL,
       1,
       LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM'
-- 구역 순번(k)과 이름
FROM (VALUES (0, 'A'), (1, 'B'), (2, 'C'), (3, 'D')) AS section (k, name)
         -- 열 1~10
         CROSS JOIN generate_series(1, 10) AS seat_row (number)
         -- 열의 등급·가격. 1~3열 VIP, 4~7열 R, 8~10열 S
         CROSS JOIN LATERAL (SELECT CASE WHEN seat_row.number <= 3 THEN 900 WHEN seat_row.number <= 7 THEN 901 ELSE 902 END,
                                    CASE WHEN seat_row.number <= 3 THEN 'VIP' WHEN seat_row.number <= 7 THEN 'R' ELSE 'S' END,
                                    CASE WHEN seat_row.number <= 3 THEN 150000 WHEN seat_row.number <= 7 THEN 120000 ELSE 90000 END
                            ) AS grade (id, name, price)
         -- 열마다 좌석 1~25번
         CROSS JOIN generate_series(1, 25) AS seat (number);
