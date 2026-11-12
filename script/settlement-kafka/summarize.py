"""완료한 최종 회차의 창을 구분해 숫자를 모은다. 측정/DB 변경은 하지 않는다."""
import argparse
import json
import re
import measure as bench


def cgroup(raw):
    cpu = int(re.search(r"^usage_usec (\d+)$", raw, re.M)[1]) / 1_000_000
    memory = [int(line) for line in raw.splitlines() if line.isdigit()]
    io = {key: 0 for key in ["rbytes", "wbytes", "rios", "wios"]}
    for line in raw.splitlines():
        if re.match(r"\d+:\d+ ", line):
            for key, value in re.findall(r"(\w+)=(\d+)", line):
                if key in io:
                    io[key] += int(value)
    return {"cpuSeconds": cpu, "currentBytes": memory[0], "lifetimePeakBytes": memory[1], **io}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", default="final")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,10}", args.prefix):
        raise ValueError("전용 결과 prefix가 필요하다")
    rows = []
    artifacts = []
    for suffix in ["db1", "k1", "db2", "k2", "db4", "k4", "d", "b"]:
        directory = bench.ASSETS / "runs" / (args.prefix + suffix)
        result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        if not result.get("success") or not result.get("oracleAfter", {}).get("valid"):
            raise RuntimeError(f"실패/금융 미검증 회차: {directory.name}")
        artifacts.append(manifest["artifact"]["sourceHash"])
        measured = result["measured"]
        resources = json.loads((directory / "resources.json").read_text(encoding="utf-8"))
        row = {"case": directory.name, "mode": manifest["mode"], "workers": manifest["workers"],
               "pipeline": manifest["pipeline"], "inputSeconds": measured["dispatchSeconds"],
               "allDoneSeconds": measured["elapsedSeconds"], "drainSeconds": measured["drainSeconds"],
               "completed": measured["completed"], "projectionApplied": measured.get("projectionApplied", 0),
               "completionPerSecond": measured["completionThroughputPerSecond"],
               "financialP99Millis": measured["scheduledToObservedDoneMillis"]["p99"],
               "projectionP99Millis": measured.get("scheduledToObservedProjectionMillis", {}).get("p99"),
               "financialToProjectionP99Millis": measured.get("financialToObservedProjectionMillis", {}).get("p99"),
               "approvalTxP99Millis": measured["approvalTxMillis"]["p99"],
               "generatorP99Millis": measured["generatorDispatchDelayMillis"]["p99"],
               "observerReturnedRows": measured["observerReturnedRows"],
               "jvmMeasuredCpuSeconds": (result["jvmAfter"]["cpuNanos"]-result["jvmBefore"]["cpuNanos"])/1e9,
               "jvmMeasuredEndHeapBytes": result["jvmAfter"]["heapUsedBytes"],
               "wholeJvmSeconds": result["totalSeconds"], "bootSeconds": result["bootSeconds"],
               "verifySeconds": result["verifySeconds"], "financialValid": True,
               "projectionValid": result.get("projectionOracle", {}).get("valid")}
        row["databaseMeasuredDelta"] = {key: result["databaseAfter"][key]-result["databaseBefore"][key]
                                        for key in ["xact_commit", "xact_rollback", "blks_read", "blks_hit", "temp_bytes", "deadlocks", "blk_read_time", "blk_write_time"]}
        row["wholeRunContainers"] = {}
        for service in ["postgres", "kafka"]:
            before, after = [cgroup(resources[point][service]) for point in ["before", "after"]]
            row["wholeRunContainers"][service] = {key: after[key]-before[key] for key in ["cpuSeconds", "rbytes", "wbytes", "rios", "wios"]}
            row["wholeRunContainers"][service]["endMemoryBytesIncludingFileCache"] = after["currentBytes"]
        rows.append(row)
    if len(set(artifacts)) != 1:
        raise RuntimeError("최종 회차 산출물 hash가 다르다")
    report = {"artifactHash": artifacts[0], "allFinancialValid": True, "rows": rows,
              "limits": ["PG/판매자 HTTP TPS 제외", "100/s 60초 단일 회차, 반복 통계/포화 최대 TPS 아님",
                         "금융/전체 파이프라인 종료 기준 다름", "앱/JDBC measured snapshot 대 컨테이너 whole run 창 다름",
                         "DB 모드에서도 Kafka가 상주함", "cgroup memory는 파일 cache 포함이며 lifetime peak를 회차 peak로 쓰지 않음",
                         "SQL top30은 이행/기동/예열/oracle 포함, 완전 WAL/측정 창 SQL delta 아님",
                         "Hikari acquire/행 잠금 대기 histogram을 직접 수집하지 않아 감소를 주장할 수 없음",
                         "애플리케이션 OS CPU quota/멀티 broker HA/장시간 hot-key 스트레스 미검증"]}
    bench.save(bench.ASSETS / "summaries" / (args.prefix + ".json"), report)
    for row in rows:
        print(json.dumps({key: row[key] for key in ["case", "completed", "financialP99Millis", "projectionP99Millis", "drainSeconds", "jvmMeasuredCpuSeconds", "wholeRunContainers"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
