# 쿠폰 선착순 발급 k6 부하 시나리오 (#238)

쿠폰 하나에 발급 요청을 몰아 보내 정합성(수량을 넘겨 발급하지 않음)과 기준 측정값(응답 시간·처리량)을 확인합니다. 발급은 쿠폰 행 하나를 잠그고 수량을 늘리는 방식이라, 인기 쿠폰 하나에 몰린 요청은 한 줄로 처리됩니다. 발급 방식을 바꿀 때(예: Redis 수량 선차감) 이 시나리오로 다시 재서 비교합니다.

## 측정 조건

- **reservation-service 직접 호출**: Gateway를 거치지 않습니다. Gateway가 JWT를 검사한 뒤 넣어 주는 회원 헤더(`X-User-Id`·`X-Username`·`X-User-Role`)를 k6가 직접 채웁니다. 그래서 JWT·라우팅 비용은 빠지고 쿠폰 로직과 DB 잠금 비용만 잽니다. 실제 사용자 경로의 응답 시간이 아닙니다.
- **실행마다 새 쿠폰**: `setup()`에서 MASTER 헤더로 쿠폰을 하나 만들고 그 쿠폰만 발급합니다. 1인 1장은 쿠폰마다 따지므로 서비스를 재시작하지 않고 다시 돌려도 됩니다. 다른 서비스(공연 등)는 부르지 않습니다.
- **서버 설정은 기본값**: DB 커넥션 풀 10개(Hikari 기본), Tomcat 기본값입니다.

## 시나리오

| 스크립트 | 하는 일 | 합격 조건 |
| --- | --- | --- |
| `coupon-issue.js` (순간 몰림) | 수량 `QUANTITY`장인 쿠폰에 서로 다른 회원 `VUS`명이 한 번씩 동시에 발급 | 201 정확히 `QUANTITY`건, 409 `COUPON_SOLD_OUT` 정확히 `VUS - QUANTITY`건, 5xx 0건 |
| `coupon-issue-sustained.js` (지속 부하) | 초당 `RATE`건씩 `DURATION` 동안 요청마다 다른 회원으로 발급(`constant-arrival-rate`) | 201이 수량 이하, 5xx 0건, `dropped_iterations` 0건 |

- 순간 몰림은 1초 안팎에 끝나 순간 최대 경합에서 수량이 지켜지는지 봅니다. Prometheus 수집 주기(5초)보다 짧아 Grafana에는 거의 보이지 않습니다.
- 지속 부하는 "이 속도를 버티는가"를 봅니다. 응답이 느려지면 응답을 기다리는 VU가 늘고, `MAX_VUS`(기본 RATE × 10, 약 10초어치 요청)가 모두 묶여 시작하지 못한 요청을 `dropped_iterations`로 셉니다. 이 값이 0보다 크면 그 속도가 처리 한계를 넘은 것입니다.
- 지속 부하의 VU는 `MAX_VUS`만큼 처음부터 만들어 둡니다(`PRE_VUS` 기본값). 부하 크기는 RATE가 정하므로 VU를 미리 만들어도 보내는 양은 같습니다. 측정 중에 VU를 새로 만들게 두면, 응답이 잠깐 늦어지는 순간 VU를 만드는 사이 요청을 놓쳐 한계 아래에서도 `dropped_iterations`가 생깁니다.
- 지속 부하의 수량은 기본이 "보낼 요청 수 전체"라 모든 요청이 실제 발급(행 잠금 + 회원 쿠폰 INSERT) 경로를 지납니다. `QUANTITY`를 작게 주면 중간에 소진돼 이후는 409가 됩니다.
- 응답 시간으로는 불합격 처리하지 않습니다(기준 측정이 목적). 합격 조건을 어기면 k6가 종료 코드 99로 끝납니다.

환경 변수(`-e 이름=값`)로 바꿀 수 있는 값과 기본값은 각 스크립트 머리 주석에 있습니다.

## 실행

k6는 설치하지 않고 Docker 이미지로 돌립니다. 저장소 루트에서 실행합니다.

```bash
# 0. 스택 기동(처음 한 번). 쿠폰 코드를 바꿨다면 --build
docker compose up -d --wait
until docker compose exec -T reservation-service curl -fs localhost:8083/actuator/health >/dev/null; do sleep 3; done

# k6 실행 명령. 운영체제에 맞는 것 하나를 고른다
# macOS·Linux
K6="docker run --rm --user $(id -u):$(id -g) --network ticket_ticket-net -v $PWD/script/coupon-load:/work grafana/k6:0.54.0 run"
# Windows Git Bash: 경로 자동 변환을 끄고 Windows 경로로 넘긴다
K6="docker run --rm --network ticket_ticket-net -v $(pwd -W)/script/coupon-load:/work grafana/k6:0.54.0 run"
export MSYS_NO_PATHCONV=1

# 1. 측정마다 초기화: local은 기동마다 테이블을 새로 만듭니다(ddl-auto: create)
docker compose restart reservation-service
until docker compose exec -T reservation-service curl -fs localhost:8083/actuator/health >/dev/null; do sleep 3; done

# 2. 준비 운동: 기동 직후의 JVM은 느려서 그대로 재면 시작 구간이 막힙니다. 측정할 속도에 가까운 세기로 30초 돌립니다
$K6 -e RATE=100 -e DURATION=30s /work/k6/coupon-issue-sustained.js

# 3-1. 순간 몰림
$K6 -e VUS=200 -e QUANTITY=50 --summary-export=/work/out/burst-200-1.json /work/k6/coupon-issue.js

# 3-2. 지속 부하. 한 RATE를 잰 뒤 다음 RATE는 1번부터 다시 합니다
$K6 -e RATE=150 -e DURATION=3m --summary-export=/work/out/sustained-150-1.json /work/k6/coupon-issue-sustained.js
```

