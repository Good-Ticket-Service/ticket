"""#384 승인 종단 지연. #377의 전용 FakePG·봉인 이력 DB를 재사용하고 원본은 삭제하지 않는다."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import sys
import time
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bench = load("settlement_measure", ROOT / "script/settlement-kafka/measure.py")
wait = load("confirm_wait", ROOT / "script/payment-performance/confirm-wait.py")
ASSETS = ROOT / "backend/payment-service/build/end-to-end"
MAIN = "com.sparta.ticket.payment.application.service.payment.ApprovalEndToEndBenchmarkMain"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label")
    parser.add_argument("--rates", nargs="+", type=int, default=[10, 40])
    parser.add_argument("--set", nargs="*", default=[], help="발행기 설정. 예: fixed-delay-ms=100")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9]{0,7}", args.label):
        parser.error("label은 소문자로 시작하는 영숫자 1~8자다")
    overrides = []
    for value in args.set:
        if not re.fullmatch(r"[a-z-]+=[0-9a-z]+", value):
            parser.error("--set 형식은 이름=값이다")
        overrides.append("--payment.messaging.result-relay." + value)
    directory = ASSETS / args.label
    if directory.exists():
        parser.error("같은 label의 결과가 이미 있다. 원본을 보존하고 새 label을 사용한다")
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 17이 필요하다")
    artifact = bench.build(java)
    wait.ASSETS.mkdir(parents=True, exist_ok=True)
    wait.fake_pg(java)
    manifest = {"label": args.label, "sourceHead": bench.execute(["git", "rev-parse", "HEAD"]),
                "sourceHash": artifact["sourceHash"], "sourceDiff": bench.execute(["git", "status", "--short"]),
                "java": artifact["javaVersion"], "overrides": overrides, "runs": [],
                "limits": {"workers": 16, "perProvider": 8, "consumers": 3, "maxPollRecords": 16, "maxInFlight": 48,
                           "retryMillis": 200, "pgLatencyMillis": 300, "jvmActiveProcessorCount": 2, "appPoolMax": 20},
                "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    bench.save(directory / "manifest.json", manifest)
    for rate in args.rates:
        case = f"e2e_{args.label}_{rate}"
        bench.clone(SimpleNamespace(case=case, pipeline="FINANCIAL", posting_schema=True))
        prepared = json.loads((bench.ASSETS / "prepared.json").read_text(encoding="utf-8"))
        database = prepared["clones"][case]["database"]
        output = directory / f"rate-{rate}.json"
        classpath = (bench.SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
        argfile = output.with_suffix(".args")
        wait.java_args(argfile, ["-Xms256m", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                                 "-cp", classpath, MAIN, database, output, rate, rate * 30, *overrides])
        print(f"{args.label}: {rate}건/초, {rate * 30}건 측정 시작", flush=True)
        bench.execute([java, "@" + str(argfile)], cwd=ROOT / "backend", log=output.with_suffix(".log"), timeout=400)
        report = json.loads(output.read_text(encoding="utf-8"))
        manifest["runs"].append({"rate": rate, "database": database, "output": str(output), "success": report["success"]})
        bench.save(directory / "manifest.json", manifest)
        measured = report["measured"]
        print(json.dumps({key: measured[key] for key in ["sentToApprovedMillis", "finalizedToSentMillis",
                                                          "sentToReceivedMillis", "endToEndMillis", "drainSeconds"]},
                         ensure_ascii=False), flush=True)
    print(f"결과: {directory}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        print(f"중단: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
