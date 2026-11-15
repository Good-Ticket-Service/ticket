# 좌석 선점 k6 부하 시나리오 (#172, 처리량 시나리오 추가)

좌석 API(조회·선점·해제)에 동시 요청을 보내 정합성(같은 좌석은 한 명만)과 기준 측정값(응답 시간·처리량)을 확인합니다. 선점 잠금 방식을 바꿀 때 이 시나리오로 다시 재서 비교합니다.

## 측정 조건

- **reservation-service 직접 호출**: Gateway를 거치지 않습니다. Gateway가 JWT를 검사한 뒤 넣어 주는 회원 헤더(`X-User-Id`·`X-Username`·`X-User-Role`)를 k6가 직접 채웁니다. 그래서 JWT·라우팅 비용은 빠지고 좌석 로직과 DB 잠금 비용만 잽니다. 실제 사용자 경로의 응답 시간이 아닙니다.
- **판매 검증 포함**: 선점할 때 reservation-service가 부르는 공연 판매 검증(Feign)은 포함됩니다. performance-service도 떠 있어야 합니다.
- **local 시드 좌석**: `hot`·`loop`는 local 시드 회차 1~3(회차당 40석, #124), `spread`는 부하용 시드 회차 900(1,000석, 판매 제외 4석, #173)을 씁니다. 둘 다 local 기동 때 자동으로 들어갑니다. local은 대기열이 꺼져 있어(`QUEUE_ENABLED=false`) 입장 토큰이 필요 없습니다.

## 시나리오

| SCENARIO | 회차 | 하는 일 | 합격 조건 |
| --- | --- | --- | --- |
| `hot` | 1 | VU(가상 사용자) 100명이 서로 다른 회원으로 같은 좌석을 한 번씩 동시에 선점 | 201 정확히 1건, 409 `SEAT_ALREADY_TAKEN` 99건, 5xx 0건 |
| `loop` | 2 | VU 50명이 1분 동안 조회 → 비어 있는 좌석 선점 → 바로 해제를 반복 | 1인 1장 초과 0건, 해제 204 1건 이상, 5xx 0건 |
| `spread` | 900 | VU 500명이 1분 동안 각자 정해진 다른 좌석으로 선점 → 해제를 반복(서로 다른 좌석 처리량) | 1인 1장 초과 0건, 해제 204 1건 이상, 5xx 0건. VU가 996명 이하면 409도 0건 |

`spread`는 VU마다 좌석을 하나씩 나눠 주므로 경합 없이 선점·해제 자체의 처리량을 봅니다. 좌석 조회는 시작할 때 한 번만 합니다. VU가 판매 가능 좌석(996석)보다 많으면 좌석을 나눠 써서 409가 섞입니다.

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
$K6 -e SCENARIO=spread -e VUS=500 --summary-export=/work/out/spread-500.json /work/k6/seat-hold.js
```

- `--user`: k6 이미지는 root가 아닌 사용자로 돌아 `out/`에 결과를 쓰지 못합니다. 내 사용자 ID로 실행합니다.
- `--network ticket_ticket-net`: compose 네트워크에 붙어야 `reservation-service:8083`이라는 이름으로 찾아갑니다.
- 결과 JSON(`out/`)은 Git에 올리지 않습니다.

환경 변수(`-e 이름=값`)로 바꿀 수 있는 값은 `k6/seat-hold.js` 머리 주석에 있습니다(VU 수, 회차, 시간 등).

### 실시간으로 보기(웹 대시보드)와 HTML 보고서

k6에 들어 있는 웹 대시보드를 켜면 측정하는 동안 브라우저(http://localhost:5665)에서 그래프로 볼 수 있고, 끝나면 같은 그래프를 HTML 보고서로 남깁니다.

```bash
docker run --rm --user $(id -u):$(id -g) --network ticket_ticket-net -p 127.0.0.1:5665:5665 \
  -v $PWD/script/seat-load:/work \
  -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 -e K6_WEB_DASHBOARD_PERIOD=1s \
  -e K6_WEB_DASHBOARD_EXPORT=/work/out/spread-500-report.html \
  grafana/k6:0.54.0 run -e SCENARIO=spread -e VUS=500 /work/k6/seat-hold.js
```

- `K6_WEB_DASHBOARD_HOST=0.0.0.0`: 컨테이너 밖에서 열 수 있게 합니다. 포트는 `127.0.0.1:5665`에만 공개합니다.
- `K6_WEB_DASHBOARD_PERIOD=1s`: 그래프를 1초마다 갱신합니다. 기본값(10초)으로 두면 짧은 측정은 집계가 한 번도 돌지 않아 보고서가 저장되지 않습니다.
- 대시보드는 측정이 끝나면 닫힙니다. 놓쳤으면 HTML 보고서를 엽니다. 보고서도 `out/`에 남아 커밋되지 않습니다.
- 대시보드가 1초마다 집계하느라 k6가 CPU를 조금 더 씁니다. 비교용 기준값은 대시보드를 끄고 잽니다.

### 기준값을 잴 때

- 시나리오·VU마다 3회(매번 1~3번부터) 재서 중앙값을 씁니다. local은 같은 조건에서도 회차마다 ±25% 안팎 흔들립니다(#234).
- 측정 중에는 노트북 절전을 끕니다. 절전으로 WSL이 멈추면 그 시간이 응답 시간에 그대로 잡혀 회차가 무효가 됩니다.

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

## 선점 처리량·연결 풀 단독 측정 (#434)

`hold-pool.sh`는 위 k6 시나리오와 별개로, 예매 서비스만 떼어 선점 처리량을 잽니다. 연결 풀 크기나 부하 크기를 바꿔 가며 비교할 때 씁니다.

- **구성**: Docker Compose 스택 없이 PostgreSQL·Kafka·Redis 컨테이너와 예매 서비스 JVM(`HoldBenchServerMain`), 부하 생성기 JVM(`HoldBenchLoadMain`)을 따로 띄웁니다. 코드는 `backend/reservation-service/src/bench/java`에 있습니다.
- **설정**: 예매 서비스는 config-repo의 `application.yml`·`reservation-service.yml`을 그대로 읽습니다. 판매 검증만 5ms 뒤 허용하는 가짜로 바꿉니다. Gateway가 넣는 회원 헤더는 부하 생성기가 직접 넣습니다.
- **CPU 고정**: 통합시험과 같게 예매 서비스 2코어(`SERVER_CPUS`, 기본 2-3), DB 2코어(`PG_CPUS`, 4-5), 부하 생성기(`LOAD_CPUS`, 6-9), Kafka·Redis(`SIDE_CPUS`, 10-11)로 나눕니다.
- **부하**: 다른 좌석 선점을 초당 250건 고르게 보내면서, 매초 250명이 각자 다른 좌석을 동시에 선점합니다(#390 대조군과 같음). 성공하면 0.5초 뒤 해제합니다.
- **순서**: 풀 크기마다 빈 스키마에서 예매 서비스를 새로 띄우고, 집계하지 않는 예열 회차(0) 뒤 `REPS`번 잽니다. 회차마다 새 좌석을 만들고, 예열 20초 뒤 60초를 집계합니다.

```bash
# 저장소 루트에서. 같은 PC에서 다른 Docker 측정·Gradle 작업을 함께 돌리지 않습니다
POOLS="10 15 20" REPS=3 script/seat-load/hold-pool.sh

# 짧은 확인, 부하 크기 바꾸기(초당 다른 좌석 L건 + burst L명)
LOAD_JAVA_OPTS="-Dbench.warmup=10 -Dbench.measure=30 -Dbench.rate=200 -Dbench.burst=200" POOLS=10 REPS=3 script/seat-load/hold-pool.sh
```

결과는 `script/seat-load/out/hold-pool-<시각>/`에 남습니다(저장소 추적 제외).

| 파일 | 내용 |
| --- | --- |
| `summary.csv` | 회차별 다른 좌석·burst 선점 p99, 늦게 보낸 비율, 연결 대기 최대, 서버·부하 생성기·DB CPU, DB 연결 최대 |
| `pool-N-rep-K.json` | 회차 원본: 결과별 건수, 지연 분포, 1초 서버 표본, 초별 지연 |
| `pool-N-rep-K-timeline.jsonl` | 0.2초 표본: 서버 CPU, 처리 중인 요청 수와 가장 오래된 요청, DB 세션 상태·대기 종류, autovacuum |
| `pool-N-rep-K-dump-*.txt`, `-loaddump-*.txt` | 처리 중인 요청이 0.5초를 넘은 순간의 서버·부하 생성기 스레드 덤프(회차당 최대 5번) |
| `postgres.log`, `pg-tables-*.txt` | autovacuum·checkpoint·잠금 대기 로그, 회차 뒤 테이블 크기와 autovacuum 시각 |

- `PG_EXTRA_ARGS`(예: `-c synchronous_commit=off`)는 원인을 가르는 진단에만 쓰고 측정 결과에는 쓰지 않습니다.
- `PG_DOCKER_ARGS`·`PG_MEM`(예: `PG_DOCKER_ARGS="--tmpfs /var/lib/postgresql/data:rw,size=6g" PG_MEM=8g`, DB 데이터를 메모리에 두기)도 진단에만 씁니다. tmpfs는 컨테이너 메모리 한도에 들어가므로 `PG_MEM`을 함께 올립니다.
- `SERVER_JAVA_OPTS`는 예매 서비스 JVM 옵션입니다. `-Dbench.sale.delay.ms=10`으로 판매 검증 가짜 지연을 바꿉니다(기본 5ms).
- `BENCH_CP_FILE`에 미리 만든 클래스패스 파일을 넘기면 작업 폴더를 빌드하지 않고 그 클래스패스로 잽니다.

### 부하 단계 번갈아 재기

`LEVELS`에 단계를 여러 개 주면 같은 서버에서 회차마다 순서를 돌려 가며 잽니다. 시간이 지나며 느려지는 영향을 단계마다 고르게 나누기 위해서입니다.

```bash
# 다른 좌석 초당 L건 + burst L명을 150·200·250으로 번갈아
LEVELS="150 200 250" POOLS=10 REPS=3 script/seat-load/hold-pool.sh
```

### 모의 매진 (#447)

`BENCH_MODE=sellout`이면 회차마다 25,000석(`SELLOUT_SEATS`)을 매진까지 팝니다. 사용자는 좌석 상태를 1초마다 조회하고, 1\~2초 고른 뒤 선점하고, 409면 상태를 바로 다시 받아 고르며, 같은 구역에서 3번 연달아 놓치면 빈 좌석이 있는 다른 구역으로 옮깁니다. 인기 구역 70%, 결제 포기 20%가 기본값이고 `-Dbench.hot.percent` 등으로 바꿉니다. 판매 시간 상한은 180초(`-Dbench.sellout.seconds`)입니다.

`LEVELS`의 단계 형식은 `사용자수[x회차수][@초당입장]`입니다.

- `2000`: 회차 1개, 안에 2,000명. 빈자리가 나는 즉시 다음 사람이 들어옵니다(입장 제한 없음).
- `1000x3`: 회차 3개를 같은 서버에서 동시에, 회차마다 1,000명.
- `2000@400`: 대기열(`admit.lua`)처럼 초당 400명만 들여보내고, 남는 입장분은 2초 분량까지만 쌓습니다. 앞 숫자는 안에 있을 수 있는 인원 상한입니다.

```bash
BENCH_MODE=sellout LEVELS="2000@300 2000@400" SERVER_JAVA_OPTS="-Dbench.sale.delay.ms=10" \
    LOAD_JAVA_OPTS="-Dbench.sale.delay.ms=10" POOLS=10 REPS=3 script/seat-load/hold-pool.sh
```

#### 선점 위치 번갈아 재기 (#449)

단계 맨 뒤에 `:DB` 또는 `:REDIS`를 붙이면 그 회차를 시작하기 전에 측정 서버의 선점 위치 스위치를 바꿉니다. 모드마다 서버를 다시 띄우면 JVM 예열 상태가 달라지므로, 같은 서버에서 회차마다 순서를 돌려 가며 잽니다. 스위치를 바꾸는 엔드포인트(`/bench/hold-mode`)는 측정 서버(`HoldBenchServerMain`)에만 있습니다.

```bash
BENCH_MODE=sellout LEVELS="1000x3:DB 1000x3:REDIS" SERVER_JAVA_OPTS="-Dbench.sale.delay.ms=10" \
    LOAD_JAVA_OPTS="-Dbench.sale.delay.ms=10" POOLS=10 REPS=3 script/seat-load/hold-pool.sh
```

- REDIS 모드에서 결제하는 사용자는 선점 뒤 5\~15초(`-Dbench.convert.min`·`-Dbench.convert.max`, ms)에 결제하기를 누릅니다. 짧을수록 DB 쓰기가 오픈 직후에 몰려 Redis 효과가 작게 나오는 보수적인 가정입니다.
- 결제 포기(기본 20%)는 REDIS 모드에서 절반이 결제하기 전에, 절반은 결제하기 뒤 10\~30초에 예약을 해제합니다. DB 모드는 지금처럼 선점 뒤 10\~30초에 해제합니다.
- 결제 준비·승인은 두 모드 모두 재지 않습니다. 결제하기 뒤에는 두 모드가 같은 경로이기 때문입니다.
- 결과에 결제하기 응답(`convert_ms`, `convert_outcomes`), 선점이나 결제하기 응답이 0.5초를 넘은 초(`stalled_seconds_with_convert`), 서버의 Redis 선점 지표(`redis_metrics`: 결과별 수, 명령별 Redis 호출 시간 p50·p99·p999)가 더해집니다. Redis 지표는 회차 시작·끝의 차이입니다.

결과는 `pool-10-M2000xK1-A400-rep-1.json`처럼 단계가 이름에 붙고, 회차별 요약은 `sellout-summary.csv`(판매 수, 매진 여부·시간, 1명당 시도 수, 선점 성공·409 p99, 가장 나쁜 10초 구간 p99, 멈춘 초 수, 좌석 조회 p99, 서버·DB CPU)에 남습니다.
- 결과 해석과 처리 한도는 #434 댓글에 있습니다.
