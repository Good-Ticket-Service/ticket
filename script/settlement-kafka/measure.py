"""#363 재사용 측정 자산. 표준 라이브러리만 사용하며 기존 DB/컨테이너를 삭제하지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
SERVICE = BACKEND / "payment-service"
ASSETS = SERVICE / "build/settlement-benchmark"
COMPOSE = ["docker", "compose", "-f", "script/settlement-kafka/compose.yml"]
FIXTURE = SERVICE / "src/test/resources/settlement-benchmark/history.sql"
SCHEMA = BACKEND / "sql/payment-service/schema.sql"
MAIN = "com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementKafkaBenchmarkMain"


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def execute(command, *, data=None, log=None, timeout=600, cwd=ROOT):
    if log:
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, input=data, text=True, encoding="utf-8", errors="replace",
                                    stdout=stream, stderr=subprocess.STDOUT, cwd=cwd, timeout=timeout)
        output = ""
    else:
        result = subprocess.run(command, input=data, text=True, encoding="utf-8", errors="replace",
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=cwd, timeout=timeout)
        output = result.stdout.strip()
    if result.returncode:
        raise RuntimeError(f"실행 실패(exit={result.returncode}). 로그: {log or output[-2000:]}")
    return output


def sql(statement, database="postgres"):
    if database != "postgres" and not re.fullmatch(r"sk363_[a-z0-9_]{1,50}", database):
        raise ValueError("전용 DB만 허용한다")
    return execute(COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-qAt",
                              "-v", "ON_ERROR_STOP=1", "-U", "sk363_admin", "-d", database], data=statement)


def fingerprint(paths):
    digest = hashlib.sha256()
    for path in sorted(path for path in paths if path.is_file()):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
    return digest.hexdigest()


def build(java):
    paths = [p for base in [SERVICE / "src", BACKEND / "common/src", BACKEND / "common-core/src"]
             if base.exists() for p in base.rglob("*") if p.is_file()]
    paths += list(BACKEND.glob("*.gradle")) + list(BACKEND.glob("*/build.gradle"))
    stamp = fingerprint(paths)
    marker = ASSETS / "build.json"
    classpath = SERVICE / "build/settlement-benchmark-classpath.txt"
    cached = json.loads(marker.read_text(encoding="utf-8")) if marker.exists() else {}
    if cached.get("sourceHash") != stamp or not classpath.exists() or not all(Path(p).exists() for p in classpath.read_text().split(os.pathsep)):
        before = time.perf_counter()
        execute([str(BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")),
                 ":payment-service:settlementBenchmarkClasspath"], cwd=BACKEND, log=ASSETS / "compile.log")
        cached = {"sourceHash": stamp, "compileSeconds": time.perf_counter() - before}
        save(marker, cached)
        print(f"컴파일 완료: {cached['compileSeconds']:.1f}초", flush=True)
    else:
        print("컴파일 재사용", flush=True)
    cached = dict(cached)
    cached["javaVersion"] = execute([java, "-version"])
    return cached


def java_run(java, command, database, output, *arguments):
    classpath = (SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
    # Windows CreateProcess 길이 제한을 피한다. 테스트용 공개 fixture 계정만 Java에 고정돼 있다.
    argfile = output.with_suffix(".args")
    argfile.parent.mkdir(parents=True, exist_ok=True)
    tokens = ["-Xms256m", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-cp", classpath,
              MAIN, command, database, str(output), *map(str, arguments)]
    argfile.write_text("\n".join('"' + token.replace("\\", "/").replace('"', '\\"') + '"' for token in tokens), encoding="utf-8")
    execute([java, "@" + str(argfile)], cwd=BACKEND, log=output.with_suffix(".log"))
    return json.loads(output.read_text(encoding="utf-8"))


def database_info(name):
    text = sql(f"SELECT json_build_object('allow',datallowconn,'marker',shobj_description(oid,'pg_database')) FROM pg_database WHERE datname='{name}';")
    return json.loads(text) if text else None


def mark(name, value):
    marker = json.dumps(value, separators=(",", ":")).replace("'", "''")
    sql(f"COMMENT ON DATABASE {name} IS '{marker}';")


def prepare(args, java):
    started = time.perf_counter()
    environment = {
        "composeVersion": execute(["docker", "compose", "version"]),
        "postgresVersion": sql("SHOW server_version;"),
        "kafkaVersion": execute(COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--version"]),
        "limits": {"postgresCpus": 2, "postgresMemory": "2g", "kafkaCpus": 2, "kafkaMemory": "1g",
                   "jvmActiveProcessorCount": 2, "jvmMaxHeap": "768m", "appPoolMax": 20},
    }
    stamp = fingerprint([SCHEMA, FIXTURE])[:16]
    base = f"sk363_base_v2_{stamp}_{args.history}_{args.ready}"
    config = {"format": 2, "seedHash": stamp, "history": args.history, "ready": args.ready}
    existing = database_info(base)
    if existing:
        marker = json.loads(existing["marker"] or "{}")
        if marker.get("config") != config:
            raise RuntimeError("동일 이름의 DB 설정이 다르다. 자동 덮어쓰기를 하지 않는다")
    else:
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='sk363_app') THEN CREATE ROLE sk363_app LOGIN PASSWORD 'sk363_local_fixture' NOSUPERUSER; END IF; END $$;")
        sql(f"CREATE DATABASE {base} OWNER sk363_app TEMPLATE template0;")
        marker = {"kind": "base", "config": config, "phase": "created"}
        mark(base, marker)
    if marker["phase"] == "created":
        # canonical DDL 자체의 BEGIN/COMMIT이 있으므로 역할/search_path는 세션 범위로 유지한다.
        sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SET ROLE sk363_app; CREATE SCHEMA payment_schema AUTHORIZATION sk363_app; SET search_path TO payment_schema,public;\n" + SCHEMA.read_text(encoding="utf-8"), base)
        marker["phase"] = "schema"
        mark(base, marker)
    if marker["phase"] == "schema":
        before = time.perf_counter()
        statement = FIXTURE.read_text(encoding="utf-8").replace("${history}", str(args.history)).replace("${ready}", str(args.ready))
        sql("BEGIN; SET LOCAL ROLE sk363_app; SET LOCAL search_path TO payment_schema,public;\n" + statement + "\nCOMMIT;", base)
        marker.update(phase="seeded", seedSeconds=time.perf_counter() - before)
        mark(base, marker)
    if marker["phase"] == "seeded":
        # 원천 bootstrap은 재진입 가능하다. 집계 소비는 금융 구간 기준선에서 제외하고 4단계에서 별도 준비한다.
        result = java_run(java, "materialize", base, ASSETS / base / "materialize.json", args.history)
        if not result["success"]:
            raise RuntimeError("원천 bootstrap 검증 실패")
        sql("ANALYZE;", base)
        marker.update(phase="verified", materializeSeconds=result["totalSeconds"], oracle=result["oracle"])
        mark(base, marker)
    if marker["phase"] == "verified":
        sql(f"ALTER DATABASE {base} ALLOW_CONNECTIONS false;")
        marker["phase"] = "sealed"
        mark(base, marker)
    if marker["phase"] != "sealed" or database_info(base)["allow"]:
        raise RuntimeError("검증·봉인되지 않은 기준 DB다")
    clones = {}
    for case in args.cases:
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,20}", case):
            raise ValueError("case 이름은 소문자·숫자·밑줄로 제한한다")
        target = f"sk363_{case}_{stamp[:8]}"
        before = time.perf_counter()
        previous = database_info(target)
        expected = {"kind": "case", "base": base, "config": config}
        if previous:
            if json.loads(previous["marker"] or "{}") != expected:
                raise RuntimeError(f"{target}: 복제본 설정 불일치")
            reused = True
        else:
            sql(f"CREATE DATABASE {target} OWNER sk363_app TEMPLATE {base};")
            mark(target, expected)
            reused = False
        clones[case] = {"database": target, "reused": reused, "seconds": time.perf_counter() - before}
    report = {"base": base, "config": config, "marker": marker, "clones": clones, "environment": environment,
              "databaseSizes": json.loads(sql("SELECT json_agg(json_build_object('name',datname,'bytes',pg_database_size(oid))) FROM pg_database WHERE datname LIKE 'sk363_%';")),
              "prepareSeconds": time.perf_counter() - started, "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    current = ASSETS / "prepared.json"
    if current.exists():
        old = json.loads(current.read_text(encoding="utf-8"))
        save(ASSETS / "prepare-history" / (hashlib.sha256(current.read_bytes()).hexdigest()[:16] + ".json"), old)
    save(ASSETS / "prepare-history" / (str(time.time_ns()) + ".json"), report)
    save(current, report)
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


def resources():
    return {service: execute(COMPOSE + ["exec", "-T", service, "sh", "-c",
                                      "cat /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/io.stat"])
            for service in ["postgres", "kafka"]}


def clone(args):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,20}", args.case):
        raise ValueError("전용 case 이름이 필요하다")
    prepared = json.loads((ASSETS / "prepared.json").read_text(encoding="utf-8"))
    base = prepared["base"] if args.pipeline == "FINANCIAL" else prepared.get("templates", {}).get(args.pipeline, {}).get("database")
    if not base:
        raise RuntimeError("template --pipeline D/B로 집계 이력을 먼저 준비한다")
    info = database_info(base)
    if not info or info["allow"] or json.loads(info["marker"])["phase"] != "sealed":
        raise RuntimeError("봉인된 기준본이 필요하다")
    migrations = [BACKEND / "sql/payment-service/migrations" / name for name in
                  ["026-settlement-posting-outbox.sql", "027-financial-consumer-recovery.sql"]] if args.posting_schema and args.pipeline == "FINANCIAL" else []
    applied = ([{"file": path.name, "hash": fingerprint([path])} for path in migrations] if args.pipeline == "FINANCIAL"
               else prepared["templates"][args.pipeline]["migrations"])
    expected = {"kind": "case", "base": base, "config": prepared["config"],
                "migrations": applied, "phase": "PREPARED"}
    if args.pipeline != "FINANCIAL":
        expected["pipeline"] = args.pipeline
    name = f"sk363_{args.case}_{prepared['config']['seedHash'][:8]}"
    existing = database_info(name)
    before = time.perf_counter()
    if existing:
        if json.loads(existing["marker"] or "{}") != expected:
            raise RuntimeError("기존 복제본 설정 불일치/미완성. 새 case 이름이 필요하다")
    else:
        sql(f"CREATE DATABASE {name} OWNER sk363_app TEMPLATE {base};")
        mark(name, dict(expected, phase="MIGRATING"))
        for path in migrations:
            sql("SET ROLE sk363_app; SET search_path TO payment_schema,public;\n" + path.read_text(encoding="utf-8"), name)
        if args.pipeline != "FINANCIAL":
            sql(f"ALTER DATABASE {name} SET timezone TO 'Asia/Seoul';")
        mark(name, expected)
    prepared["clones"][args.case] = {"database": name, "reused": bool(existing), "seconds": time.perf_counter() - before,
                                   "migrations": expected["migrations"], "base": base, "pipeline": args.pipeline}
    save(ASSETS / "clone-history" / (str(time.time_ns()) + ".json"), prepared["clones"][args.case])
    save(ASSETS / "prepared.json", prepared)
    print(json.dumps(prepared["clones"][args.case], ensure_ascii=False, indent=2), flush=True)


def template(args, java):
    if args.pipeline not in ["D", "B"]:
        raise ValueError("template은 --pipeline D 또는 B가 필요하다")
    prepared = json.loads((ASSETS / "prepared.json").read_text(encoding="utf-8"))
    paths = [BACKEND / "sql/payment-service/migrations" / name for name in
             ["026-settlement-posting-outbox.sql", "027-financial-consumer-recovery.sql"]]
    helper = SERVICE / "src/test/java/com/sparta/ticket/payment/infrastructure/persistence/settlement/SettlementKafkaProjectionFixture.java"
    signature = fingerprint(paths + [helper])[:12]
    name = f"sk363_full_{args.pipeline.lower()}_{signature}"
    base = prepared["base"] if args.pipeline == "D" else prepared.get("templates", {}).get("D", {}).get("database")
    if not base:
        raise RuntimeError("D template을 먼저 준비한다")
    info = database_info(base)
    if not info or info["allow"] or json.loads(info["marker"] or "{}").get("phase") != "sealed":
        raise RuntimeError("부모 template 봉인 확인 실패")
    expected = {"kind": "projection-template", "base": base, "mode": args.pipeline, "signature": signature,
                "migrations": [{"file": path.name, "hash": fingerprint([path])} for path in paths], "phase": "sealed"}
    before = time.perf_counter()
    existing = database_info(name)
    if existing:
        if existing["allow"] or json.loads(existing["marker"] or "{}") != expected:
            raise RuntimeError("기존 template이 미완성이거나 설정이 다르다. 원본을 보존하고 확인한다")
    else:
        sql(f"CREATE DATABASE {name} OWNER sk363_app TEMPLATE {base};")
        mark(name, dict(expected, phase="initializing"))
        sql(f"ALTER DATABASE {name} SET timezone TO 'Asia/Seoul';")
        if args.pipeline == "D":
            for path in paths:
                sql("SET ROLE sk363_app; SET search_path TO payment_schema,public;\n" + path.read_text(encoding="utf-8"), name)
        java_run(java, "projection-base", name, ASSETS / name / "initialize.json", prepared["config"]["history"], args.pipeline)
        sql("VACUUM (ANALYZE);", name)
        mark(name, expected)
        sql(f"ALTER DATABASE {name} ALLOW_CONNECTIONS false;")
    value = {"database": name, "base": base, "mode": args.pipeline, "reused": bool(existing),
             "seconds": time.perf_counter() - before, "signature": signature, "migrations": expected["migrations"]}
    prepared.setdefault("templates", {})[args.pipeline] = value
    save(ASSETS / "prepared.json", prepared)
    save(ASSETS / "template-history" / (str(time.time_ns()) + ".json"), value)
    print(json.dumps(value, ensure_ascii=False, indent=2), flush=True)


def inspect(case):
    prepared = json.loads((ASSETS / "prepared.json").read_text(encoding="utf-8"))
    database = prepared["clones"][case]["database"]
    statement = """
