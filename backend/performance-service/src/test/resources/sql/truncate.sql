-- 공연 테이블 7개를 비우고 자동 ID를 1부터 다시 시작한다. 테스트 데이터를 넣기 전에 먼저 실행한다.
TRUNCATE TABLE p_performance_seat_policies, p_schedules, p_performance_grade_prices, p_performances,
    p_venue_seats, p_venue_seat_grades, p_venues RESTART IDENTITY CASCADE;
