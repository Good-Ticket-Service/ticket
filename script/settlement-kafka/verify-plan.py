"""새 세대 대량 삽입 직후 receipt 대조 계획. 기존 기준본은 변경하지 않고 전용 복제본만 사용한다."""
import json
import time
import uuid
import measure as bench

DATABASE = "sk363_verifyplan_12c862cc"
OUTPUT = bench.ASSETS / "diagnostics/verify-plan-v2.json"


def main():
    if OUTPUT.exists():
        previous = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if not previous.get("diagnosticAutovacuumRestored") or len(previous.get("queries", {})) != 2:
            raise RuntimeError("미완성 진단 자료를 보존한다. 전용 복제본과 통계 설정을 확인한다")
        print(f"완료한 진단 재사용: {OUTPUT}")
        return
    candidate = str(uuid.UUID("4e575c73-0a77-4c90-8f2b-208f5693b364"))
    prefix = "SET ROLE sk363_app; SET search_path TO payment_schema,public; "
    exists = bench.sql(prefix + f"SELECT count(*) FROM p_settlement_projection_builds WHERE id='{candidate}';", DATABASE)
    if exists != "0":
        raise RuntimeError("진단 candidate가 이미 있다. 실패 자료를 보존하고 확인한다")
    bench.sql(prefix + f"""
BEGIN;
ALTER TABLE p_settlement_projection_deliveries SET (autovacuum_enabled=false);
ALTER TABLE p_settlement_projection_applied SET (autovacuum_enabled=false);
UPDATE p_settlement_projection_builds SET status='FAILED' WHERE id='4e575c73-0a77-4c90-8f2b-208f5693b363' AND actor='sk363-fixture';
INSERT INTO p_settlement_projection_builds(id,mode,status,actor,reason)
VALUES('{candidate}','B','VERIFYING','sk363-fixture','신규 세대 통계 전 대조 계획');
INSERT INTO p_settlement_projection_deliveries(build_id,fact_key,seller_id,schedule_id,business_date,published_at,applied)
SELECT '{candidate}',fact_key,seller_id,schedule_id,business_date,CURRENT_TIMESTAMP,true
FROM p_settlement_projection_facts;
INSERT INTO p_settlement_projection_applied(build_id,fact_key,meaning_hash)
SELECT '{candidate}',fact_key,meaning_hash FROM p_settlement_projection_facts;
COMMIT;
""", DATABASE)
    old = f"""SELECT count(*) FROM p_settlement_projection_deliveries d
JOIN p_settlement_projection_facts f ON f.fact_key=d.fact_key
LEFT JOIN p_settlement_projection_applied a ON a.build_id=d.build_id AND a.fact_key=d.fact_key
WHERE d.build_id='{candidate}' AND (d.published_at IS NULL OR a.fact_key IS NULL OR a.meaning_hash<>f.meaning_hash)"""
    new = f"""SELECT count(*) FROM (
SELECT d.fact_key,f.meaning_hash FROM p_settlement_projection_deliveries d
JOIN p_settlement_projection_facts f USING(fact_key) WHERE d.build_id='{candidate}'
EXCEPT SELECT fact_key,meaning_hash FROM p_settlement_projection_applied WHERE build_id='{candidate}'
) missing"""
    stats = "SELECT json_agg(row_to_json(s)) FROM (SELECT relname,last_analyze,last_autoanalyze,n_live_tup,n_mod_since_analyze FROM pg_stat_user_tables WHERE relname IN ('p_settlement_projection_deliveries','p_settlement_projection_applied') ORDER BY relname) s;"
    result = {"database": DATABASE, "candidate": candidate, "members": 110000,
              "scope": "새 세대 bulk 삽입 직후. 전용 진단 복제본의 두 테이블만 자동 통계를 잠시 중지; 각 5초 제한. 처리량 부하 시험 아님",
              "statsBefore": json.loads(bench.sql(prefix + stats, DATABASE)), "queries": {}}
    for name, statement in [("leftJoin", old), ("setDifference", new)]:
        plan = json.loads(bench.sql(prefix + "EXPLAIN (FORMAT JSON) " + statement, DATABASE))
        before = time.perf_counter()
        try:
            actual = json.loads(bench.sql(prefix + "SET statement_timeout='5s'; EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + statement, DATABASE))
            value = {"success": True, "actualPlan": actual}
        except RuntimeError as error:
            value = {"success": False, "failure": str(error)}
        value.update(plan=plan, wallSeconds=time.perf_counter()-before)
        result["queries"][name] = value
        bench.save(OUTPUT, result)
        print(json.dumps({"query": name, "success": value["success"], "wallSeconds": value["wallSeconds"]}))
    result["statsAfter"] = json.loads(bench.sql(prefix + stats, DATABASE))
    bench.sql(prefix + "ALTER TABLE p_settlement_projection_deliveries RESET (autovacuum_enabled); ALTER TABLE p_settlement_projection_applied RESET (autovacuum_enabled);", DATABASE)
    result["diagnosticAutovacuumRestored"] = True
    bench.save(OUTPUT, result)


if __name__ == "__main__":
    main()