SELECT coalesce(json_agg(s),'[]'::json) FROM (
  SELECT queryid,query,calls,total_exec_time,mean_exec_time,rows,shared_blks_hit,shared_blks_read,
    shared_blks_dirtied,shared_blks_written,temp_blks_read,temp_blks_written,wal_records,wal_bytes
  FROM public.pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
  ORDER BY total_exec_time DESC LIMIT 30
) s;
"""
    output = ASSETS / "runs" / case / "sql-after.json"
    if output.exists():
        print(f"SQL 집계 재사용: {output}", flush=True)
        return
    save(output, {"scope": "복제 DB 생성 이후 기동·예열·측정·oracle 전체. 측정 60초만의 SQL delta가 아니다",
                  "database": database, "queries": json.loads(sql(statement, database))})


def run(args, java, artifact):
    prepared = json.loads((ASSETS / "prepared.json").read_text(encoding="utf-8"))
    case = prepared["clones"][args.case]
    directory = ASSETS / "runs" / args.case
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / "result.json"
    expected = {"artifact": artifact, "base": case.get("base", prepared["base"]), "database": case["database"],
                "workers": args.workers, "warm": args.warm, "count": args.count, "rate": args.rate, "mode": args.mode,
                "pipeline": args.pipeline, "observer": "moving-low-water-v2"}
    expected["harnessHash"] = fingerprint([Path(__file__)])
    if case.get("pipeline", "FINANCIAL") != args.pipeline:
        raise RuntimeError("복제본의 집계 상태와 요청한 pipeline이 다르다")
    if args.mode.startswith("kafka") and not case.get("migrations"):
        raise RuntimeError("Kafka 시험에는 clone --posting-schema로 준비한 복제본이 필요하다")
    manifest = directory / "manifest.json"
    if manifest.exists():
        previous = json.loads(manifest.read_text(encoding="utf-8"))
        if previous != expected:
            raise RuntimeError("이미 사용한 case의 실행 조건이 다르다. prepare --cases 새이름으로 복제본을 추가한다")
        if output.exists() and json.loads(output.read_text(encoding="utf-8")).get("success"):
            print(f"성공한 결과 재사용: {output}", flush=True)
            return
        raise RuntimeError("실패/중단한 case는 보존한다. 새 case 이름으로 다시 실행한다")
    if args.warm + args.count + (1 if args.mode == "kafka-recovery" else 0) > prepared["config"]["ready"]:
        raise ValueError("미리 준비한 READY 수를 초과한다")
    save(manifest, expected)
    resource = {"before": resources()}
    try:
        java_run(java, args.mode, case["database"], output, prepared["config"]["history"],
                 args.workers, args.warm, args.count, args.rate, args.pipeline)
    finally:
        resource["after"] = resources()
        save(directory / "resources.json", resource)
    inspect(args.case)
    print(f"측정 완료: {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "template", "clone", "check", "run", "suite", "inspect", "report", "build"])
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--history", type=int, default=100000)
    parser.add_argument("--ready", type=int, default=7000)
    parser.add_argument("--cases", nargs="+", default=["rehearsal", "db1", "db2", "db4"])
    parser.add_argument("--case", default="rehearsal")
    parser.add_argument("--workers", type=int, choices=[1, 2, 4], default=1)
    parser.add_argument("--warm", type=int, default=0)
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--rate", type=int, default=50)
    parser.add_argument("--mode", choices=["db", "kafka", "kafka-recovery"], default="db")
    parser.add_argument("--posting-schema", action="store_true")
    parser.add_argument("--pipeline", choices=["FINANCIAL", "D", "B"], default="FINANCIAL")
    parser.add_argument("--suite-prefix", default="final")
    args = parser.parse_args()
    if not args.java or not 10 <= args.history <= 1000000 or args.history % 10 or not 100 <= args.ready <= 30000:
        parser.error("Java 실행 경로와 history(10의 배수,10~100만),ready(100~3만)를 확인한다")
    if args.command == "report":
        for path in sorted((ASSETS / "runs").glob("*/result.json")):
            result = json.loads(path.read_text(encoding="utf-8"))
            print(json.dumps({"case": path.parent.name, "success": result.get("success"),
                              "bootSeconds": result.get("bootSeconds"), "measured": result.get("measured"),
                              "postDispatchToAllDoneSeconds": result.get("measured", {}).get("elapsedSeconds", 0)
                              - result.get("measured", {}).get("dispatchSeconds", 0),
                              "oracleAfter": result.get("oracleAfter")}, ensure_ascii=False, indent=2))
        return
    if args.command == "clone":
        clone(args)
        return
    if args.command == "inspect":
        inspect(args.case)
        return
    if args.command == "check":
        prepared = json.loads((ASSETS / "prepared.json").read_text(encoding="utf-8"))
        info = database_info(prepared["base"])
        if not info or info["allow"] or json.loads(info["marker"])["phase"] != "sealed":
            raise RuntimeError("기준 DB 봉인 확인 실패")
        for case, config in prepared["clones"].items():
            value = sql("SET search_path TO payment_schema,public; SELECT json_build_object('case','" + case
                        + "','unfinished',(SELECT count(*) FROM p_settlement_jobs WHERE status<>'DONE'),"
                        "'newNonReady',(SELECT count(*) FROM p_payments WHERE id>"
                        + str(prepared["config"]["history"]) + " AND status<>'READY'));", config["database"])
            print(value, flush=True)
        return
    artifact = build(args.java)
    if args.command == "prepare":
        prepare(args, args.java)
    elif args.command == "template":
        template(args, args.java)
    elif args.command == "run":
        run(args, args.java, artifact)
    elif args.command == "suite":
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,10}", args.suite_prefix):
            raise ValueError("짧은 전용 suite prefix가 필요하다")
        matrix = [("db1", "db", 1, "FINANCIAL"), ("k1", "kafka", 1, "FINANCIAL"),
                  ("db2", "db", 2, "FINANCIAL"), ("k2", "kafka", 2, "FINANCIAL"),
                  ("db4", "db", 4, "FINANCIAL"), ("k4", "kafka", 4, "FINANCIAL"),
                  ("d", "db", 4, "D"), ("b", "kafka", 4, "B")]
        for suffix, mode, workers, pipeline in matrix:
            values = argparse.Namespace(**vars(args))
            values.case, values.mode, values.workers, values.pipeline = args.suite_prefix + suffix, mode, workers, pipeline
            values.posting_schema = True
            print(f"SUITE {values.case}: mode={mode}, workers={workers}, pipeline={pipeline}", flush=True)
            clone(values)
            run(values, args.java, artifact)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        print(f"중단: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
