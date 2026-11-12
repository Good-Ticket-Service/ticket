"""전용 DB의 실제 Consumer poison 격리 → 제품 지표 → Prometheus → 실제 Slack 재현.

Alertmanager API나 가짜 메트릭을 사용하지 않는다. 생성한 자원은 결과에 기록해 운영 조회를 유지한다.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import time
from types import SimpleNamespace
from urllib.request import urlopen

import measure as bench


def request(url):
    with urlopen(url, timeout=5) as response:
        return response.read().decode()


def dashboard_service():
    return {"image": "grafana/grafana:13.2.4", "ports": ["127.0.0.1:19097:3000"],
            "mem_limit": "256m", "environment": {
                "GF_AUTH_ANONYMOUS_ENABLED": "true", "GF_AUTH_ANONYMOUS_ORG_ROLE": "Viewer",
                "GF_AUTH_DISABLE_LOGIN_FORM": "true", "GF_AUTH_BASIC_ENABLED": "false",
                "GF_SECURITY_ADMIN_PASSWORD": secrets.token_urlsafe(32)},
            "volumes": [f"{(bench.ROOT / 'infra/grafana/provisioning').as_posix()}:/etc/grafana/provisioning:ro"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--webhook-file", type=Path, required=True, help="실제 Slack URL 한 줄을 가진 비추적 파일")
    args = parser.parse_args()
    for port in (19065, 19095, 19096, 19097):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"전용 포트 {port} 사용 중. 기존 재현 환경을 먼저 확인한다") from None
    # .env를 읽지 않는다. URL은 검증에만 사용하고 출력·결과·로그에 복사하지 않는다.
    secret = args.webhook_file.resolve(strict=True)
    webhook = secret.read_text(encoding="utf-8-sig").strip()
    if not re.fullmatch(r"https://hooks\.slack\.com/services/[^\s]+", webhook):
        raise RuntimeError("실제 Slack webhook 전용 파일 설정이 필요하다")
    del webhook
    case = "slack" + str(int(time.time()))
    directory = bench.ASSETS / "alerts" / case
    directory.mkdir(parents=True, exist_ok=False)
    bench.clone(SimpleNamespace(case=case, pipeline="FINANCIAL", posting_schema=True))
    prepared = json.loads((bench.ASSETS / "prepared.json").read_text(encoding="utf-8"))
    database = prepared["clones"][case]["database"]
    topic = "sk363." + database + ".posting.v1"
    config = bench.ROOT / "infra/alertmanager/alertmanager.yml"
    prometheus = {
        "global": {"scrape_interval": "5s", "evaluation_interval": "15s",
                   "external_labels": {"environment": "local-consumer-reproduction",
                                       "payment_dashboard_url": "http://localhost:19097/d/payment-operations?viewPanel=48"}},
        "rule_files": ["/etc/prometheus/financial-consumer-alerts.yml"],
        "alerting": {"alertmanagers": [{"static_configs": [{"targets": ["alertmanager:9093"]}]}]},
        "scrape_configs": [{"job_name": "payment-live", "metrics_path": "/actuator/prometheus",
                            "static_configs": [{"targets": ["host.docker.internal:19065"],
                                                "labels": {"application": "payment-service"}}]}],
    }
    (directory / "prometheus.json").write_text(json.dumps(prometheus), encoding="utf-8")
    compose = {"name": "ticket-sk363-live-alert", "services": {
        "alertmanager": {"image": "prom/alertmanager:v0.28.1", "ports": ["127.0.0.1:19096:9093"],
                         "mem_limit": "128m", "volumes": [
                             f"{config.as_posix()}:/etc/alertmanager/alertmanager.yml:ro",
                             f"{(config.parent / 'templates').as_posix()}:/etc/alertmanager/templates:ro",
                             f"{secret.as_posix()}:/etc/alertmanager/secrets/slack_webhook_url:ro"]},
        "prometheus": {"image": "prom/prometheus:v3.15.1", "ports": ["127.0.0.1:19095:9090"],
                       "mem_limit": "256m", "command": ["--config.file=/etc/prometheus/prometheus.json"],
                       "volumes": [f"{(directory / 'prometheus.json').as_posix()}:/etc/prometheus/prometheus.json:ro",
                                   f"{(bench.ROOT / 'infra/prometheus/rules/financial-consumer-alerts.yml').as_posix()}:/etc/prometheus/financial-consumer-alerts.yml:ro"]}}}
    compose_path = directory / "compose.json"
    compose["services"]["grafana"] = dashboard_service()
    compose_path.write_text(json.dumps(compose, indent=2), encoding="utf-8")
    options = ["--spring.profiles.active=measurement", "--spring.cloud.config.enabled=false",
               "--eureka.client.enabled=false", "--server.address=0.0.0.0", "--server.port=19065",
               "--management.endpoints.web.exposure.include=health,prometheus",
               f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:15463/{database}?currentSchema=payment_schema,public",
               "--spring.datasource.username=sk363_app", "--spring.datasource.password=sk363_local_fixture",
               "--spring.datasource.hikari.maximum-pool-size=6", "--spring.datasource.hikari.minimum-idle=1",
               "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
               "--spring.kafka.bootstrap-servers=127.0.0.1:19063",
               "--payment.settlement.posting.transport=KAFKA", "--payment.settlement.posting.kafka.enabled=true",
               "--payment.settlement.posting.kafka.consumer-enabled=true",
               "--payment.settlement.posting.kafka.relay-enabled=false",
               "--payment.settlement.posting.kafka.concurrency=1",
               f"--payment.settlement.posting.kafka.topic={topic}",
               f"--payment.settlement.posting.kafka.group={topic}",
               "--payment.settlement.consumer-recovery.observation-enabled=true",
               "--payment.settlement.consumer-recovery.replay-enabled=true"]
    for feature in ["payment.recovery", "payment.reconcile", "payment.recovery.refund", "payment.observation",
                    "payment.settlement.worker", "payment.settlement.backfill", "payment.settlement.statement",
                    "payment.settlement.payout", "payment.settlement.payout.recovery", "payment.settlement.reconcile",
                    "payment.settlement.projection", "payment.pg", "payment.messaging.confirm",
                    "payment.messaging.result-relay"]:
        options.append("--" + feature + ".enabled=false")
    classpath = (bench.SERVICE / "build/settlement-benchmark-classpath.txt").read_text().strip()
    tokens = ["-Xms128m", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-cp", classpath,
              "com.sparta.ticket.payment.PaymentServiceApplication", *options]
    argfile = directory / "java.args"
    argfile.write_text("\n".join('"' + token.replace("\\", "/").replace('"', '\\"') + '"' for token in tokens), encoding="utf-8")
    with (directory / "application.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([shutil.which("java"), "@" + str(argfile)], cwd=bench.BACKEND,
                                   stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    report = {"database": database, "topic": topic, "pid": process.pid, "composeFile": str(compose_path),
              "application": "http://localhost:19065", "prometheus": "http://localhost:19095",
              "alertmanager": "http://localhost:19096",
              "dashboard": "http://localhost:19097/d/payment-operations", "success": False}
    bench.save(directory / "result.json", report)
    print(f"실제 payment JVM 시작 pid={process.pid}, 결과={directory}", flush=True)
    deadline = time.monotonic() + 65
    while True:
        if process.poll() is not None:
            raise RuntimeError("서비스 시작 실패. 전용 application.log 확인 필요")
        try:
            if "settlement_consumer_incidents_unresolved" in request("http://localhost:19065/actuator/prometheus"):
                break
        except Exception:
            pass
        if time.monotonic() > deadline:
            raise RuntimeError("실제 서비스 지표 시작 시간 초과")
        time.sleep(1)
    bench.execute(["docker", "compose", "-f", str(compose_path), "up", "-d"], timeout=45)
    deadline = time.monotonic() + 240
    print("Grafana 초기 기동·실제 데이터 연결 확인 중", flush=True)
    while True:
        try:
            dashboard = json.loads(request("http://localhost:19097/api/dashboards/uid/payment-operations"))
            query = json.loads(request("http://localhost:19097/api/datasources/proxy/uid/prometheus/api/v1/query?query=settlement_consumer_incidents_unresolved"))
            if any(panel["id"] == 48 for panel in dashboard["dashboard"]["panels"]) and query["data"]["result"]:
                break
        except Exception:
            pass
        if time.monotonic() > deadline:
            raise RuntimeError("Grafana 대시보드 또는 실제 데이터 연결 실패. Slack 경보를 발생시키지 않는다")
        time.sleep(3)
    # 정상 금융 사건을 손상시키지 않는 원천 불명 poison 한 건을 실제 broker로 전달한다.
    bench.execute(bench.COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
                                 "--bootstrap-server", "localhost:9092", "--topic", topic],
                  data='{"version":\n', timeout=30)
    print("실제 broker에 잘못된 JSON 1건 전달. Consumer 격리 및 제품 경보 평가 대기", flush=True)
    deadline = time.monotonic() + 170
    while time.monotonic() < deadline:
        incident = bench.sql("SET search_path TO payment_schema,public; SELECT coalesce(json_agg(x),'[]'::json) FROM "
                             "(SELECT incident_id,reason,status,dlt_confirmed_at FROM p_financial_consumer_incidents) x;", database)
        rows = json.loads(incident)
        metrics = request("http://localhost:19096/metrics")
        sent = sum(float(line.rsplit(" ", 1)[1]) for line in metrics.splitlines()
                   if line.startswith("alertmanager_notifications_total{") and 'integration="slack"' in line)
        failed = sum(float(line.rsplit(" ", 1)[1]) for line in metrics.splitlines()
                     if line.startswith("alertmanager_notifications_failed_total{") and 'integration="slack"' in line)
        alerts = json.loads(request("http://localhost:19095/api/v1/alerts"))
        firing = any(alert["labels"].get("alertname") == "FinancialConsumerIncidentOpen"
                     and alert["state"] == "firing" for alert in alerts["data"]["alerts"])
        if sent >= 1 and failed == 0 and rows and rows[0]["dlt_confirmed_at"] and firing:
            report.update(success=True, incidents=rows, slackNotifications=sent, slackFailed=failed,
                          alerts=alerts,
                          scope="실제 Consumer/DLT/DB 관측/Prometheus/Alertmanager/Slack. 운영 금융 데이터 변경 없음")
            bench.save(directory / "result.json", report)
            print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
            return
        time.sleep(5)
    raise RuntimeError("Slack 성공 확인 시간 초과. URL이 포함될 수 있는 Alertmanager 원시 로그는 출력하지 않는다")


if __name__ == "__main__":
    main()
