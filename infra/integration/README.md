# 로컬 통합시험 실행

이슈 #256의 서비스 연동·혼합 부하·회차 간 영향·장애 복구 시험이다. 저장소 루트에서 실행한다. 서비스별 단독 성능 시험과 CPU 조건이 다르므로 처리량을 직접 비교하지 않는다.

## 준비 환경

- Git, Python 3, Docker Compose를 사용할 수 있는 Docker Desktop(Linux containers)이 필요하다. 애플리케이션은 Docker 안에서 JDK 17로 빌드한다.
- 아래 명령은 Windows PowerShell 기준이다. 수집기의 Windows 메모리·paging 항목도 Windows 기준이다. 다른 OS에서는 해당 항목이 누락될 수 있으므로 수집 성공 지표를 확인한다.
- 기본 CPU 배치는 Docker VM의 논리 CPU 0~7을 사용한다. 8개 미만이면 `compose-inputs.example`의 `INTEGRATION_*_CPUS`를 실제 CPU 번호에 맞게 바꾼다. 동일 묶음 서비스가 CPU를 공유한다. 별도의 CPU quota는 없다.
- 응답이 큰 시험(예: 3000석 legacy)은 k6 2코어가 병목이 될 수 있다. Docker CPU가 10개 이상이면 `INTEGRATION_LOAD_CPUS=6-9`로 올려서 측정한다. 결과의 `resources.k6.cpuset`으로 측정 때 k6 코어를 확인하고, 코어 수가 다른 결과끼리 직접 비교하지 않는다.
- 컨테이너 메모리 상한 합계는 k6 포함 약 16.34GiB다(Kafka UI 제외). 이것은 예약량이 아니다. VM·OS·빌드·페이지 캐시 여유도 필요하다. swap·OOM·발생기 자원 부족이 생기면 해당 부하 결과를 서버 용량으로 해석하지 않는다.
- 기존 단독 부하 시험은 중단한 상태에서 진행한다. 프로젝트 이름은 `ticket-integration`이며 시험용 DB·볼륨을 사용한다.
- `compose-inputs.example`은 공개된 로컬 시험용 값이다. 운영 환경이나 외부에 노출된 서버에 사용하지 않는다. 실제 `.env`는 읽지 않는다.

## 최초 소스 준비와 기동

오늘 시험한 서비스 소스는 develop의 `adff84290770074724a1dcf9c6738d04fc200a91`이다. 실행 도구는 `script/integration/source-*` worktree가 정확히 하나이고 그 소스가 clean인지 검사한다. 이 폴더를 Git에 추가하지 않는다.

```powershell
git fetch origin develop
git worktree add --detach script/integration/source-adff842 adff84290770074724a1dcf9c6738d04fc200a91
python infra/integration/compose.py config
python infra/integration/compose.py up -d --build
python infra/integration/compose.py ps
```

worktree 생성은 최초 한 번만 실행한다. 이미 있으면 같은 명령으로 다시 만들지 않는다. 모든 필수 서비스가 healthy가 될 때까지 기다린다. 새 커밋으로 시험할 때는 소스를 명시적으로 갱신하고 결과의 `source_sha`를 비교한다. 설정 파일을 바꾼 뒤 컨테이너에 반영하려면 `compose.py up -d`를 실행한다.

| 묶음 | 논리 CPU | 컨테이너 메모리 상한 |
|---|---|---|
| 진입·조회 | 0–1 공유 | Caddy 256MiB, Config·Eureka 각 384MiB, Gateway·회원 각 768MiB, 공연 1GiB |
| 예매·결제 | 2–3 공유 | 예매·결제 각 1.75GiB |
| 데이터·관측 | 4–5 공유 | PostgreSQL 2GiB, Redis 384MiB, Kafka·Fake PG 각 768MiB, Prometheus·Grafana 각 512MiB |
| 관측 보조 | 4–5 공유 | Alertmanager 128MiB, alert-mock 64MiB, PostgreSQL exporter 96MiB, Redis exporter 64MiB, Kafka exporter 128MiB |
| 부하 발생기 | 6–7 | k6 4GiB, 시험 중에만 실행 |
| 선택 도구 | 4–5 공유 | Kafka UI 256MiB, 기본 실행 제외 |

