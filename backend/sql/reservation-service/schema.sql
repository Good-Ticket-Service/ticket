-- reservation-service 스키마(reservation_schema)에서 모듈이 함께 쓰는 DDL. 지금은 공유 테이블이 없다.
-- 모듈이 소유한 테이블은 이 파일에 넣지 않고 booking.sql, seat.sql, coupon.sql에 둔다. 여러 모듈이 한 파일을 고치면 충돌하기 때문이다(#57).
-- 가장 먼저 적용한다. 통합 테스트는 ReservationIntegrationTest가 schema.sql → booking.sql → seat.sql → coupon.sql 순서로 실행한 뒤 Entity와 대조한다(ddl-auto=validate).
-- 테이블 이름에 스키마를 붙이지 않는다. 서비스 계정(reservation_app)으로 적용하면 계정 기본 search_path인 reservation_schema에 만들어진다.

-- Note: 공유 테이블이 없어 실행할 문장이 하나도 없으면 spring.sql.init이 빈 스크립트로 보고 통합 테스트가 실패한다.
-- 공유 테이블의 DDL을 추가할 때 아래 줄을 지운다.
SELECT 1;
