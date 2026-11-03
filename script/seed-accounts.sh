#!/usr/bin/env bash
# 로컬에서 쓸 USER·SELLER 계정을 가입 API로 만든다. MASTER는 user-service가 기동할 때 만든다(MASTER_EMAIL).
# 사용: script/seed-accounts.sh   (script/run.sh가 기동 끝에 부른다. user-service만 다시 띄운 뒤에는 직접 실행한다)
# local 프로필은 user-service가 기동할 때마다 테이블을 다시 만들어 가입한 계정이 사라지므로, 띄울 때마다 다시 만든다.
# 계정은 .env의 SEED_USER_*·SEED_SELLER_*로 정한다. .env에 그 줄이 없으면 .env.example 값을 쓰고, 줄은 있는데 비어 있으면 만들지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."

WAIT_SECONDS=${SEED_WAIT_SECONDS:-180}

# .env를 다시 해석하지 않고 Compose 프로젝트의 실제 공개 포트를 조회한다(run.sh와 같은 방식).
host_url() {
  local binding
  binding=$(docker compose port "$1" "$2" 2>/dev/null | head -n 1) || return 1
  [[ -n "$binding" ]] || return 1
  printf 'http://localhost:%s' "${binding##*:}"
}

# 값 하나를 환경 변수 → .env → .env.example 순서로 찾는다. 이 키가 생기기 전에 복사한 .env에도 계정이 만들어지게 하려는 것이다.
env_value() {
  local name=$1 file
  if [[ -n "${!name:-}" ]]; then
    printf '%s' "${!name}"
    return
  fi
  for file in .env .env.example; do
    if [[ -f "$file" ]] && grep -qE "^${name}=" "$file"; then
      grep -E "^${name}=" "$file" | tail -n 1 | cut -d= -f2-
      return
    fi
  done
}

# JSON 문자열 안에서 따옴표와 역슬래시가 값으로 남게 한다.
json_string() {
  local value=${1//\\/\\\\}
  printf '"%s"' "${value//\"/\\\"}"
}

running=$(docker compose ps --status running --services 2>/dev/null || true)
if ! grep -qx gateway-service <<<"$running" || ! grep -qx user-service <<<"$running"; then
  echo "건너뜀: gateway-service와 user-service가 떠 있지 않아 테스트 계정을 만들지 않는다"
  exit 0
fi
gateway=$(host_url gateway-service 8080) || { echo "Gateway 포트를 찾지 못했다" >&2; exit 1; }

body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT
request() {
  curl -s -o "$body_file" -w '%{http_code}' --max-time 10 "$@" || echo 000
}

# 없는 계정의 로그인이 401이면 Gateway가 user-service까지 요청을 넘긴다는 뜻이다. Eureka 등록 전에는 503이 온다.
echo "테스트 계정: user-service가 요청을 받을 때까지 기다린다(최대 ${WAIT_SECONDS}초)"
deadline=$((SECONDS + WAIT_SECONDS))
until [[ $(request -H 'Content-Type: application/json' -d '{"email":"nobody@ticket.invalid","password":"x"}' \
  "$gateway/api/auth/login") == 401 ]]; do
  if (( SECONDS >= deadline )); then
    echo "user-service가 ${WAIT_SECONDS}초 안에 요청을 받지 않았다. 서비스가 뜬 뒤 script/seed-accounts.sh를 다시 실행한다" >&2
    exit 1
  fi
  sleep 3
done

failed=false
# 역할은 서버가 경로로 정한다. 이미 있으면(재시작하지 않은 DB) 409라 그대로 둔다.
signup() {
  local label=$1 path=$2 prefix=$3 name=$4 phone=$5 email password status
  email=$(env_value "${prefix}_EMAIL")
  password=$(env_value "${prefix}_PASSWORD")
  if [[ -z "$email" || -z "$password" ]]; then
    echo "건너뜀: .env의 ${prefix}_EMAIL·${prefix}_PASSWORD가 비어 있어 $label 계정을 만들지 않는다"
    return
  fi
  status=$(request -H 'Content-Type: application/json' \
    -d "{\"email\":$(json_string "$email"),\"password\":$(json_string "$password"),\"name\":\"$name\",\"phone\":\"$phone\"}" \
    "$gateway$path")
  case "$status" in
    201) echo "ok: $label 계정을 만들었다 ($email)" ;;
    409) echo "ok: $label 계정이 이미 있다 ($email)" ;;
    *) echo "$label 계정을 만들지 못했다: $status $(cat "$body_file")" >&2; failed=true ;;
  esac
}

signup USER /api/auth/signup SEED_USER 테스트회원 010-0000-0001
signup SELLER /api/auth/seller/signup SEED_SELLER 테스트판매자 010-0000-0002

[[ "$failed" == false ]]
