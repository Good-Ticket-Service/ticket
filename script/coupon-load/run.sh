#!/usr/bin/env bash
# 쿠폰 발급 k6 부하 측정 한 번을 끝까지 실행한다(#238).
# reservation-service 재시작(테이블 초기화) → 기동 대기 → 준비 운동(초당 100건 × 30초) → 본측정 → 결과 JSON 저장.
#
# 사용 (저장소 루트에서. 스택은 먼저 띄워 둔다: script/run.sh --no-seed config-server reservation-service prometheus grafana)
#   script/coupon-load/run.sh sustained <RATE> [DURATION]     지속 부하. DURATION 기본 1m
#   script/coupon-load/run.sh burst <VUS> <QUANTITY>          순간 몰림
# 예
#   script/coupon-load/run.sh sustained 175                   → out/sustained-1m-175-1.json
#   script/coupon-load/run.sh sustained 200 3m                → out/sustained-3m-200-1.json
#   script/coupon-load/run.sh burst 8000 1000                 → out/burst-8000-1000-1.json
#
# 결과 파일 이름은 측정값으로 만든다. 같은 이름이 있으면 끝 번호를 올려 덮어쓰지 않는다(-1 → -2).
# 추가 k6 옵션은 환경 변수로 넘긴다. 예: K6_EXTRA="-e MAX_VUS=3000" script/coupon-load/run.sh sustained 250
set -euo pipefail
cd "$(dirname "$0")/../.."

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

# 1) 인자로 시나리오와 결과 파일 이름 앞부분을 정한다
mode=${1:-}
case "$mode" in
  sustained)
    [[ $# -ge 2 ]] || usage
    rate=$2
    duration=${3:-1m}
    script=coupon-issue-sustained.js
    args=(-e "RATE=$rate" -e "DURATION=$duration")
    prefix="sustained-$duration-$rate"
    ;;
  burst)
    [[ $# -ge 3 ]] || usage
    script=coupon-issue.js
    args=(-e "VUS=$2" -e "QUANTITY=$3")
    prefix="burst-$2-$3"
    ;;
  *) usage ;;
esac

# 2) 같은 이름이 있으면 번호를 올린다. 이전 결과를 실수로 덮어쓰지 않게 한다
out_dir=script/coupon-load/out
mkdir -p "$out_dir"
n=1
while [[ -e "$out_dir/$prefix-$n.json" ]]; do
  n=$((n + 1))
done
result="$prefix-$n.json"

# 3) k6 실행 명령. Windows Git Bash는 경로 자동 변환을 끄고 Windows 경로로 넘긴다
if pwd -W >/dev/null 2>&1; then
  export MSYS_NO_PATHCONV=1
  work="$(pwd -W)/script/coupon-load"
  user_args=()
else
  work="$PWD/script/coupon-load"
  # k6 이미지는 root가 아닌 사용자로 돌아 out/에 쓰지 못할 수 있어 내 사용자로 실행한다
  user_args=(--user "$(id -u):$(id -g)")
fi
k6() {
  # 빈 배열을 set -u에서 펼치면 macOS 기본 bash(3.2)가 오류를 내므로 있을 때만 펼친다
  docker run --rm ${user_args[@]+"${user_args[@]}"} --network ticket_ticket-net -v "$work:/work" grafana/k6:0.54.0 run "$@"
}

# 4) 재시작으로 테이블을 초기화하고 기동을 기다린다(local은 ddl-auto: create)
echo "== reservation-service 재시작"
docker compose restart reservation-service >/dev/null
# 확인 명령마다 10초 제한을 둔다. Docker가 잠깐 멈추면 exec가 응답 없이 끝없이 기다릴 수 있다
# 3분이 지나도 뜨지 않으면 이 회차를 포기한다(반복 실행 중이면 다음 회차로 넘어간다)
deadline=$((SECONDS + 180))
until timeout 10 docker compose exec -T reservation-service curl -fs localhost:8083/actuator/health >/dev/null 2>&1; do
  if (( SECONDS > deadline )); then
    echo "== 3분 안에 기동을 확인하지 못해 이 회차를 건너뜀" >&2
    exit 1
  fi
  sleep 3
done

# 5) 준비 운동. 기동 직후 JVM은 느려 그대로 재면 시작 구간이 막힌다. 결과는 버린다
#    준비 운동이 합격 조건을 어겨도 본측정은 한다(예전에는 여기서 조용히 끝나 결과 파일 없이 넘어갔다)
echo "== 준비 운동 (초당 100건 × 30초)"
k6 -q -e RATE=100 -e DURATION=30s /work/k6/coupon-issue-sustained.js >/dev/null || echo "== 준비 운동 중 오류가 있었음(결과는 버림)" >&2

# 6) 본측정. 합격 조건을 어겨 k6가 99로 끝나도 결과 파일 위치는 알린다
echo "== 본측정: $mode ${args[*]} → out/$result"
status=0
# shellcheck disable=SC2086
k6 "${args[@]}" ${K6_EXTRA:-} --summary-export="/work/out/$result" "/work/k6/$script" || status=$?
echo "== 결과: script/coupon-load/out/$result (k6 종료 코드 $status)"
exit "$status"
