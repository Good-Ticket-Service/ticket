#!/usr/bin/env bash
# 지표 수집용 계정(metrics_app)과 SQL 통계 확장(pg_stat_statements)을 만든다. 여러 번 실행해도 결과가 같다.
# 빈 볼륨을 처음 띄울 때는 자동으로 돈다. 이미 데이터가 있는 DB에는 README의 명령으로 한 번 실행한다.
# metrics_app은 비밀번호가 없어 TCP로는 로그인할 수 없다. postgres-exporter가 공유 소켓(pg-socket 볼륨)으로만 접속한다.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'metrics_app') THEN
    CREATE ROLE metrics_app LOGIN;
  END IF;
END
$$;
-- 다른 계정의 세션·대기·SQL 통계를 읽는 데 필요한 내장 읽기 권한만 준다
GRANT pg_monitor TO metrics_app;
GRANT CONNECT ON DATABASE ticket TO metrics_app;
-- pg_stat_statements 뷰가 public 스키마에 있다. infra/postgres/init.sh가 public 사용 권한을 막아 두어서 이 계정에만 연다
GRANT USAGE ON SCHEMA public TO metrics_app;
SQL