- `--network ticket_ticket-net`: compose 네트워크에 붙어야 `reservation-service:8083`이라는 이름으로 찾아갑니다. reservation-service는 PC에 포트를 열지 않습니다.
- `--user`(macOS·Linux): k6 이미지는 root가 아닌 사용자로 돌아 `out/`에 결과를 쓰지 못할 수 있습니다. 내 사용자 ID로 실행합니다. Windows Docker Desktop에서는 필요 없습니다.
- `MSYS_NO_PATHCONV=1`(Windows Git Bash): 없으면 Git Bash가 `/work/...`를 Windows 경로로 바꿔 k6가 스크립트를 찾지 못합니다.
- **측정마다 재시작하는 이유**: 재시작하지 않고 이어서 재면 회원 쿠폰 행이 쌓이고(1시간에 약 18만 행) 같은 쿠폰 행의 수정 이력이 늘어 측정 중에 DB 정리 작업(autovacuum)이 돕니다. 뒤에 한 실행일수록 불리해져, 같은 RATE 130이 이어서 재면 실패하고 재시작 후에는 통과했습니다.
- 결과 JSON(`out/`)은 Git에 올리지 않습니다. `--summary-export`는 같은 이름이면 덮어쓰므로 실행마다 이름을 바꿉니다(`-1`, `-2`).
- 순간 몰림 `VUS`는 수천 명까지 가능하지만, 기본 Tomcat이 동시에 받는 연결은 약 8,300개(`max-connections` 8192 + `accept-count` 100)입니다. 이를 넘기면 연결 거절이 `http_req_failed`로 잡힙니다.

## Grafana로 보기

local compose에는 Prometheus(5초마다 수집)와 Grafana(http://localhost:3000)가 함께 뜨고, Prometheus 데이터 소스가 등록돼 있습니다. **Explore** 또는 대시보드 패널에서 아래 쿼리를 씁니다(Code 모드). 서버 쪽 지표라 k6의 p95는 없고 평균·최대만 있습니다.

| 보는 것 | PromQL |
| --- | --- |
| 초당 발급 요청 | `sum(rate(http_server_requests_seconds_count{application="reservation-service", uri="/api/coupons/{couponId}/issue"}[$__rate_interval]))` |
| 응답 코드별 요청(Legend `{{status}}`) | `sum by (status) (rate(http_server_requests_seconds_count{application="reservation-service", uri="/api/coupons/{couponId}/issue"}[$__rate_interval]))` |
| 평균 응답 시간 | `sum(rate(http_server_requests_seconds_sum{application="reservation-service", uri="/api/coupons/{couponId}/issue"}[$__rate_interval])) / sum(rate(http_server_requests_seconds_count{application="reservation-service", uri="/api/coupons/{couponId}/issue"}[$__rate_interval]))` |
| 최대 응답 시간 | `max(http_server_requests_seconds_max{application="reservation-service", uri="/api/coupons/{couponId}/issue"})` |
| DB 커넥션 사용·대기·최대 | `hikaricp_connections_active` / `hikaricp_connections_pending` / `hikaricp_connections_max` (모두 `{application="reservation-service"}`) |
| 화면 범위 안의 누적 발급 요청 | `sum(http_server_requests_seconds_count{application="reservation-service", uri="/api/coupons/{couponId}/issue"}) - sum(http_server_requests_seconds_count{application="reservation-service", uri="/api/coupons/{couponId}/issue"} @ start())` |

- 누적 쿼리는 화면 시간 범위의 시작(`@ start()`)부터 셉니다. 테스트 직전에 시간 범위의 From을 그 시각으로 고정하고 To를 `now`로 두면 이번 테스트 요청만 0부터 쌓입니다.
- DB 커넥션 **사용 중**이 최대(10)에 붙고 **대기 중**이 오르면 커넥션이 모자라 요청이 줄을 서고 있다는 뜻입니다.

## 결과 읽는 법

| 요약 항목 | 뜻 |
| --- | --- |
| `http_req_duration{name:issue}` | 발급 API 응답 시간. `p(95)`는 요청 95%가 이 시간 안에 끝났다는 뜻 |
| `http_reqs` | 전체 요청 수와 초당 요청 수(처리량). 쿠폰 생성 요청 1건이 포함됩니다 |
| `http_req_failed` | 예상하지 못한 응답(5xx·연결 오류) 비율. 409(소진)는 정상 결과로 빼고 셉니다 |
| `coupon_issued` | 발급 성공(201) 건수 |
| `coupon_issue_outcome{outcome:...}` | 발급 결과별 건수(예: `409:COUPON_SOLD_OUT`, `transport_error`) |
| `dropped_iterations` | 지속 부하에서 정한 속도를 따라가지 못해 시작하지 못한 요청 수 |

## 결과 기록

결과 파일은 docs에 추가하지 않고 이슈(#238) 댓글에 남깁니다. 비교할 수 있게 다음을 함께 적습니다.

- 커밋, 장비(OS·CPU·메모리), 스크립트, VUS·QUANTITY 또는 RATE·DURATION
- 발급 API avg·p95·max, 처리량, 5xx 수, 201·409 건수, `dropped_iterations`
- Grafana에서 본 DB 커넥션 사용·대기
- 측정 조건: reservation-service 직접 호출, 측정 전 재시작·준비 운동 여부
