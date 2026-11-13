"""#396 Kafka 승인 동시성 예산 측정(환경 B). #377의 전용 FakePG DB·봉인 이력 DB를 재사용하고 원본은 삭제하지 않는다.

환경 B는 HTTP 500/s 측정(p13)과 CPU 조건을 맞춘다.
- 결제 DB: 전용 PostgreSQL CPU 6(compose.capacity.yml 겹쳐 쓰기)
- 결제 앱 JVM: 논리 CPU 0~5 고정, ActiveProcessorCount=6, heap 128m~1g
- FakePG JVM: 논리 CPU 6~11 고정, ActiveProcessorCount=6
- Kafka: 전용 단일 브로커 CPU 2(p13과 다르다. 기록한다)
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bench = load("settlement_measure", ROOT / "script/settlement-kafka/measure.py")
wait = load("confirm_wait", ROOT / "script/payment-performance/confirm-wait.py")
ASSETS = ROOT / "backend/payment-service/build/confirm-capacity"
MAIN = "com.sparta.ticket.payment.application.service.payment.ApprovalCapacityBenchmarkMain"
APP_AFFINITY = 0x3F
PG_AFFINITY = 0xFC0
CPUS = 6
COMPOSE = ["docker", "compose", "-f", "script/settlement-kafka/compose.yml",
           "-f", "script/payment-performance/compose.capacity.yml"]
FAKE_PG_MARKER = ASSETS / "fake-pg.json"


def powershell(script):
    return subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout.strip()


def set_affinity(pid, mask):
    powershell(f"(Get-Process -Id {pid}).ProcessorAffinity = {mask}")
    actual = powershell(f"[int64](Get-Process -Id {pid}).ProcessorAffinity")
    if actual != str(mask):
        raise RuntimeError(f"프로세스 {pid}의 CPU 고정 실패: {actual}")
    return int(actual)


def container_cpus(service):
    nano = bench.execute(["docker", "inspect", "-f", "{{.HostConfig.NanoCpus}}",
                          f"ticket-settlement-kafka-{service}-1"])
    return int(nano) / 1e9


def health():
    try:
        with urllib.request.urlopen("http://127.0.0.1:18077/actuator/health", timeout=2) as response:
            return json.load(response).get("status") == "UP"
    except (urllib.error.URLError, OSError):
        return False


def fake_pg(java):
    """환경 B FakePG. 같은 설정으로 떠 있으면 재사용하고, 다른 프로세스가 포트를 쓰면 중단한다."""
    if health():
        marker = json.loads(FAKE_PG_MARKER.read_text(encoding="utf-8")) if FAKE_PG_MARKER.exists() else {}
        if marker.get("purpose") == "issue396" and powershell(f"(Get-Process -Id {marker['pid']}).Id") == str(marker["pid"]):
            marker["affinityVerified"] = set_affinity(marker["pid"], PG_AFFINITY)
            return marker
        raise RuntimeError("18077에 환경 B가 아닌 FakePG가 떠 있다. 소유를 확인하고 먼저 종료한다")
    database = "sk363_cw_fakepg"
    if not bench.database_info(database):
        raise RuntimeError("#377 전용 FakePG DB가 없다")
    jar = ROOT / "backend/fake-pg/build/libs/app.jar"
    if not jar.exists():
        bench.execute([str(ROOT / "backend/gradlew.bat"), ":fake-pg:bootJar"], cwd=ROOT / "backend",
                      log=ASSETS / "fake-pg-compile.log")
    ASSETS.mkdir(parents=True, exist_ok=True)
    argfile = ASSETS / "fake-pg.args"
    wait.java_args(argfile, ["-Xms128m", "-Xmx384m", f"-XX:ActiveProcessorCount={CPUS}", "-Dfile.encoding=UTF-8",
                             "-jar", jar, "--server.address=127.0.0.1", "--server.port=18077",
                             f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:15463/{database}?currentSchema=fake_pg_schema",
                             "--spring.datasource.username=sk363_admin",
                             "--spring.datasource.password=sk363_local_fixture"])
    with (ASSETS / "fake-pg.log").open("w", encoding="utf-8") as stream:
        process = subprocess.Popen([java, "@" + str(argfile)], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
    affinity = set_affinity(process.pid, PG_AFFINITY)
    marker = {"pid": process.pid, "port": 18077, "database": database, "activeProcessorCount": CPUS,
              "affinity": affinity, "heap": "128m~384m", "purpose": "issue396"}
    bench.save(FAKE_PG_MARKER, marker)
    until = time.monotonic() + 60
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError("전용 FakePG 시작 실패. fake-pg.log 확인")
        if health():
            return marker
        time.sleep(.3)
    raise RuntimeError("전용 FakePG 시작 시간 초과")


def environment():
    """환경 B를 적용·검증한다. 기준과 다르면 실행하지 않는다."""
    bench.execute(COMPOSE + ["up", "-d", "--wait", "postgres", "kafka"], timeout=300)
    value = {"postgresCpus": container_cpus("postgres"), "kafkaCpus": container_cpus("kafka"),
             "appAffinity": APP_AFFINITY, "appActiveProcessorCount": CPUS, "appHeap": "128m~1g",
             "hostLogicalCpus": int(powershell("(Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors"))}
    if value["postgresCpus"] != 6 or value["kafkaCpus"] != 2:
        raise RuntimeError(f"컨테이너 CPU 한도가 환경 B와 다르다: {value}")
    return value


def run_java(java, argfile, log, timeout):
    """앱 JVM을 띄우고 즉시 CPU 0~5에 고정한다."""
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.Popen([java, "@" + str(argfile)], cwd=ROOT / "backend", stdout=stream,
                                   stderr=subprocess.STDOUT)
        try:
            affinity = set_affinity(process.pid, APP_AFFINITY)
        except Exception:
            process.kill()
            raise
        code = process.wait(timeout=timeout)
    if code:
        raise RuntimeError(f"실행 실패(exit={code}). 로그: {log}")
    return affinity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label", help="단계 이름. 예: b0")
    parser.add_argument("--rates", nargs="+", type=int, default=[40, 100])
    parser.add_argument("--seconds", type=int, default=30, help="유입 시간(초)")
    parser.add_argument("--toss", type=int, default=100, help="TOSS 비율(%%). 나머지는 KAKAO")
    parser.add_argument("--toss-ms", type=int, default=300)
    parser.add_argument("--kakao-ms", type=int, default=300)
    parser.add_argument("--pool", type=int, default=20)
    parser.add_argument("--drain", type=int, default=180)
    parser.add_argument("--set", nargs="*", default=[],
                        help="승인 소비 설정. 예: concurrency=6 max-poll-records=32 workers=64 per-provider=32 max-in-flight=192")
    parser.add_argument("--suffix", default="", help="같은 label 안의 변형 이름")
    parser.add_argument("--check", action="store_true", help="환경 B 적용·검증만 한다")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9]{0,7}", args.label):
        parser.error("label은 소문자로 시작하는 영숫자 1~8자다")
    overrides = []
    for value in args.set:
        if not re.fullmatch(r"[a-z-]+=[0-9a-z]+", value):
            parser.error("--set 형식은 이름=값이다")
        overrides.append("--payment.messaging.confirm." + value)
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 17이 필요하다")
    env = environment()
    env["fakePg"] = fake_pg(java)
    if args.check:
        print(json.dumps(env, ensure_ascii=False, indent=2), flush=True)
        return
    artifact = bench.build(java)
    directory = ASSETS / args.label
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {
        "label": args.label, "environment": "B", "sourceHead": bench.execute(["git", "rev-parse", "HEAD"]),
        "sourceHash": artifact["sourceHash"], "sourceDiff": bench.execute(["git", "status", "--short"]),
        "java": artifact["javaVersion"], "history": 100000, "warm": 100, "runs": [],
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    if manifest["sourceHash"] != artifact["sourceHash"]:
        raise RuntimeError("같은 label의 소스가 다르다. 새 label을 사용한다")
    for rate in args.rates:
        name = f"r{rate}{args.suffix}"
        output = directory / f"{name}.json"
        if output.exists():
            raise RuntimeError(f"{name}: 같은 label의 결과가 이미 있다. 원본을 보존하고 --suffix나 새 label을 사용한다")
        case = f"cap_{args.label}_{name}"[:21]
        bench.clone(SimpleNamespace(case=case, pipeline="FINANCIAL", posting_schema=True))
        prepared = json.loads((bench.ASSETS / "prepared.json").read_text(encoding="utf-8"))
        database = prepared["clones"][case]["database"]
        classpath = (bench.SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
        argfile = output.with_suffix(".args")
        count = rate * args.seconds
        wait.java_args(argfile, ["-Xms128m", "-Xmx1g", f"-XX:ActiveProcessorCount={CPUS}", "-Dfile.encoding=UTF-8",
                                 "-cp", classpath, MAIN, database, output, rate, count, args.toss, args.toss_ms,
                                 args.kakao_ms, args.pool, args.drain, *overrides])
        print(f"{args.label}/{name}: {rate}건/초 × {args.seconds}초 = {count}건, TOSS {args.toss}%, "
              f"PG {args.toss_ms}/{args.kakao_ms}ms, 풀 {args.pool}, {overrides} 시작", flush=True)
        run_env = dict(environment(), fakePgAffinity=set_affinity(env["fakePg"]["pid"], PG_AFFINITY))
        resource = {"before": bench.resources(), "environment": run_env}
        affinity = None
        try:
            affinity = run_java(java, argfile, output.with_suffix(".log"), args.seconds + args.drain + 600)
        finally:
            resource["after"] = bench.resources()
            bench.save(directory / f"{name}.resources.json", resource)
            report = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {}
            manifest["runs"].append({"name": name, "rate": rate, "seconds": args.seconds, "toss": args.toss,
                                     "tossMs": args.toss_ms, "kakaoMs": args.kakao_ms, "pool": args.pool,
                                     "overrides": overrides, "database": database, "output": str(output),
                                     "environment": run_env, "appAffinity": affinity,
                                     "success": report.get("success")})
            bench.save(manifest_path, manifest)
        measured = report.get("measured", {})
        print(json.dumps({key: measured.get(key) for key in
                          ["approvalLatencyMillis", "drainSeconds", "outcomes", "hikari", "processCpuSeconds",
                           "threadCountPeak"]}, ensure_ascii=False), flush=True)
    print(f"결과: {directory}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        print(f"중단: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