DB는 PostgreSQL 인스턴스 하나에서 서비스별 스키마·계정을 사용한다. 앱 Hikari 풀 최대값은 10이다. 기본 대기열은 OFF다. 상세 JVM 힙과 서비스 설정은 `compose.integration.yml`을 기준으로 한다.

## 관측 시작

별도 터미널에서 수집기를 켜 두고 다른 터미널에서 시험한다.

```powershell
python infra/integration/observe.py
```

- 수집기 확인: `http://localhost:19100/metrics`
- Grafana: `http://localhost:13000` — `admin`, 비밀번호는 `compose-inputs.example`의 `GRAFANA_ADMIN_PASSWORD` 값.
- Prometheus: `http://localhost:19090`
- 통합 API: `http://localhost:18080`

Grafana에서 Integration 폴더를 열고 시험 ID와 시간 범위를 선택한다. `up{job="integration-host"}`와 세부 수집 성공 지표를 확인한다. 앱의 지연·Hikari·Tomcat, DB 세션 대기, 컨테이너 메모리·swap·OOM, 호스트 메모리 압박을 함께 본다. 지표가 없으면 0으로 해석하지 않는다.

### trace·로그·프로파일 함께 보기 (선택)

지표만으로는 어느 코드가 CPU나 DB 커넥션을 썼는지 알 수 없다. 그때 `observability` 프로필을 켠다. 앱 이미지는 그대로 두고 Java 에이전트만 붙인다.

```powershell
$env:COMPOSE_PROFILES='observability'
python infra/integration/compose.py up -d
```

- 처음 켤 때 Maven Central에서 OpenTelemetry Java 에이전트와 Pyroscope 에이전트를 `script/integration/agents/`로 받고 SHA-256을 확인한다. 받는 것은 `up`·`run`뿐이라 다운로드가 실패해도 `stop`·`ps`·`logs`는 된다.
- 예약·결제 서비스에 JFR 기록(최근 30분·200MB, 스택 깊이 256)과 Hikari 누수 탐지(커넥션을 2초 넘게 쥐면 WARN과 스택)도 함께 켜진다. 기본 실행에서는 둘 다 꺼져 있어 이전 측정과 조건이 같다.
- JFR은 컨테이너가 멈출 때 `script/integration/results/jfr/`에 저장된다. 다시 뜨면 같은 파일을 비우므로 멈춘 뒤 바로 다른 곳에 복사한다. 볼 때는 `jfr print --stack-depth 256`을 쓴다(기본 5단계).
- 같은 터미널에서 시나리오를 실행한다. 이 변수가 없으면 k6가 수집기와 같은 CPU 7을 쓴다.
- 프로필을 끄려면 변수를 지우고 `compose.py up -d`를 다시 실행한다. 앱이 에이전트 없이 다시 뜬다.
- 수집기 컨테이너는 `python infra/integration/compose.py stop tempo loki alloy pyroscope`로 멈춘다.

| 도구 | Grafana 데이터 소스 | 보는 것 |
|---|---|---|
| Tempo | Tempo | 요청 하나의 gateway → 서비스 → 다른 서비스 → DB 구간별 시간 |
| Loki·Alloy | Loki | 모든 컨테이너 로그. `{service="reservation-service", level="WARN"}`처럼 거른다. 앱 로그는 JSON이다 |
| Pyroscope | Pyroscope | 서비스별 CPU·할당·락 플레임 그래프. 시간 범위를 지표와 맞춘다 |

- 로그의 `trace_id`를 누르면 Tempo trace로, trace에서 "Logs for this span"을 누르면 그 trace의 로그로 이동한다.
- 수집기 넷은 CPU 7, k6는 CPU 6을 쓴다. DB 코어(4–5)와 섞지 않기 위해서다. 메모리 상한은 Tempo·Pyroscope 각 1GiB, Loki 512MiB, Alloy 256MiB로 합계 약 2.75GiB가 늘어난다.
- 에이전트가 계측하는 만큼 CPU와 JIT 부담이 늘어난다. 이 프로필로 잰 처리량은 기본 실행과 비교하지 않는다.

## 시나리오 실행

한 번에 한 시험만 실행한다. 모든 시험은 신규 계정·공연·좌석을 준비하며 기존 시험 데이터를 삭제하지 않는다. 반복 실행으로 데이터와 디스크 사용량이 증가한다. 준비·사전 로그인·예열·종료 후 대기·DB 검증은 60초 측정 구간에 포함되지 않는다.

