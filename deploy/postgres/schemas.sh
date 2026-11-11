#!/usr/bin/env bash
# 빈 볼륨을 처음 기동할 때만 10-roles.sh(infra/postgres/init.sh) 다음에 실행된다. 기존 볼륨은 바꾸지 않는다.
# 서버는 dev 프로필로 떠서 Hibernate가 테이블을 만들지 않고 Entity와 맞는지만 검사한다(ddl-auto: validate).
# 그래서 앱보다 먼저 테이블이 있어야 한다. 각 서비스 계정으로 적용해 테이블 소유자를 그 계정으로 둔다.
# 결제 스키마는 10-roles.sh가 적용하고, fake-pg는 기동할 때 스스로 만든다.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SET ROLE user_app;
SET search_path TO user_schema;
\i /ticket-sql/user-service/schema.sql
RESET ROLE;

SET ROLE performance_app;
SET search_path TO performance_schema;
\i /ticket-sql/performance-service/schema.sql
RESET ROLE;

SET ROLE reservation_app;
SET search_path TO reservation_schema;
\i /ticket-sql/reservation-service/schema.sql
\i /ticket-sql/reservation-service/booking.sql
\i /ticket-sql/reservation-service/seat.sql
\i /ticket-sql/reservation-service/coupon.sql
RESET ROLE;

SET search_path TO public;
SQL
