"""직접 생성한 JVM만 종료해 Outbox ACK 유실·금융 commit/offset 간격·다중 JVM 인수를 확인한다."""
import argparse
import json
import shutil
import subprocess
import time
from types import SimpleNamespace
import measure as bench

MAIN = "com.sparta.ticket.payment.infrastructure.persistence.settlement.SettlementKafkaFaultProcessMain"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", default="fault1")
    parser.add_argument("--scenario", choices=["posting", "projection"], default="posting")
    args = parser.parse_args()
    java = shutil.which("java")
    artifact = bench.build(java)
    pipeline = "B" if args.scenario == "projection" else "FINANCIAL"
    bench.clone(SimpleNamespace(case=args.case, pipeline=pipeline, posting_schema=True))
    prepared = json.loads((bench.ASSETS / "prepared.json").read_text(encoding="utf-8"))
    database = prepared["clones"][args.case]["database"]
    directory = bench.ASSETS / "faults" / args.case
    if directory.exists():
        raise RuntimeError("기존 장애 실행 자료를 보존한다. 새 --case를 지정한다")
    directory.mkdir(parents=True)
    bench.save(directory / "manifest.json", {"artifact": artifact, "database": database,
               "scope": "실제 전용 JVM 강제 종료. 동일 DB·단일 RF1 broker. 다중 broker HA 검증 아님",
               "newPayments": 100, "testSessionTimeoutMs": 6000, "testLeaseSeconds": 3})
    handles = []
    streams = []
    report = {"database": database, "success": False}
    started = time.perf_counter()

    def launch(name, role, point="NONE"):
        path = directory / name
        path.mkdir()
        classpath = (bench.SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
        tokens = ["-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-cp", classpath,
                  MAIN, role, database, str(path), point, pipeline]
        argfile = path / "java.args"
        argfile.write_text("\n".join('"' + token.replace("\\", "/").replace('"', '\\"') + '"' for token in tokens), encoding="utf-8")
        stream = (path / "process.log").open("w", encoding="utf-8")
        process = subprocess.Popen([java, "@" + str(argfile)], cwd=bench.BACKEND, stdout=stream, stderr=subprocess.STDOUT)
        handles.append(process)
        streams.append(stream)
        return process, path

    def wait(condition, process=None, seconds=30):
        deadline = time.perf_counter() + seconds
        while time.perf_counter() < deadline:
            if condition():
                return
            if process and process.poll() is not None:
                raise RuntimeError(f"자식 JVM 조기 종료: pid={process.pid}, exit={process.returncode}")
            time.sleep(.15)
        raise RuntimeError(f"장애 시나리오 대기 {seconds}초 제한")

    def snapshot():
        return json.loads(bench.sql("SET search_path TO payment_schema,public; SELECT json_build_object("
                         "'jobs',count(*),'done',count(*) FILTER(WHERE status='DONE'),"
                         "'published',count(*) FILTER(WHERE published_at IS NOT NULL),'maxAttempts',max(publish_attempts),"
                         "'minAttempts',min(publish_attempts),'identities',md5(string_agg(event_id::text||event_payload,',' ORDER BY id)))"
                         " FROM p_settlement_jobs WHERE payment_id>100000;", database))

    def stop(process):
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)

    try:
        process, path = launch("prepare", "prepare")
        if process.wait(timeout=35) != 0:
            raise RuntimeError("원자 완료 TX fixture 준비 실패")
        report["prepared"] = snapshot()
        if report["prepared"]["jobs"] != 100 or report["prepared"]["done"] != 0:
            raise RuntimeError("금융 Consumer 중지 중 완료 사실/작업 보존 실패")
        if args.scenario == "projection":
            publisher, _ = launch("publisher", "publisher")
            (directory / "resume-consumers").touch(exist_ok=False)
            financial, _ = launch("financial", "consumer")
            wait(lambda: snapshot()["done"] == 100, financial)
            def applied():
                return int(bench.sql("SET search_path TO payment_schema,public; SELECT count(*) FROM p_settlement_projection_applied WHERE build_id=(SELECT build_id FROM p_settlement_projection_head WHERE id=1) AND fact_key IN (SELECT fact_key FROM p_settlement_projection_facts WHERE payment_id>100000);", database))
            report["financeWhileProjectionStopped"] = snapshot()
            report["newProjectedWhileStopped"] = applied()
            if report["newProjectedWhileStopped"] != 0:
                raise RuntimeError("집계 중지 전제가 다르다")
            broken, broken_path = launch("projection-commit-gap", "projection", "PROJECTION_COMMIT")
            wait(lambda: (broken_path / "checkpoint.json").exists(), broken)
            checkpoint = json.loads((broken_path / "checkpoint.json").read_text())
            if checkpoint["pid"] != broken.pid or checkpoint["transactionActive"]:
                raise RuntimeError("집계 commit checkpoint의 PID/TX 경계 불일치")
            report["projectedBeforeKill"] = applied()
            stop(broken)
            resumed_at = time.perf_counter()
            resumed, _ = launch("projection-resume", "projection")
            wait(lambda: applied() == 100, resumed)
            report["restartToAllAppliedSeconds"] = time.perf_counter() - resumed_at
            time.sleep(.5)
            verifier, verify_path = launch("verify", "verify")
            if verifier.wait(timeout=35) != 0:
                raise RuntimeError("집계 재시작 뒤 oracle 실패")
            report["verification"] = json.loads((verify_path / "verified.json").read_text(encoding="utf-8"))
            report["restartToVerifiedRecoverySeconds"] = time.perf_counter() - resumed_at
            stop(resumed)
            stop(financial)
            stop(publisher)
            rollback, rollback_path = launch("rollback", "rollback")
            if rollback.wait(timeout=35) != 0:
                raise RuntimeError("최신 원천 따라잡기/D rollback 실패")
            report["rollback"] = json.loads((rollback_path / "rollback.json").read_text(encoding="utf-8"))
            report["success"] = True
            return
        process, path = launch("publisher-ack-gap", "publisher", "BROKER_ACK")
        wait(lambda: (path / "checkpoint.json").exists(), process)
        checkpoint = json.loads((path / "checkpoint.json").read_text())
        if checkpoint["pid"] != process.pid or checkpoint["transactionActive"]:
            raise RuntimeError("시험 소유 PID/트랜잭션 경계가 다르다")
        report["beforePublisherKill"] = snapshot()
        stop(process)
        process, path = launch("publisher-recovery", "publisher")
        wait(lambda: snapshot()["published"] == 100, process)
        report["afterLeaseRecovery"] = snapshot()
        if report["afterLeaseRecovery"]["identities"] != report["prepared"]["identities"]:
            raise RuntimeError("재발행 사건 식별자/원문 변경")
        if report["afterLeaseRecovery"]["minAttempts"] < 2:
            raise RuntimeError("만료 임대를 재인수하지 않았다")
        stop(process)
        first, path = launch("consumer-commit-gap", "consumer", "DB_COMMIT")
        wait(lambda: (path / "ready.json").exists(), first)
        second, second_path = launch("consumer-takeover", "consumer")
        wait(lambda: (second_path / "ready.json").exists(), second)
        report["secondConsumer"] = json.loads((second_path / "ready.json").read_text())
        if report["secondConsumer"].get("groupMembers") != 2:
            raise RuntimeError("같은 Group의 실제 두 JVM 참여를 확인하지 못했다")
        (directory / "resume-consumers").touch(exist_ok=False)
        wait(lambda: (path / "checkpoint.json").exists(), first)
        checkpoint = json.loads((path / "checkpoint.json").read_text())
        if checkpoint["pid"] != first.pid or checkpoint["transactionActive"]:
            raise RuntimeError("금융 commit checkpoint가 소유 PID/트랜잭션 경계와 다르다")
        report["beforeConsumerKill"] = snapshot()
        killed_at = time.perf_counter()
        stop(first)
        wait(lambda: snapshot()["done"] == 100, second)
        report["takeoverToAllDoneSeconds"] = time.perf_counter() - killed_at
        # 마지막 DB commit과 offset commit 사이를 허용하되 재수신 확인을 건너뛰지 않는다.
        time.sleep(.3)
        verifier, verify_path = launch("verify", "verify")
        if verifier.wait(timeout=35) != 0:
            raise RuntimeError("인수 후 금융/offset 검증 실패")
        report["verification"] = json.loads((verify_path / "verified.json").read_text(encoding="utf-8"))
        if report["verification"]["offsets"]["brokerRecords"] <= 100:
            raise RuntimeError("broker ACK 유실의 실제 중복 전달이 없다")
        report["success"] = True
    finally:
        for process in handles:
            stop(process)
        for stream in streams:
            stream.close()
        report["totalSeconds"] = time.perf_counter() - started
        bench.save(directory / "result.json", report)
        print(json.dumps({"success": report["success"], "totalSeconds": report["totalSeconds"], "result": str(directory / "result.json")}))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
