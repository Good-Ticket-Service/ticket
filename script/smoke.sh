#!/usr/bin/env bash
# Compose로 띄운 전체 시스템이 Gateway를 통해 연결되는지 점검한다. CI의 smoke 잡과 로컬에서 같이 쓴다.
# 사용: docker compose up -d --build && script/smoke.sh
# 점검: Gateway 기동 → 서비스 Eureka 등록 → 무인증 요청 401 → Kafka 송수신 → Prometheus 수집·Grafana 연결
#       → fake PG 결제 인증·승인
#       → MASTER 로그인 → 토큰으로 내 정보 조회
set -euo pipefail
cd "$(dirname "$0")/.."

SERVICES=(user-service performance-service reservation-service payment-service)
WAIT_SECONDS=${SMOKE_WAIT_SECONDS:-300}

fail() { echo "실패: $*" >&2; exit 1; }

# .env를 다시 해석하지 않고 Compose 프로젝트의 실제 공개 포트를 조회한다(run.sh와 같은 방식).
host_url() {
  local binding
  binding=$(docker compose port "$1" "$2" 2>/dev/null | head -n 1) || return 1
  [[ -n "$binding" ]] || return 1
  printf 'http://localhost:%s' "${binding##*:}"
}

# .env의 값 하나를 읽는다. 환경 변수로 이미 주어졌으면 그 값을 쓴다.
env_value() {
  local name=$1 value
  value=${!name:-}
  if [[ -z "$value" && -f .env ]]; then
    value=$(grep -E "^${name}=" .env | tail -n 1 | cut -d= -f2- || true)
  fi
  printf '%s' "$value"
}

# 상태 코드를 출력하고 응답 본문은 $body_file에 남긴다.
body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT
request() {
  curl -s -o "$body_file" -w '%{http_code}' --max-time 10 "$@" || echo 000
}

wait_until() {
  local description=$1; shift
  local deadline=$((SECONDS + WAIT_SECONDS))
  until "$@"; do
    (( SECONDS < deadline )) || fail "$description (${WAIT_SECONDS}초 초과). 마지막 응답: $(cat "$body_file" 2>/dev/null)"
    sleep 3
  done
}

gateway=$(host_url gateway-service 8080) || fail "gateway-service 컨테이너가 없다. docker compose up을 먼저 실행한다"
eureka=$(host_url eureka-server 8761) || fail "eureka-server 컨테이너가 없다"

gateway_up() { [[ $(request "$gateway/actuator/health") == 200 ]]; }
wait_until "Gateway health가 200이 아니다" gateway_up
echo "ok: gateway health"

# Note: Eureka 앱 이름은 대문자다. ${var^^}는 macOS 기본 bash 3.2에 없어 tr로 바꾼다.
registered() {
  local app
  app=$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')
  [[ $(request -H 'Accept: application/json' "$eureka/eureka/apps/$app") == 200 ]] && grep -q '"UP"' "$body_file"
}
for service in "${SERVICES[@]}"; do
  wait_until "$service가 Eureka에 UP으로 등록되지 않았다" registered "$service"
  echo "ok: $service 등록"
done

status=$(request "$gateway/api/users/me")
[[ $status == 401 ]] && grep -q '"code":"UNAUTHORIZED"' "$body_file" \
  || fail "무인증 요청이 공통 401 응답이 아니다: $status $(cat "$body_file")"
echo "ok: 무인증 요청 401"

# Kafka: 컨테이너끼리 쓰는 INTERNAL 리스너(kafka:29092)로 토픽을 만들고 메시지를 보내고 받는다. 점검용 토픽은 지운다.
if docker compose ps --services --status running | grep -qx kafka; then
  # Note: Windows Git Bash는 /opt/... 인자를 Windows 경로로 바꾼다. 컨테이너 안 경로가 깨지지 않게 이 명령에서만 끈다.
  kafka() { MSYS_NO_PATHCONV=1 docker compose exec -T kafka "/opt/kafka/bin/$1" --bootstrap-server kafka:29092 "${@:2}"; }
  topic="smoke-check-$(date +%s)"
  kafka kafka-topics.sh --create --topic "$topic" --partitions 1 --replication-factor 1 > /dev/null \
    || fail "Kafka 토픽을 만들지 못했다"
  echo "smoke" | kafka kafka-console-producer.sh --topic "$topic" || fail "Kafka에 메시지를 보내지 못했다"
  received=$(kafka kafka-console-consumer.sh --topic "$topic" --from-beginning --max-messages 1 \
    --timeout-ms 20000 2> /dev/null | tr -d '\r' || true)
  kafka kafka-topics.sh --delete --topic "$topic" > /dev/null 2>&1 || true
  [[ $received == smoke ]] || fail "Kafka에서 보낸 메시지를 받지 못했다: '$received'"
  echo "ok: Kafka 송수신"
