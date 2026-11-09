#!/usr/bin/env bash
# PostgreSQL의 빈 볼륨을 처음 기동할 때만 실행한다. 기존 계정 비밀번호는 바꾸지 않는다.
# 서비스마다 전용 계정과 스키마를 만들고, 계정은 자기 스키마에만 권한을 갖는다(서비스 간 DB 직접 참조 차단).
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER가 필요하다}"
: "${POSTGRES_DB:?POSTGRES_DB가 필요하다}"
: "${USER_DB_PASSWORD:?USER_DB_PASSWORD가 필요하다}"
: "${PERFORMANCE_DB_PASSWORD:?PERFORMANCE_DB_PASSWORD가 필요하다}"
: "${RESERVATION_DB_PASSWORD:?RESERVATION_DB_PASSWORD가 필요하다}"
: "${PAYMENT_DB_PASSWORD:?PAYMENT_DB_PASSWORD가 필요하다}"
: "${FAKE_PG_DB_PASSWORD:?FAKE_PG_DB_PASSWORD가 필요하다}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL

create_service_schema() {
  local schema="$1" app_user="$2" password="$3"
  # Note: psql의 식별자·문자열 인용을 사용해 비밀번호의 작은따옴표도 SQL로 해석되지 않게 한다.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=schema_name="$schema" --set=app_user="$app_user" --set=password="$password" <<'SQL'
CREATE USER :"app_user" WITH PASSWORD :'password';
CREATE SCHEMA IF NOT EXISTS :"schema_name" AUTHORIZATION :"app_user";
ALTER ROLE :"app_user" SET search_path = :"schema_name";
SQL
}

create_service_schema user_schema user_app "$USER_DB_PASSWORD"
create_service_schema performance_schema performance_app "$PERFORMANCE_DB_PASSWORD"
create_service_schema reservation_schema reservation_app "$RESERVATION_DB_PASSWORD"
create_service_schema payment_schema payment_app "$PAYMENT_DB_PASSWORD"
# 테스트용 fake PG(backend/fake-pg)의 거래 기록
create_service_schema fake_pg_schema fake_pg_app "$FAKE_PG_DB_PASSWORD"

# 결제는 JDBC 운영 테이블·Batch 메타데이터·원장 불변 트리거도 사용한다.
# Hibernate create는 이 계약을 만들 수 없고 재기동 시 금융 원장을 지우므로, 결제 local도 validate를 쓴다.
# 기본 Compose가 공식 SQL을 마운트한다. 자체 스키마를 준비하는 독립 시험 환경은 역할 생성만 재사용할 수 있다.
if [[ -f /ticket-sql/payment-service/schema.sql ]]; then
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SET ROLE payment_app;
SET search_path = payment_schema;
\i /ticket-sql/payment-service/schema.sql
RESET ROLE;
SQL
fi
