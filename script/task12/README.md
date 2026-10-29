# Task 12 결제·환불 장애 주입 정합성 시험 (#141)

실제 컨테이너·HTTP·프로세스 종료·fake-pg 장애에서 결제·환불이 돈이 틀리지 않는 상태로 수렴하는지 합격/불합격으로 판정한다.
성능 수치는 #120에서 잰다.

## 구성

| 파일 | 역할 |
|---|---|
| `compose.yml` | 단독 시험 환경(프로젝트 `ticket-t12`). postgres·config-server·fake-pg·payment. `--profile multi`로 payment 2대, `--profile obs`로 Prometheus·Alertmanager·alert-mock |
| `init-payment-schema.sh` | 빈 DB에 `backend/sql/payment-service/schema.sql` 적용 |
| `verify.sql` | payment DB와 fake-pg 거래를 건별 대조. 위반 한 줄씩 출력 |
| `t12.py` | HTTP·Docker·DB 공통 기능 |
| `scenarios.py` | 시나리오 정의 |
| `run.py` | 실행기 |
| `run_d.py` | 혼합 부하 D2(대조군) → D1(장애 혼합) 순서 실행. k6 실행·장애 설정·결과 보존 |
| `k6/payment-flow.js` | 준비→사용자 결제→승인→(일부) 환불 부하. 혼합 부하(D1·D2)와 #120에서 같이 쓴다 |

사용자 개발 스택(`docker-compose.yml`)과 프로젝트 이름·네트워크·볼륨·포트가 겹치지 않는다.

payment는 `task12` 프로필로 뜬다. 기본 `local` 프로필은 `ddl-auto: create`라 재시작할 때마다 테이블을 지우므로 재시작 시나리오가 의미 없어진다.

## 실행

```bash
python script/task12/run.py up                          # 빌드 후 기동(healthy까지 대기)
python script/task12/run.py list                        # 시나리오 목록(동시/단독 표시)
python script/task12/run.py run A1 B1                   # 시나리오 실행
python script/task12/run.py run --group A --stop-on-fail  # 그룹 실행, 불합격이 나오면 다음 시나리오부터 멈춤
python script/task12/run.py status                      # 가장 최근 실행의 진행 로그
python script/task12/run.py verify [TOSS|KAKAO]         # 현재 DB 대조만
python script/task12/run.py down                        # 내리기(DB 볼륨 유지). down --volumes 는 DB까지 삭제
```

TOSS와 KAKAO 시나리오는 동시에 돈다. fake-pg 장애가 PG·동작 단위로 걸리고, 초기화·완료 대기·대조를 PG별로 하므로 서로 섞이지 않는다. 컨테이너 중단·재기동·전체 부하처럼 두 PG에 함께 영향을 주는 시나리오는 단독(exclusive)으로 표시해 앞의 시나리오가 끝난 뒤 혼자 돌린다. 장애 적용 횟수는 전체를 비우지 않고 시나리오 전후 차이로 센다.

그룹 하나가 수십 분 걸릴 수 있으므로 백그라운드로 돌리고 `status`로 확인한다. 진행은 `results/<실행 ID>/progress.log`에 한 줄씩 남는다.

시나리오마다 다음 순서로 진행한다.

1. 그 PG의 결제·PG 기록과 장애 설정을 비운다(단독 시나리오는 전체).
2. 시나리오를 실행한다.
3. 장애를 풀고 처리 중인 결제·환불이 없어질 때까지 기다린다.
4. `verify.sql`로 대조한다.

결과는 `script/task12/results/<실행 ID>/`에 JSON으로 남는다(Git 비추적). 담기는 내용은 커밋, 장애 설정, 장애 적용 횟수, seed, 대기 시간, 위반, 시나리오 확인이다. 요약은 #141과 PR에 옮긴다.

## 시간 단축 시험

대사 체크포인트와 환불 SLOW 전환은 환경 변수로만 줄인다. 결과에 시간 단축 시험임을 적는다.

```bash
T12_RECONCILE_CHECKPOINTS=1,2,3 T12_REFUND_SLOW_AFTER_MINUTES=3 T12_REFUND_SLOW_INITIAL_SECONDS=30 \
  docker compose -p ticket-t12 -f script/task12/compose.yml up -d --wait payment-service
```

## 혼합 부하 직접 실행

`python script/task12/run_d.py`로 D2 → D1을 순서대로 돌린다(백그라운드 권장). 아래는 k6만 따로 돌릴 때다.

```bash
docker run --rm --network ticket-t12_t12-net -v "$PWD/script/task12/k6:/scripts" \
  -e ITERATIONS=500 -e VUS=20 -e RUN_ID=d1 grafana/k6:0.54.0 run --summary-export=/scripts/out/d1.json /scripts/payment-flow.js
```

## 판정 기준(verify.sql)

| ID | 위반 |
|---|---|
| V1 | 같은 orderId의 PG 거래가 둘 이상 |
| V2 | 결제당 환불 행이 둘 이상 |
| V3 | payment 상태와 PG 상태 불일치, PG에만 있는 과금 |
| V4 | FAILED인데 PG에 과금이 남음 |
| V5 | 관찰 기간 뒤 처리 중(IN_PROGRESS, 진행 중 환불) |
| V6 | 승인액·환불액이 결제 금액과 다름 |
| V7 | PG 순수취액 ≠ payment DONE 금액 합 |
| V10 | 저장한 PG 거래 키와 실제 거래 키가 다름 |

V8(다른 시도의 일시적 복수 과금)과 V9(비밀값 노출)는 해당 시나리오가 따로 기록한다.

## 주의

- 관리자 API 호출은 Gateway 없이 `X-User-*` 헤더를 직접 붙인다. 단독 환경에서만 가능한 방식이다. 서비스 포트를 외부에 열지 않아야 한다는 배포 조건(`docs/infra.md`)은 그대로다.
- fake-pg 응답 유실은 TCP 절단이 아니라 처리 후 응답을 붙잡았다가 500을 보내는 모사다.
- 같은 seed라도 동시 요청은 도착 순서에 따라 거래별 장애가 달라질 수 있다.
