# 최종 발표 시연 — 콘서트 티켓팅 재현

인기 공연의 티켓 오픈 순간을 재현해 90초 시연 영상을 찍기 위한 스크립트다. 봇 수천 명이 대기열에 몰리고, 좌석을 두고 경쟁하고, 결제해서 매진되기까지의 흐름을 실제 서비스(통합시험 환경)와 프론트 화면으로 보여 준다.

- 실행 환경: 로컬 통합시험 환경(`infra/integration`). 서비스·DB·Kafka가 모두 Docker로 뜬다.
- 결제는 Fake PG(토스 형식)로 처리한다. 계정·비밀번호는 모두 공개된 로컬 시험용 값이다.

## 1. 시연 시나리오 (90초)

화면을 둘로 나눈다. 왼쪽은 프론트(사용자 화면), 오른쪽은 Grafana·Kafka UI(지표)다.

| # | 시간 | 왼쪽: 화면 흐름 | 오른쪽: 지표 |
|---|---|---|---|
| 1 | 0~12초 | 판매자가 회차 등록 → 좌석 배치 준비 완료 → 관리자가 그 회차에 대기열 생성 | – |
| 2 | 12~22초 | 회원이 선착순 쿠폰 받기 | 쿠폰 발급 수가 한도에서 멈춤 |
| 3 | 22~40초 | 티켓 오픈 → 봇이 10초마다 몰려옴 → 회원이 대기열 진입, "앞에 N명"이 줄어듦 → 좌석 화면 이동 | 대기 인원은 크게 출렁이고, 입장 속도와 서버 응답 시간은 평평 |
| 4 | 40~52초 | 앞 구역부터 좌석이 채워짐 → 회원이 A구역 1열 10번 선택 → 선점 클릭 → **"방금 다른 분이 고른 좌석이에요"** → 11번 선점 성공 | 선점 수, 좌석 충돌 수, 중복 판매 0 |
| 5 | 52~62초 | 쿠폰 적용 → 결제 → 예매 확정 → 내 예매에 티켓 | 결제 확정 건수·금액 증가 |
| 6 | 62~76초 | 판매자 예상 정산 화면: 판매액·예상 정산금이 올라감 | 결제 금액 누적 |
| 7 | 76~90초 | 매진 → 판매자 화면 금액이 멈춤 | 남은 좌석 0 |

- 1·2번(판매자 공연 등록, 쿠폰)은 화면에서 직접 찍는다. 이 스크립트가 만드는 공연과 별개다(아래 6. 주의 참고). 쿠폰은 관리자 화면에서 미리 만들어 둔다. 쿠폰 봇은 없다.
- 통합시험 환경은 예매 → 결제 승인을 Kafka 메시지가 아니라 HTTP로 처리한다(메시지 승인 설정 기본값 꺼짐). 5번에서 Kafka UI를 보여 주려면 결제·예매 메시지 승인 설정을 함께 켜야 해서 이번 스크립트에는 넣지 않았다.
- 시간이 모자라면 1번을 줄인다. 3번(대기열)·4번(좌석 경합)·6~7번(정산·매진)은 남긴다.

## 2. 파일

| 파일 | 하는 일 |
|---|---|
| `prepare.py` | 시연 데이터 준비. 계정(회원·판매자·경쟁 봇·티켓팅 봇), 공연장(구역 여러 개, A가 가장 인기), 좌석, 공연, 회차, 공개, 대기열을 만들고 **봇 로그인까지 미리** 해서 `input.json`에 저장한다 |
| `ticketing.js` | k6 티켓팅 봇. 오픈 전 대기 → 오픈 후 파도별로 대기열 진입 → 입장 → 앞 구역 가중치로 좌석 선택(0.3~1.0초) → 충돌하면 다시 고름(최대 5번) → 5%는 결제 포기 → 토스 결제 → 확정 |
| `rival.py` | 경쟁 봇. 4번 장면의 충돌을 확실히 만들기 위해, 회원이 대기열을 통과하고 5초 뒤 A구역 1열 10번을 먼저 선점한다 |
| `input.json` | `prepare.py`가 만든다. 봇 토큰이 있어 Git에 올리지 않는다 |

통합시험 환경에서 함께 바꾼 것
- `compose.integration.yml`: 대기열 입장 속도·동시 입장 상한을 `DEMO_QUEUE_ADMIT_PER_SECOND`·`DEMO_QUEUE_MAX_ACTIVE`로 바꿀 수 있다. 없으면 기존 기본값(10, 1000).
- `infra/integration/init-schemas.sh`: 기본 `infra/postgres/init.sh`와 결제 스키마를 두 번 만들어 PostgreSQL 초기화가 실패하던 문제를 고쳤다.

## 3. 준비물

