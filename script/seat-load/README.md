# 좌석 선점 k6 부하 시나리오 (#172)

좌석 API(조회·선점·해제)에 동시 요청을 보내 정합성(같은 좌석은 한 명만)과 기준 측정값(응답 시간·처리량)을 확인합니다. 선점 잠금 방식을 바꿀 때 이 시나리오로 다시 재서 비교합니다.

## 측정 조건

- **reservation-service 직접 호출**: Gateway를 거치지 않습니다. Gateway가 JWT를 검사한 뒤 넣어 주는 회원 헤더(`X-User-Id`·`X-Username`·`X-User-Role`)를 k6가 직접 채웁니다. 그래서 JWT·라우팅 비용은 빠지고 좌석 로직과 DB 잠금 비용만 잽니다. 실제 사용자 경로의 응답 시간이 아닙니다.
- **판매 검증 포함**: 선점할 때 reservation-service가 부르는 공연 판매 검증(Feign)은 포함됩니다. performance-service도 떠 있어야 합니다.
- **local 시드 좌석**: local 프로필의 회차 1~3(회차당 40석, #124)을 씁니다. local은 대기열이 꺼져 있어(`QUEUE_ENABLED=false`) 입장 토큰이 필요 없습니다.

## 시나리오

| SCENARIO | 회차 | 하는 일 | 합격 조건 |
| --- | --- | --- | --- |
| `hot` | 1 | VU(가상 사용자) 100명이 서로 다른 회원으로 같은 좌석을 한 번씩 동시에 선점 | 201 정확히 1건, 409 `SEAT_ALREADY_TAKEN` 99건, 5xx 0건 |
| `loop` | 2 | VU 50명이 1분 동안 조회 → 비어 있는 좌석 선점 → 바로 해제를 반복 | 1인 1장 초과 0건, 해제 204 1건 이상, 5xx 0건 |

`loop`에서 고르는 사이 다른 VU가 먼저 잡아 생기는 409 `SEAT_ALREADY_TAKEN`은 정상 경합입니다. 응답 시간으로는 불합격 처리하지 않습니다(기준 측정이 목적). 합격 조건을 어기면 k6가 종료 코드 99로 끝납니다.

## 실행

k6는 설치하지 않고 Docker 이미지로 돌립니다. 저장소 루트에서 실행합니다.

```bash
# 0. 스택 기동(처음 한 번). 좌석 코드를 바꿨다면 --build
docker compose up -d --wait

# 1. 초기화: local은 기동마다 테이블을 새로 만들고 시드를 다시 넣으므로 재시작하면 좌석이 모두 비고 예약이 없어집니다
docker compose restart reservation-service
until docker compose exec -T reservation-service curl -fs localhost:8083/actuator/health >/dev/null; do sleep 3; done

# 2. 준비 운동: 재시작 직후의 JVM은 느려서 그대로 재면 값이 크게 나옵니다. 측정에 쓰지 않는 회차 3으로 20초 돌립니다
K6="docker run --rm --user $(id -u):$(id -g) --network ticket_ticket-net -v $PWD/script/seat-load:/work grafana/k6:0.54.0 run"
$K6 -e SCENARIO=loop -e SCHEDULE_ID=3 -e DURATION=20s /work/k6/seat-hold.js

# 3. 측정(시나리오 하나). 다른 시나리오를 재려면 1번부터 다시 합니다
$K6 -e SCENARIO=hot --summary-export=/work/out/hot.json /work/k6/seat-hold.js
$K6 -e SCENARIO=loop --summary-export=/work/out/loop.json /work/k6/seat-hold.js
```

- `--user`: k6 이미지는 root가 아닌 사용자로 돌아 `out/`에 결과를 쓰지 못합니다. 내 사용자 ID로 실행합니다.
- `--network ticket_ticket-net`: compose 네트워크에 붙어야 `reservation-service:8083`이라는 이름으로 찾아갑니다.
- 결과 JSON(`out/`)은 Git에 올리지 않습니다.

환경 변수(`-e 이름=값`)로 바꿀 수 있는 값은 `k6/seat-hold.js` 머리 주석에 있습니다(VU 수, 회차, 시간 등).

## 결과 읽는 법

| 요약 항목 | 뜻 |
| --- | --- |
| `http_req_duration{name:seats·hold·release}` | API별 응답 시간. `p(95)`는 요청 95%가 이 시간 안에 끝났다는 뜻 |
| `http_reqs` | 전체 요청 수와 초당 요청 수(처리량) |
| `http_req_failed` | 예상하지 못한 응답(5xx·연결 오류) 비율. 409는 정상 결과로 빼고 셉니다 |
| `seat_hold_created` | 선점 성공(201) 건수 |
| `seat_hold_outcome{outcome:...}` | 선점 결과별 건수(예: `409:SEAT_ALREADY_TAKEN`) |
| `seat_release_outcome{outcome:204}` | 해제 성공 건수 |
| `seat_purchase_limit_exceeded` | 1인 1장 초과 건수(`loop`에서 0이어야 함) |

## 결과 기록

결과 파일은 docs에 추가하지 않고 이슈·PR 댓글에 남깁니다. 비교할 수 있게 다음을 함께 적습니다.

- 커밋, 장비(OS·CPU·메모리), 시나리오, VU 수, 지속 시간
- API별 avg·p95, 처리량, 5xx 수, 201·409 건수
- 측정 조건: reservation-service 직접 호출, 판매 검증 포함, 준비 운동 여부
