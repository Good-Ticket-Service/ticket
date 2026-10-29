#!/usr/bin/env bash
# Task 12 단독 환경의 빈 볼륨을 처음 띄울 때만 실행한다. 10-init.sh가 만든 payment_app 계정으로 payment 테이블을 만든다.
# 계정 기본 search_path가 payment_schema라 테이블이 그 스키마에 생긴다(backend/sql/payment-service/schema.sql 머리말 참고).
set -euo pipefail

PGPASSWORD="$PAYMENT_DB_PASSWORD" psql -v ON_ERROR_STOP=1 --username payment_app --dbname "$POSTGRES_DB" \
  -f /t12/payment-schema.sql