- Docker Desktop(Linux containers), Python 3, Node.js(프론트), Git
- 메모리 여유: 통합시험 환경 컨테이너 상한 합계가 약 16GiB다. 다른 Docker 스택은 내려 둔다.
- 아래 명령은 **cmd(명령 프롬프트)** 기준이다. PowerShell이면 `set A=B` 대신 `$env:A='B'`를 쓴다.

## 4. 처음 한 번: 통합시험 환경 띄우기

통합시험 도구는 저장소 안의 `script/integration/source-<커밋>` worktree를 소스로 빌드한다(자세한 내용은 `infra/integration/README.md`). 이 worktree는 **하나만** 있어야 한다.

```bat
cd /d <저장소 루트>
git fetch origin develop
git rev-parse --short origin/develop
:: 위에서 나온 짧은 커밋(예: ba9dc8f)으로 소스 worktree를 만든다
git worktree add --detach script/integration/source-ba9dc8f origin/develop

set DEMO_QUEUE_ADMIT_PER_SECOND=30
set DEMO_QUEUE_MAX_ACTIVE=5000
set COMPOSE_PROFILES=tools
python infra/integration/compose.py up -d --build
python infra/integration/compose.py ps
```

- 처음 빌드는 10분 이상 걸린다. `ps`에서 서비스가 모두 healthy가 될 때까지 기다린다.
- `set`은 그 cmd 창에서만 유지된다. 입장 속도를 바꾸면 같은 창에서 `compose.py up -d`를 다시 실행한다.

## 5. 시연할 때마다

### 5-1. 데이터 준비 (cmd 창 1)

```bat
cd /d <저장소 루트>
python script/demo/prepare.py --sections 3 --section-seats 500 --bots 4000 --open-in 480
```

- 봇 4,000명 가입·로그인에 5~8분 걸린다. 끝나면 **대기열 오픈 시각**, 계정, 시연 좌석이 출력된다.
- 오픈은 준비가 끝나고 `--open-in`초 뒤다. 그 안에 5-2~5-4를 띄운다.
- 봇 토큰은 1시간 유효하다. 출력된 만료 시각 전에 녹화를 끝낸다.

| 옵션 | 기본 | 뜻 |
|---|---|---|
| `--sections` | 3 | 구역 수(A가 가장 앞·인기) |
| `--section-seats` | 500 | 구역당 좌석(25의 배수) |
| `--bots` | 1000 | 봇 수. 좌석의 2~3배면 매진이 자연스럽다 |
| `--open-in` | 180 | 준비 완료 후 대기열 오픈까지 초(70 이상) |

### 5-2. 프론트 (cmd 창 2)

```bat
cd /d <저장소 루트>\frontend
npm install
set VITE_GATEWAY_URL=http://localhost:18080
set VITE_FAKE_PG_URL=http://localhost:18090
set "SEED_USER_EMAIL=demo-user@demo.local"
set "SEED_USER_PASSWORD=Demo!2026pw"
set "SEED_SELLER_EMAIL=demo-seller@demo.local"
set "SEED_SELLER_PASSWORD=Demo!2026pw"
set "MASTER_EMAIL=master@integration.local"
set "MASTER_PASSWORD=Integration!2026"
npm run dev
```

http://localhost:5173 을 연다. 로그인 화면에 회원·판매자·관리자 바로 로그인 버튼이 생긴다. **봇을 켜기 전에 회원·판매자로 로그인해 둔다.**

| 역할 | 아이디 | 비밀번호 |
|---|---|---|
| 회원 | `demo-user@demo.local` | `Demo!2026pw` |
| 판매자 | `demo-seller@demo.local` | `Demo!2026pw` |
| 관리자 | `master@integration.local` | `Integration!2026` |

### 5-3. 경쟁 봇 (cmd 창 3, 오픈 전)

```bat
cd /d <저장소 루트>
python script/demo/rival.py
```

| 옵션 | 뜻 |
|---|---|
| (없음) | 회원이 대기열을 통과하고 5초 뒤 A구역 1열 10번을 선점 |
| `--delay 8` | 좌석 화면이 늦게 열리면 기다리는 시간을 늘림 |
| `--manual` | cmd 창에서 Enter를 누르는 순간 선점(다른 사람이 눌러 줄 때) |

### 5-4. 티켓팅 봇 (cmd 창 4, 오픈 전)

```bat
cd /d <저장소 루트>
docker run --rm -i --network ticket-integration_ticket-net -v "%cd%/script/demo:/demo" grafana/k6:0.54.0 run -e INPUT=/demo/input.json -e BASE=http://caddy:80 -e PG=http://fake-pg:8090 -e WAVES=8 -e WAVE_SIZE=500 -e WAVE_GAP=10 /demo/ticketing.js
```

| 값 | 뜻 |
|---|---|
| `WAVES` × `WAVE_SIZE` | 파도 수 × 파도당 봇. `--bots` 이하여야 한다 |
| `WAVE_GAP` | 파도 간격(초). 파도 w는 **오픈 + w × 간격**에 대기열에 들어간다. k6를 언제 켜든 같다 |
| `POLL_MIN`·`POLL_MAX` | 봇 순번 조회 간격(기본 5~10초) |
| `ABANDON` | 결제 포기 비율(기본 0.05) |
| `RETRIES` | 좌석 충돌 시 다시 고르는 횟수(기본 4) |

