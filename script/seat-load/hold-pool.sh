#!/usr/bin/env bash
# #434 좌석 선점 처리량을 예매 서비스 기본 연결 풀(booking-main) 크기별로 잰다. 예매 서비스만 떼어 잰다(Gateway·공연 서비스 없음).
#
# 통합시험(#390)과 CPU 조건을 맞추려고 부품마다 코어를 고정한다.
#   예매 서비스 JVM 2코어(SERVER_CPUS), DB 2코어(PG_CPUS), Kafka·Redis(SIDE_CPUS), 부하 생성기(LOAD_CPUS)
# 판매 검증은 5ms 뒤 허용하는 가짜다(HoldBenchServerMain). 부하는 #390 대조군과 같은 모양이다(HoldBenchLoadMain).
# 풀 크기마다 빈 스키마에서 예매 서비스를 새로 띄우고, 예열 회차(0) 1번 뒤 회차마다 새 좌석으로 REPS번 잰다.
#
# 실행(저장소 루트): POOLS="10 15 20" REPS=3 script/seat-load/hold-pool.sh
# 결과: script/seat-load/out/hold-pool-<시각>/pool-<N>-rep-<K>.json, summary.csv, server-pool-<N>.log
# 같은 PC에서 다른 Docker 측정·Gradle 작업을 함께 돌리지 않는다(코어 경합).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BACKEND="$ROOT/backend"
POOLS="${POOLS:-10 15 20}"
REPS="${REPS:-3}"
SERVER_CPUS="${SERVER_CPUS:-2-3}"
PG_CPUS="${PG_CPUS:-4-5}"
LOAD_CPUS="${LOAD_CPUS:-6-9}"
SIDE_CPUS="${SIDE_CPUS:-10-11}"
PG_PORT=15499
REDIS_PORT=16399
KAFKA_PORT=19099
SERVER_PORT=18083
# 짧은 확인 실행: LOAD_JAVA_OPTS="-Dbench.warmup=5 -Dbench.measure=10" POOLS=10 REPS=1
LOAD_JAVA_OPTS="${LOAD_JAVA_OPTS:-}"
# 진단 전용 DB 설정(예: PG_EXTRA_ARGS="-c synchronous_commit=off"). 측정 결과에는 쓰지 않는다
PG_EXTRA_ARGS="${PG_EXTRA_ARGS:-}"
OUT="$ROOT/script/seat-load/out/hold-pool-$(date +%Y%m%d-%H%M%S)"
PG=hb-pg
SERVER_PID=