fi

# Prometheus: Eureka에서 찾은 앱이 모두 수집되는지(up=1) 확인한다.
if prometheus=$(host_url prometheus 9090); then
  scraped() {
    [[ $(request -G --data-urlencode "query=up{job=\"spring-apps\",application=\"$1\"}" \
      "$prometheus/api/v1/query") == 200 ]] && grep -q '"1"\]' "$body_file"
  }
  for app in gateway-service "${SERVICES[@]}"; do
    wait_until "Prometheus가 $app 메트릭을 수집하지 못한다" scraped "$app"
  done
  echo "ok: Prometheus 수집 (gateway-service, ${SERVICES[*]})"
fi

# Grafana: 자동 등록한 Prometheus 데이터 소스가 연결되는지 확인한다.
if grafana=$(host_url grafana 3000); then
  grafana_password=$(env_value GRAFANA_ADMIN_PASSWORD)
  datasource_ok() {
    [[ $(request -u "admin:$grafana_password" "$grafana/api/datasources/uid/prometheus/health") == 200 ]] \
      && grep -q '"status":"OK"' "$body_file"
  }
  wait_until "Grafana의 Prometheus 데이터 소스가 연결되지 않는다" datasource_ok
  echo "ok: Grafana 데이터 소스"
fi

# fake PG: 결제 인증 시뮬레이션으로 paymentKey를 받고, 토스 형식 승인이 DONE으로 끝나는지 확인한다.
if fake_pg=$(host_url fake-pg 8090); then
  fake_pg_up() { [[ $(request "$fake_pg/actuator/health") == 200 ]]; }
  wait_until "fake PG health가 200이 아니다" fake_pg_up
  order_id="smoke-$(date +%s)-$RANDOM"
  status=$(request -H 'Content-Type: application/json' -d "{\"orderId\":\"$order_id\",\"amount\":1000}" \
    "$fake_pg/fake/toss/checkout")
  payment_key=$(sed -n 's/.*"paymentKey":"\([^"]*\)".*/\1/p' "$body_file")
  [[ $status == 200 && -n "$payment_key" ]] || fail "fake PG 결제 인증 시뮬레이션 실패: $status $(cat "$body_file")"
  toss_auth=$(printf '%s' "${FAKE_PG_TOSS_SECRET_KEY:-test_sk_fake_toss}:" | base64)
  status=$(request -H "Authorization: Basic $toss_auth" -H 'Content-Type: application/json' \
    -d "{\"paymentKey\":\"$payment_key\",\"orderId\":\"$order_id\",\"amount\":1000}" "$fake_pg/v1/payments/confirm")
  [[ $status == 200 ]] && grep -q '"status":"DONE"' "$body_file" \
    || fail "fake PG 승인 실패: $status $(cat "$body_file")"
  echo "ok: fake PG 결제 인증·승인"
fi

email=$(env_value MASTER_EMAIL)
password=$(env_value MASTER_PASSWORD)
if [[ -z "$email" || -z "$password" ]]; then
  echo "건너뜀: MASTER_EMAIL·MASTER_PASSWORD가 없어 로그인 점검을 하지 않는다"
  exit 0
fi

# user-service가 MASTER 계정을 만든 뒤에 로그인할 수 있다. 등록 직후에는 잠깐 실패할 수 있어 기다린다.
login() {
  [[ $(request -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$password\"}" \
    "$gateway/api/auth/login") == 200 ]]
}
wait_until "MASTER 로그인이 200이 아니다" login
token=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$body_file")
[[ -n "$token" ]] || fail "로그인 응답에 accessToken이 없다: $(cat "$body_file")"
echo "ok: MASTER 로그인"

status=$(request -H "Authorization: Bearer $token" "$gateway/api/users/me")
[[ $status == 200 ]] && grep -q "\"email\":\"$email\"" "$body_file" \
  || fail "토큰으로 내 정보 조회에 실패했다: $status $(cat "$body_file")"
echo "ok: 토큰으로 내 정보 조회"

echo "smoke 통과"
