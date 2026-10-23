#!/usr/bin/env bash
# Compose로 띄운 전체 시스템이 Gateway를 통해 연결되는지 점검한다. CI의 smoke 잡과 로컬에서 같이 쓴다.
# 사용: docker compose up -d --build && script/smoke.sh
# 점검: Gateway 기동 → 서비스 Eureka 등록 → 무인증 요청 401 → MASTER 로그인 → 토큰으로 내 정보 조회
set -euo pipefail
cd "$(dirname "$0")/.."

SERVICES=(user-service event-service reservation-service payment-service)
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

status=$(request "$gateway/api/v1/users/me")
[[ $status == 401 ]] && grep -q '"code":"UNAUTHORIZED"' "$body_file" \
  || fail "무인증 요청이 공통 401 응답이 아니다: $status $(cat "$body_file")"
echo "ok: 무인증 요청 401"

username=$(env_value MASTER_USERNAME)
password=$(env_value MASTER_PASSWORD)
if [[ -z "$username" || -z "$password" ]]; then
  echo "건너뜀: MASTER_USERNAME·MASTER_PASSWORD가 없어 로그인 점검을 하지 않는다"
  exit 0
fi

# user-service가 MASTER 계정을 만든 뒤에 로그인할 수 있다. 등록 직후에는 잠깐 실패할 수 있어 기다린다.
login() {
  [[ $(request -H 'Content-Type: application/json' \
    -d "{\"username\":\"$username\",\"password\":\"$password\"}" \
    "$gateway/api/v1/auth/login") == 200 ]]
}
wait_until "MASTER 로그인이 200이 아니다" login
token=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$body_file")
[[ -n "$token" ]] || fail "로그인 응답에 accessToken이 없다: $(cat "$body_file")"
echo "ok: MASTER 로그인"

status=$(request -H "Authorization: Bearer $token" "$gateway/api/v1/users/me")
[[ $status == 200 ]] && grep -q "\"username\":\"$username\"" "$body_file" \
  || fail "토큰으로 내 정보 조회에 실패했다: $status $(cat "$body_file")"
echo "ok: 토큰으로 내 정보 조회"

echo "smoke 통과"
