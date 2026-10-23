-- reservation-service 스키마(reservation_schema)의 현재 DDL. Entity를 추가·변경하면 이 파일도 같이 고친다.
-- 통합 테스트(PostgresIntegrationTest)가 이 파일로 테이블을 만든 뒤 Entity와 맞는지 검사한다(ddl-auto=validate).
-- 테이블 이름에 스키마를 붙이지 않는다. 서비스 계정(reservation_app)으로 적용하면 계정 기본 search_path인 reservation_schema에 만들어진다.

-- Note: 아직 테이블이 없다. 실행할 문장이 하나도 없으면 spring.sql.init이 빈 스크립트로 보고 통합 테스트가 실패한다.
-- 첫 테이블의 DDL을 추가할 때 아래 줄을 지운다.
SELECT 1;