### 5-5. 녹화 (브라우저)

1. 회원 화면: 공연 목록에서 이번에 출력된 **"시연 콘서트 <번호>"**를 연다(이전 회차와 헷갈리지 않게 번호 확인).
2. 오픈 시각이 지나고 **10초쯤 뒤** 예매에 들어간다. 봇 첫 파도 뒤에 줄을 서야 "앞에 N명"이 크게 보이고, 경쟁 봇보다 늦게 줄을 선다.
3. 좌석 화면이 열리면 바로 **A구역 1열 10번**을 고른다.
4. 경쟁 봇 창에 **"선점 완료"**가 찍히면 3초 안에 "좌석 선점 후 결제"를 누른다 → "방금 다른 분이 고른 좌석이에요".
5. **A구역 1열 11번**을 고른다 → 선점 → 결제 → 예매 확정.
6. 판매자 화면: 정산 내역 → **예상 정산** 탭. 새로고침 버튼으로 판매액이 올라가는 것을 보여 준다.
7. 지표: Grafana http://localhost:13000 (`admin` / `integration_grafana_local_only`), Kafka UI http://localhost:18989

### 5-6. 결과 확인

k6가 끝나면 요약에 다음 값이 나온다.

| 지표 | 뜻 |
|---|---|
| `demo_queue_entered` / `demo_admitted` | 대기열 진입 / 입장 봇 수 |
| `demo_hold_success` / `demo_hold_conflict` | 좌석 선점 성공 / 충돌(이미 선택된 좌석) |
| `demo_gave_up` | 매진·충돌로 포기 |
| `demo_payment_abandoned` / `demo_payment_failed` / `demo_confirmed` | 결제 포기 / 결제 실패 / 예매 확정 |
| `demo_login_failed` / `demo_queue_failed` / `demo_system_errors` | 0이어야 정상 |

다시 찍을 때는 5-1부터 다시 한다. 매번 새 공연·회차·대기열·봇이 만들어지고 기존 데이터는 지우지 않는다.

## 6. 주의

- **판매자 공연 관리 목록에 이 공연이 안 보인다.** 판매자 공연 목록 API가 없어 프론트는 그 브라우저에서 직접 등록한 공연만 기억한다. 판매자 홈의 ID 입력란에 출력된 performanceId·scheduleId를 넣으면 열린다. 예상 정산 화면에는 정상으로 나온다.
- **결제는 토스만 된다.** 프론트 결제 화면이 토스만 지원한다.
- **A구역 1열 10~12번은 봇이 고르지 않는다.** 시연용 좌석이다. 10번이 이미 팔렸으면 그 회차로는 충돌 장면을 다시 못 찍으니 5-1부터 다시 한다.
- **봇 로그인을 시연 중에 하지 않는다.** 수천 명이 동시에 로그인하면 회원 서비스 DB 커넥션 풀(통합 환경 10)이 바닥나 로그인이 실패하고 브라우저 로그인도 멈춘다. 그래서 `prepare.py`가 미리 로그인한다.

## 7. 문제가 생기면

| 증상 | 원인 | 조치 |
|---|---|---|
| `prepare.py`가 `HTTP 503` | 서비스가 아직 뜨지 않았거나 재시작 중 | `compose.py ps`로 healthy 확인. 계속 재시작하면 `compose.py logs <서비스>` |
| 서비스 로그에 `Schema-validation: missing ...` | 통합시험 DB 볼륨이 예전 스키마 | DB를 새로 만든다(아래). 이전 통합시험 DB 데이터가 지워진다 |
| PostgreSQL이 `exited (3)`, `p_payments already exists` | 이 브랜치의 `init-schemas.sh` 수정이 없는 소스 | 이 브랜치를 쓴다 |
| `bash\r: No such file` | `init-schemas.sh` 줄바꿈이 CRLF로 바뀜 | LF로 저장한다 |
| 브라우저 로그인 무한 로딩 | 봇 로그인 몰림 | 봇을 켜기 전에 로그인해 둔다 |
| 충돌 메시지가 안 뜸 | 경쟁 봇보다 먼저 선점했거나 3초가 지남 | 경쟁 봇 "선점 완료" 직후 누른다. 좌석 화면이 늦으면 `--delay`를 늘린다 |
| `dependency postgres failed to start` | 초기화 실패 뒤 볼륨이 남음 | DB를 새로 만든다(아래) |

DB 새로 만들기(통합시험 DB 데이터가 모두 지워진다)

```bat
python infra/integration/compose.py stop
docker rm -f ticket-integration-postgres-1
docker volume rm ticket-integration_pgdata
python infra/integration/compose.py up -d
```
