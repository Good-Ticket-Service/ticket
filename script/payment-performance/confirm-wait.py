"""#377 승인 대기 비교. 봉인한 이력과 전용 FakePG를 재사용하고 원본은 삭제하지 않는다."""
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("settlement_measure", ROOT / "script/settlement-kafka/measure.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
ASSETS = ROOT / "backend/payment-service/build/confirm-wait"
MAIN = "com.sparta.ticket.payment.application.service.payment.ApprovalWaitBenchmarkMain"


def java_args(path, tokens):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join('"' + str(token).replace("\\", "/").replace('"', '\\"') + '"'
                               for token in tokens), encoding="utf-8")


def fake_pg(java):
    try:
        with urllib.request.urlopen("http://127.0.0.1:18077/actuator/health", timeout=2) as response:
            if json.load(response).get("status") == "UP" and (ASSETS / "fake-pg.json").exists():
                marker = json.loads((ASSETS / "fake-pg.json").read_text(encoding="utf-8"))
                if os.name != "nt":
                    raise RuntimeError("기존 FakePG 소유권 확인은 현재 Windows 측정 환경에서만 지원한다")
                listeners = bench.execute(["netstat", "-ano", "-p", "tcp"])
                owners = {int(fields[-1]) for line in listeners.splitlines()
                          if len(fields := line.split()) == 5 and fields[1] == "127.0.0.1:18077"
                          and fields[3] == "LISTENING"}
                if owners != {marker["pid"]}:
                    raise RuntimeError("FakePG 포트의 프로세스가 측정 자산 표식과 다르다")
                return
        raise RuntimeError("다른 프로세스가 전용 PG 포트를 사용한다")
    except urllib.error.URLError:
        pass
    database = "sk363_cw_fakepg"
    if not bench.database_info(database):
        bench.sql(f"CREATE DATABASE {database} OWNER sk363_admin;")
        bench.mark(database, {"purpose": "issue377-fakepg", "fixtureOnly": True})
    elif json.loads(bench.database_info(database)["marker"] or "{}").get("purpose") != "issue377-fakepg":
        raise RuntimeError("전용 FakePG DB 표식이 다르다")
    bench.sql("CREATE SCHEMA IF NOT EXISTS fake_pg_schema", database)
    jar = ROOT / "backend/fake-pg/build/libs/app.jar"
    if not jar.exists():
        bench.execute([str(ROOT / "backend/gradlew.bat"), ":fake-pg:bootJar"], cwd=ROOT / "backend",
                      log=ASSETS / "fake-pg-compile.log")
    argfile = ASSETS / "fake-pg.args"
    java_args(argfile, ["-Xms128m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                        "-jar", jar, "--server.address=127.0.0.1", "--server.port=18077",
                        f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:15463/{database}?currentSchema=fake_pg_schema",
                        "--spring.datasource.username=sk363_admin", "--spring.datasource.password=sk363_local_fixture"])
    with (ASSETS / "fake-pg.log").open("w", encoding="utf-8") as stream:
        process = subprocess.Popen([java, "@" + str(argfile)], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    bench.save(ASSETS / "fake-pg.json", {"pid": process.pid, "port": 18077, "database": database})
    until = time.monotonic() + 45
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError("전용 FakePG 시작 실패. fake-pg.log 확인")
        try:
            with urllib.request.urlopen("http://127.0.0.1:18077/actuator/health", timeout=2) as response:
                if json.load(response).get("status") == "UP":
                    return
        except urllib.error.URLError:
            time.sleep(.3)
    raise RuntimeError("전용 FakePG 시작 시간 초과")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["baseline", "improved", "compare"])
    parser.add_argument("--case", default=time.strftime("%m%d%H%M%S"))
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    if args.phase == "compare":
        if not args.before or not args.after:
            parser.error("compare에는 --before와 --after가 필요하다")
        compare(args.before, args.after)
        return
    if not re.fullmatch(r"[a-z0-9_]{1,10}", args.case):
        parser.error("case는 소문자·숫자·밑줄 1~10자여야 한다")
    result_dir = ASSETS / (args.phase + "-" + args.case)
    if result_dir.exists():
        parser.error("같은 case의 결과가 이미 있다. 원본을 보존하고 새 case를 사용한다")
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 17이 필요하다")
    bench.build(java)
    fake_pg(java)
    manifest = {"phase": args.phase, "sourceHead": bench.execute(["git", "rev-parse", "HEAD"]),
                "sourceHash": json.loads((bench.ASSETS / "build.json").read_text(encoding="utf-8"))["sourceHash"],
                "sourceDiff": bench.execute(["git", "diff", "--stat"]),
                "java": bench.execute([java, "-version"]), "runs": [],
                "jdbcProbe": "actual JDBC execute and commit, scoped to MessageApprovalTransactions",
                "limits": {"workers": 16, "perProvider": 8, "consumers": 3, "maxPollRecords": 16,
                           "maxInFlight": 48, "retryMillis": 200, "pgLatencyMillis": 300},
                "history": 100000, "refundHistory": 10000}
    bench.save(result_dir / "manifest.json", manifest)
    for rate in [10, 40]:
        case = f"cw_{args.phase[:1]}_{args.case}_{rate}"
        bench.clone(SimpleNamespace(case=case, pipeline="FINANCIAL", posting_schema=True))
        prepared = json.loads((bench.ASSETS / "prepared.json").read_text(encoding="utf-8"))
        database = prepared["clones"][case]["database"]
        output = result_dir / f"rate-{rate}.json"
        classpath = (bench.SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
        argfile = output.with_suffix(".args")
        java_args(argfile, ["-Xms256m", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                            "-cp", classpath, MAIN, database, output, rate, rate * 30])
        print(f"{args.phase}: {rate}건/초, {rate * 30}건 측정 시작", flush=True)
        bench.execute([java, "@" + str(argfile)], cwd=ROOT / "backend", log=output.with_suffix(".log"), timeout=200)
        report = json.loads(output.read_text(encoding="utf-8"))
        manifest["runs"].append({"rate": rate, "database": database, "output": str(output), "success": report["success"]})
        bench.save(result_dir / "manifest.json", manifest)
        print(json.dumps(report, ensure_ascii=False), flush=True)
    print(f"결과: {result_dir}", flush=True)


def compare(before, after):
    comparison = {"before": str(before), "after": str(after), "runs": []}
    for rate in [10, 40]:
        pair = [json.loads((directory / f"rate-{rate}.json").read_text(encoding="utf-8"))
                for directory in [before, after]]
        for report in pair:
            count = report["count"]
            if not (report["success"] and count == rate * 30 and report["pgLatencyMillis"] == 300
                    and report["measured"]["states"] == {"DONE": count}
                    and report["measured"]["completed"] == count and report["measured"]["dbRetry"] == 0
                    and report["jobs"] == count and report["outbox"] == count):
                raise RuntimeError("입력·완료·영속 인계 기준을 만족하지 못한다")
            for stage in ["claim", "maySend", "complete", "confirmRecovery"]:
                if report["measured"]["jdbc"][stage]["calls"] != count:
                    raise RuntimeError("필수 금융 단계 호출 수가 다르다")
        run = {"rate": rate, "count": rate * 30, "metrics": {}, "financialChecks": []}
        for label, getter in {
            "register": lambda value: value["measured"]["jdbc"]["register"]["calls"],
            "repeatRegister": lambda value: value["measured"]["jdbc"]["register"]["calls"] - value["count"],
            "sql": lambda value: sum(stage["sqlExecutions"] for stage in value["measured"]["jdbc"].values()),
            "commits": lambda value: sum(stage["commits"] for stage in value["measured"]["jdbc"].values()),
        }.items():
            first, second = map(getter, pair)
            run["metrics"][label] = {"before": first, "after": second,
                                       "reductionPercent": (1 - second / first) * 100 if first else None}
        for value in pair:
            database = value["database"]
            if not re.fullmatch(r"sk363_cw_[a-z0-9_]{1,45}", database):
                raise RuntimeError("전용 측정 DB만 검증한다")
            cohort = "SELECT * FROM payment_schema.p_payments WHERE order_id LIKE 'cw377-%'"
            checks = json.loads(bench.sql(f"""
                WITH p AS ({cohort})
                SELECT json_build_object('count',count(*),
                    'badPayment',count(*) FILTER(WHERE status<>'DONE' OR total_price<>10000 OR discount_amount<>1000 OR amount<>9000),
                    'sumAmount',sum(amount),
                    'badResult',(SELECT count(*) FROM p LEFT JOIN payment_schema.p_payment_result_outbox o ON o.payment_id=p.id
                        WHERE o.payment_id IS NULL OR o.result_status<>'DONE' OR (o.payload::jsonb->>'amount')::int<>9000),
                    'badJob',(SELECT count(*) FROM p WHERE (SELECT count(*) FROM payment_schema.p_settlement_jobs j
                        WHERE j.payment_id=p.id AND j.reason='PAYMENT_APPROVED')<>1),
                    'prefix',min(regexp_replace(order_id,'-[wm]-[0-9]+$',''))) FROM p;
                """, database))
            prefix = checks.pop("prefix")
            if not re.fullmatch(r"cw377-[a-f0-9]{8}", prefix):
                raise RuntimeError("측정 주문 경계가 다르다")
            pg = json.loads(bench.sql(f"""SELECT json_build_object('count',count(*),'sumAmount',sum(amount),
                    'bad',count(*) FILTER(WHERE status<>'DONE' OR amount<>9000))
                    FROM fake_pg_schema.pg_transaction WHERE order_id LIKE '{prefix}-%';""", "sk363_cw_fakepg"))
            expected = value["count"] + value["warmCount"]
            if (checks["count"] != expected or checks["sumAmount"] != expected * 9000
                    or checks["badPayment"] or checks["badResult"] or checks["badJob"]
                    or pg["count"] != expected or pg["sumAmount"] != expected * 9000 or pg["bad"]):
                raise RuntimeError("실제 PG 거래와 결제/결과/정산 의도가 맞지 않는다")
            run["financialChecks"].append({"database": database, "payment": checks, "pg": pg})
        comparison["runs"].append(run)
    bench.save(after / "comparison.json", comparison)
    print(json.dumps(comparison, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    main()
