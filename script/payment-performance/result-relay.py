"""#384 결제 결과 발행기 측정. 봉인한 이력 DB를 회차마다 복제하고 원본·과거 결과는 삭제하지 않는다."""
import argparse
import importlib.util
import json
import re
from pathlib import Path
import shutil
import sys
import time
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("settlement_measure", ROOT / "script/settlement-kafka/measure.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
ASSETS = ROOT / "backend/payment-service/build/result-relay"
MAIN = "com.sparta.ticket.payment.infrastructure.messaging.ResultRelayBenchmarkMain"
# 이름: (시나리오, 첫 값, 둘째 값, 유입 종료 뒤 관측 상한 초)
# drain: 사전 적재 건수, 측정 창(0=모두 비울 때까지). rate: 초당 유입, 유입 시간.
SUITE = [
    ("drain10k", "drain", 10000, 0, 400),
    ("drain50k", "drain", 50000, 120, 0),
    ("rate50", "rate", 50, 180, 60),
    ("rate100", "rate", 100, 180, 60),
    ("rate200", "rate", 200, 180, 60),
    ("rate500", "rate", 500, 180, 60),
]
# 동작 확인용 짧은 예행. 결과 수치로 쓰지 않는다.
SMOKE = [("smokerate", "rate", 50, 10, 30), ("smokedrain", "drain", 500, 0, 60)]
# 브로커 중단 시험. 유입 200건/s × 120초, 측정 시작 30초 뒤 전용 Kafka를 30초 멈춘다. 유입 종료 뒤 최대 120초 관측.
FAULT = [("broker", "rate", 200, 120, 120)]


def broker_outage(database, stop_after, outage):
    """측정 행이 처음 보이면 stop_after초 뒤 전용 Kafka 컨테이너만 멈추고 outage초 뒤 다시 시작한다."""
    import threading

    def run():
        until = time.monotonic() + 300
        while time.monotonic() < until:
            seen = bench.sql("SET search_path TO payment_schema,public; SELECT count(*) FROM p_payment_result_outbox "
                             "WHERE order_id LIKE 'rr384-%-m-%';", database)
            if seen and int(seen) > 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("측정 시작을 확인하지 못했다")
        time.sleep(stop_after)
        stopped = time.time()
        bench.execute(bench.COMPOSE + ["stop", "kafka"])
        time.sleep(outage)
        bench.execute(bench.COMPOSE + ["start", "kafka"])
        started = time.time()
        bench.save(ASSETS / "fault-last.json", {"database": database, "stoppedEpoch": stopped, "startedEpoch": started,
                                                  "outageSeconds": started - stopped})

    thread = threading.Thread(target=run, name="broker-outage", daemon=True)
    thread.start()
    return thread


def run(java, directory, label, name, scenario, first, second, cap, overrides, fault=False):
    case = f"rr_{label}_{name}"
    bench.clone(SimpleNamespace(case=case, pipeline="FINANCIAL", posting_schema=True))
    prepared = json.loads((bench.ASSETS / "prepared.json").read_text(encoding="utf-8"))
    database = prepared["clones"][case]["database"]
    output = directory / f"{name}.json"
    classpath = (bench.SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
    tokens = ["-Xms256m", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-cp", classpath,
              MAIN, database, output, scenario, first, second, cap, *overrides]
    argfile = output.with_suffix(".args")
    argfile.parent.mkdir(parents=True, exist_ok=True)
    argfile.write_text("\n".join('"' + str(token).replace("\\", "/").replace('"', '\\"') + '"' for token in tokens),
                       encoding="utf-8")
    print(f"{label}/{name}: {scenario} {first} {second} 시작", flush=True)
    resource = {"before": bench.resources()}
    outage = broker_outage(database, 30, 30) if fault else None
    try:
        bench.execute([java, "@" + str(argfile)], cwd=ROOT / "backend", log=output.with_suffix(".log"),
                      timeout=second + cap + 400)
    finally:
        resource["after"] = bench.resources()
        bench.save(directory / f"{name}.resources.json", resource)
    if outage:
        outage.join(400)
        if outage.is_alive():
            raise RuntimeError("브로커 중단 스레드가 끝나지 않았다")
        shutil.copy(ASSETS / "fault-last.json", directory / f"{name}.fault.json")
    report = json.loads(output.read_text(encoding="utf-8"))
    summary = {key: report.get(key) for key in
               ["success", "inserted", "published", "windowPublishPerSecond", "publishPerSecond", "drainedAtSeconds",
                "feedMaxLagMillis", "publishWaitMillis", "attemptsTotal", "lastErrorRunBudget", "pairsDelivered",
                "pairsInverted", "duplicateDeliveries", "deliveryFailures"]}
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return {"name": name, "database": database, "output": str(output), "success": report["success"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label", help="회차 묶음 이름. 예: base1")
    parser.add_argument("--only", nargs="+", help="SUITE 이름 일부만 실행")
    parser.add_argument("--smoke", action="store_true", help="짧은 예행만 실행")
    parser.add_argument("--fault", action="store_true", help="브로커 30초 중단 시험만 실행")
    parser.add_argument("--set", nargs="*", default=[], help="발행기 설정. 예: batch-size=500 fixed-delay-ms=100")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9]{0,7}", args.label):
        parser.error("label은 소문자로 시작하는 영숫자 1~8자다")
    overrides = []
    for value in args.set:
        if not re.fullmatch(r"[a-z-]+=[0-9a-z]+", value):
            parser.error("--set 형식은 이름=값이다")
        overrides.append("--payment.messaging.result-relay." + value)
    selected = SMOKE if args.smoke else FAULT if args.fault else [item for item in SUITE if not args.only or item[0] in args.only]
    directory = ASSETS / args.label
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 17이 필요하다")
    artifact = bench.build(java)
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {
        "label": args.label, "sourceHead": bench.execute(["git", "rev-parse", "HEAD"]),
        "sourceHash": artifact["sourceHash"], "sourceDiff": bench.execute(["git", "status", "--short"]),
        "java": artifact["javaVersion"], "overrides": overrides, "runs": [],
        "limits": {"postgresCpus": 2, "postgresMemory": "2g", "kafkaCpus": 2, "kafkaMemory": "1g",
                   "jvmActiveProcessorCount": 2, "jvmHeap": "256m~768m", "appPoolMax": 20, "inserters": 4,
                   "warmRows": 200, "history": 100000},
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    if manifest["sourceHash"] != artifact["sourceHash"] or manifest["overrides"] != overrides:
        raise RuntimeError("같은 label의 소스·설정이 다르다. 새 label을 사용한다")
    for name, scenario, first, second, cap in selected:
        if (directory / f"{name}.json").exists():
            raise RuntimeError(f"{name}: 같은 label의 결과가 이미 있다. 원본을 보존하고 새 label을 사용한다")
    for name, scenario, first, second, cap in selected:
        manifest["runs"].append(run(java, directory, args.label, name, scenario, first, second, cap, overrides, args.fault))
        bench.save(manifest_path, manifest)
    print(f"결과: {directory}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        print(f"중단: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