cleanup() {
    # 멈춤 진단(#434): autovacuum·checkpoint·잠금 대기 로그를 컨테이너를 지우기 전에 남긴다
    docker logs "$PG" >"$OUT/postgres.log" 2>&1 || true
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true; fi
    docker rm -f hb-pg hb-redis hb-kafka >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$OUT"
echo "[bench] 결과 폴더 $OUT"

"$BACKEND/gradlew" -p "$BACKEND" -q :reservation-service:holdBenchClasspath
CP="$(cat "$BACKEND/reservation-service/build/hold-bench-classpath.txt")"

docker rm -f hb-pg hb-redis hb-kafka >/dev/null 2>&1 || true
docker run -d --name "$PG" --cpuset-cpus "$PG_CPUS" -m 2g -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=ticket \
    -p "$PG_PORT:5432" postgres:16 -c shared_buffers=512MB \
    -c log_autovacuum_min_duration=0 -c log_checkpoints=on -c log_lock_waits=on -c deadlock_timeout=200ms \
    -c log_line_prefix='%m [%p] %u ' $PG_EXTRA_ARGS >/dev/null
docker run -d --name hb-redis --cpuset-cpus "$SIDE_CPUS" -p "$REDIS_PORT:6379" redis:7.4 >/dev/null
docker run -d --name hb-kafka --cpuset-cpus "$SIDE_CPUS" -p "$KAFKA_PORT:$KAFKA_PORT" \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS="PLAINTEXT://:$KAFKA_PORT,CONTROLLER://:9093" \
    -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://localhost:$KAFKA_PORT" \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    apache/kafka:4.3.1 >/dev/null

# postgres 이미지는 초기화용 임시 서버를 띄웠다가 내리고 다시 뜬다. 준비 완료 로그가 두 번 나와야 ticket DB가 있다
for _ in $(seq 90); do
    [[ "$(docker logs "$PG" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ]] && break
    sleep 1
done
# 운영과 같은 서비스 계정(reservation_app)과 스키마. 앱은 config-repo의 계정·비밀번호 기본값으로 접속한다
docker exec "$PG" psql -U postgres -d ticket -qc \
    "CREATE ROLE reservation_app LOGIN PASSWORD 'reservation_local_pw'; ALTER ROLE reservation_app SET search_path = reservation_schema;"

for pool in $POOLS; do
    docker exec "$PG" psql -U postgres -d ticket -qc \
        "DROP SCHEMA IF EXISTS reservation_schema CASCADE; CREATE SCHEMA reservation_schema AUTHORIZATION reservation_app;"
    echo "[bench] 풀 $pool: 예매 서비스 기동 $(date +%H:%M:%S)"
    DB_HOST=localhost DB_PORT="$PG_PORT" REDIS_HOST=localhost REDIS_PORT="$REDIS_PORT" \
        KAFKA_BOOTSTRAP_SERVERS="localhost:$KAFKA_PORT" SERVER_PORT="$SERVER_PORT" \
        SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE="$pool" \
        SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true \
        taskset -c "$SERVER_CPUS" java -Xms256m -Xmx1g -XX:ActiveProcessorCount=2 -cp "$CP" \
        com.sparta.ticket.bench.hold.HoldBenchServerMain "$BACKEND" >"$OUT/server-pool-$pool.log" 2>&1 &
    SERVER_PID=$!
    up=
    for _ in $(seq 120); do
        if curl -sf "http://localhost:$SERVER_PORT/actuator/health" >/dev/null; then up=1; break; fi
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 1
    done
    if [[ -z "$up" ]]; then echo "[bench] 기동 실패. $OUT/server-pool-$pool.log 확인" >&2; exit 1; fi
    # 회차 0은 예열이다. 새로 띄운 JVM은 회차 안의 예열 20초로 부족해 첫 회차 서버 CPU가 포화됐다(11/13 탐색 측정). 요약에서 뺀다
    for rep in $(seq 0 "$REPS"); do
        echo "[bench] 풀 $pool 회차 $rep 시작 $(date +%H:%M:%S)"
        taskset -c "$LOAD_CPUS" java -Xmx1g $LOAD_JAVA_OPTS -cp "$CP" com.sparta.ticket.bench.hold.HoldBenchLoadMain \
            "http://localhost:$SERVER_PORT" "jdbc:postgresql://localhost:$PG_PORT/ticket?currentSchema=reservation_schema" \
            reservation_app reservation_local_pw "$OUT/pool-$pool-rep-$rep.json" "$pool" "$rep" "$SERVER_PID" "$PG"
        # 회차 뒤 테이블 크기와 마지막 autovacuum·autoanalyze 시각(멈춤이 쌓인 데이터와 관계있는지 본다)
        docker exec "$PG" psql -U postgres -d ticket -XAc \
            "SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze, autovacuum_count, autoanalyze_count
             FROM pg_stat_user_tables WHERE schemaname = 'reservation_schema' AND n_live_tup > 0 ORDER BY relname" \
            >"$OUT/pg-tables-pool-$pool-rep-$rep.txt" 2>&1 || true
        sleep 10
    done
    kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true; SERVER_PID=
done

python3 - "$OUT" <<'PY'
import csv, json, pathlib, sys
out = pathlib.Path(sys.argv[1])
rows = []
for f in sorted(out.glob('pool-*-rep-*.json'), key=lambda p: [int(x) for x in p.stem.split('-')[1::2]]):
    d = json.loads(f.read_text())
    if d['rep'] == 0: continue
    b, u, s = d['base'], d['burst'], d['server']
    g = lambda m, k: (m or {}).get(k)
    rows.append({'pool': d['pool'], 'rep': d['rep'], 'finished': d['finished'],
                 'base_p50_ms': g(b['hold_ms'], 'p50'), 'base_p99_ms': g(b['hold_ms'], 'p99'), 'base_max_ms': g(b['hold_ms'], 'max'),
                 'base_attempts': b['attempts'], 'base_late_ratio': b['late_ratio'], 'base_outcomes': json.dumps(b['outcomes']),
                 'burst_p99_ms': g(u['hold_ms'], 'p99'), 'burst_late_ratio': u['late_ratio'], 'burst_outcomes': json.dumps(u['outcomes']),
                 'release_p99_ms': g(b['release_ms'], 'p99'),
                 'hikari_max': g(s['hikari_max'], 'max'), 'hikari_pending_max': g(s['hikari_pending'], 'max'),
                 'hikari_acquire_max_ms': g(s['hikari_acquire_max_ms'], 'max'), 'tomcat_busy_max': g(s['tomcat_busy'], 'max'),
                 'server_cpu_avg': g(s['server_cpu_200ms'], 'avg'), 'server_cpu_p95': g(s['server_cpu_200ms'], 'p95'),
                 'server_cpu_saturated': g(s['server_cpu_200ms'], 'saturated_ratio'),
                 'load_cpu_avg': g(s.get('load_cpu_200ms'), 'avg'), 'load_cpu_p95': g(s.get('load_cpu_200ms'), 'p95'),
                 'pg_cpu_cores': s['pg_cpu_cores'], 'pg_connections_max': g(s['pg_connections'], 'max'),
                 'gc_pause_ratio': s['gc_pause_ratio']})
if rows:
    with (out/'summary.csv').open('w', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    print('[bench] summary', out/'summary.csv')
PY
