#!/usr/bin/env bash
# 빈 통합 시험 볼륨의 최초 기동에만 적용된다. 기존 볼륨은 변경하지 않는다.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SET ROLE user_app;
SET search_path TO user_schema;
\i /integration-sql/user-service/schema.sql
RESET ROLE;
SET ROLE performance_app;
SET search_path TO performance_schema;
\i /integration-sql/performance-service/schema.sql
RESET ROLE;
SET ROLE reservation_app;
SET search_path TO reservation_schema;
\i /integration-sql/reservation-service/schema.sql
\i /integration-sql/reservation-service/booking.sql
\i /integration-sql/reservation-service/seat.sql
\i /integration-sql/reservation-service/coupon.sql
RESET ROLE;
-- 기본 Compose의 infra/postgres/init.sh도 결제 schema.sql을 적용한다(ed1301d0). 두 번 적용하면 p_payments 중복으로
-- 초기화가 실패하므로 이미 있으면 건너뛴다
SELECT to_regclass('payment_schema.p_payments') IS NULL AS need_payment \gset
\if :need_payment
SET ROLE payment_app;
SET search_path TO payment_schema;
\i /integration-sql/payment-service/schema.sql
RESET ROLE;
\endif
SET ROLE fake_pg_app;
SET search_path TO fake_pg_schema;
\i /integration-sql/fake-pg/schema.sql
RESET ROLE;
SET search_path TO public;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE ROLE integration_metrics LOGIN PASSWORD 'integration_metrics_local_only';
GRANT pg_monitor TO integration_metrics;
GRANT CONNECT ON DATABASE ticket TO integration_metrics;
GRANT USAGE ON SCHEMA public TO integration_metrics;
SQL