**외부 컨테이너 검사(#336).** 모든 시나리오는 데이터를 준비하기 전과 측정을 마친 뒤 `ticket-integration` 밖에서 실행 중인 컨테이너를 찾는다. 통합시험 CPU(`compose config`의 cpuset 합집합)와 cpuset이 겹치거나 cpuset이 비어 있는(모든 CPU를 쓰는) 컨테이너가 있으면, 이름·프로젝트·cpuset·재시작 정책·재시작 횟수를 출력하고 측정을 시작하지 않는다. compose 라벨이 없는 `docker run` 탐침도 외부로 본다. 외부 컨테이너는 자동으로 멈추지 않으므로 무엇을 멈출지 직접 정한다. CPU가 겹치지 않는 외부 컨테이너는 경고 한 줄만 남긴다. 결과는 `result.json`의 `foreign_containers`(측정 전후 목록)와 `checks.no_foreign_containers`에 남는다. `--allow-foreign`을 주면 경고를 크게 출력하고 측정을 진행하지만, 그 회차는 `checks.no_foreign_containers=false`라 verdict가 FAIL이다. S3는 이 옵션을 쓴 회차에서만 기준선 진행 판단에서 이 항목을 뺀다. 측정 도중 생긴 외부 컨테이너는 수집기의 `integration_foreign_running_containers`로 확인한다.

### 1. 정상 예매 연결·최종 상태 검증

```powershell
python infra/integration/scenario1.py --users 10
```

가입·로그인부터 공연 조회·좌석 선점·결제·예매 확정까지 연결하고 DB 상태를 대조한다. 순차 기능 시험이며 TPS 측정용이 아니다.

### 2. 조회 중심 혼합 부하

```powershell
python infra/integration/scenario2.py --rate 25 --duration 60 --seats 15000
python infra/integration/scenario2.py --rate 50 --duration 60 --seats 15000
python infra/integration/scenario2.py --rate 100 --duration 60 --seats 15000
python infra/integration/scenario2.py --rate 200 --duration 60 --seats 15000
python infra/integration/scenario2.py --rate 250 --duration 60 --seats 15000
python infra/integration/scenario2.py --rate 300 --duration 60 --seats 15000
```

`rate`는 API TPS가 아닌 시작 여정 수/s다. 비율은 공연 조회 70%, 좌석 탐색 20%, 예매 10%다. 좌석 탐색은 전체 좌석 배치도를 3~5회 조회한다. TOSS·쿠폰 미사용이며 예매 계정마다 겹치지 않는 좌석을 배정한다. 의도적인 좌석 경쟁 시험이 아니다.

시나리오2·3은 k6 setup에서 인증 계정을 미리 로그인시킨다. JWT는 k6 메모리에서만 유지한다. 준비 단계에서 Fake PG TOSS CONFIRM 지연을 300ms로 설정·검증한다. 이 설정은 Fake PG 프로세스에 남으므로 다른 시험 전에 조건을 확인한다. 다른 장애 확률이 켜져 있으면 정상 부하 시험은 시작하지 않는다.

좌석 수를 바꾸면 조회 데이터량도 바뀐다. 비교 시 좌석·CPU·메모리·PG·VU 조건을 고정한다. 필요한 좌석은 최소 `ceil(rate * duration / 10) + 2`개다. 예를 들어 300/s·60초에는 1,802석 이상이 필요하다.

기본 VU는 `max(200, rate*6)`, 최대 VU는 `max(1000, VU, rate*7)`다. `--vus`, `--max-vus`로 조정할 수 있다. VU를 늘리면 발생기 메모리가 증가한다. dropped iterations가 발생하면 앱 지연과 발생기 자원을 같이 확인한다.

**좌석 경계 측정 조건(#287).** 기본값(`--layout-body parse --accept-encoding default`)은 이전 측정과 같다.

- `--layout-body skip`(`--seat-api split` 전용): k6가 layout 본문을 받아 두지 않는다(`responseType: 'none'`). 큰 회차에서 k6 메모리가 아니라 서버 지표로 한계를 보기 위한 조건이다. layout은 ETag(configVersion)로만 확인하고, status 검증 기준과 배정 좌석의 비트 위치는 측정 전에 Python이 layout을 한 번 받아 `input.json`에 넣는다.
- `--accept-encoding identity|gzip`: 모든 요청에 이 헤더를 붙인다. `default`는 헤더를 보내지 않는다.
- 측정 중 layout 응답이 요청한 압축 조건과 다르면 `checks.layout_encoding_as_requested`가 실패한다. layout은 강한 ETag라 Tomcat 압축이 걸리지 않는다(TS-9, Tomcat `CompressionConfig#useCompression`의 강한 ETag 조건). 지금은 `gzip`을 요청해도 압축본이 오지 않아 이 check가 실패하므로, 경계 측정은 `identity`로 한다. 압축 측정은 #287 2번(사전 압축)에서 한다.
- 이 옵션을 쓴 회차는 `layout-cost.json`(layout 전송 바이트 identity·gzip, Content-Length 유무), `result.json`의 `layout`·`measurements.layout`을 남긴다. layout은 chunked로 와서 Content-Length가 없으므로 크기는 `layout-cost.json`을 기준으로 한다. 측정 구간의 reservation·gateway·caddy CPU는 모든 회차의 `server_cpu_cores_measure`에 남는다.

사용자 1명 기준 layout 비용(전송 바이트·시간·파싱 시간)은 부하 없이 따로 잰다. 시나리오2 결과 폴더의 시험 계정으로 로그인하며 데이터를 만들지 않는다. 파싱 시간은 Python 기준이라 좌석 수 사이의 상대 비교로만 쓴다.

```powershell
python infra/integration/layout_probe.py --result script/integration/results/scenario2-<run_id> --repeat 5
```

### 3. 인기 회차 부하가 다른 회차에 미치는 영향

```powershell
python infra/integration/scenario3.py --rate 100 --duration 60 --seats 15000
```

먼저 B 공연의 예매 1건/s를 60초 측정하고, 이어서 A 공연의 혼합 여정 100/s와 B의 예매 1건/s를 동시에 60초 측정한다. 두 구간 사이 기본 냉각은 20초다. A 좌석은 `--seats`, B는 기본 1,000석이다. B 유입은 `--probe-rate`(1~5)로 바꾸되 두 구간 모두 같은 값을 사용한다. B 1/s·60초의 약 60건으로 p99를 확정적으로 해석하지 않는다.

### 4. 공유 DB 중단 후 전체 흐름 복구

```powershell
python infra/integration/scenario4.py --dry-run
python infra/integration/scenario4.py
```

기본값은 혼합 5여정/s, 정상 60초 → DB 중단 25초 → 복구 관찰 180초다. `--dry-run`으로 대상과 계획부터 확인한다. 이 시험은 `ticket-integration`의 실행 중 PostgreSQL 컨테이너만 일시 중단하고 재시작한다. 중단 시 전체 서비스가 공유 DB의 영향을 받는다. 각 경로의 사전 인증 probe, 진행 중 예매, 신규 요청과 복구 후 상태를 확인한다. 원래 중지된 DB·다른 프로젝트 DB는 허용하지 않으며 볼륨을 삭제하지 않는다. 복구 상태가 `RESTORE_REQUIRED`이면 출력과 `result.json`을 확인하고 DB를 복구한다.

## 결과와 종료

결과는 로컬 `script/integration/results/`, 원시 관측·Docker 이벤트는 `script/integration/observation-*`에 저장된다. `scenario*-metrics.prom`, PID와 소스 worktree도 모두 런타임 파일이다. 이 전체 경로는 Git에서 제외한다. Grafana의 실제 계정·저장 데이터·스냅샷·조회 결과도 PR에 포함하지 않는다.

`result.json`에서 시작률·누락·오류·완료 지연·클라이언트 증거와 DB 대조를 함께 확인한다. HTTP 타임아웃은 실제 결제 실패를 뜻하지 않는다. 종료 직후 HOLD·결과 불명 상태가 남으면 복구 후 최종 상태를 다시 확인한다. PASS는 스크립트 실행·정합성 판정이며 합의된 지연 SLO나 최대 처리량 보장이 아니다. 로그에는 로그인 토큰을 넣지 않는다.

수집기 터미널은 Ctrl+C로 종료하고 컨테이너는 다음으로 중지한다. 볼륨과 시험 데이터는 유지된다.

```powershell
python infra/integration/compose.py stop
```
