# 원장·정산 Kafka 전환 측정 자산 (#363)

이 도구는 전용 PostgreSQL/Kafka와 합성 데이터만 사용한다. PG 호출·예매 요청·판매자 조회 API가 아닌 실제 결제 완료 TX→정산 작업 처리 구간을 먼저 비교한다. 금융 이력과 기준 DB를 삭제하거나 덮어쓰는 명령은 없다.

프로젝트 루트에서 실행한다. Python 표준 라이브러리, Docker Compose, Java17 이상이 필요하다. JAVA_HOME은 수정하지 않는다. JVM 실행 파일은 `--java 경로`로 고정할 수 있다. fixture 계정은 이 Compose의 공개 시험 계정이며 운영 연결은 지원하지 않는다.

```powershell
docker compose -f script/settlement-kafka/compose.yml up -d --wait
python script/settlement-kafka/measure.py prepare
python script/settlement-kafka/measure.py check
python script/settlement-kafka/measure.py run --case rehearsal --workers 1 --count 100 --rate 50
python script/settlement-kafka/measure.py run --case db1 --workers 1 --warm 1000 --count 6000 --rate 100
python script/settlement-kafka/measure.py run --case db2 --workers 2 --warm 1000 --count 6000 --rate 100
python script/settlement-kafka/measure.py run --case db4 --workers 4 --warm 1000 --count 6000 --rate 100
python script/settlement-kafka/measure.py report
```

- `prepare`: schema/fixture가 같으면 봉인한 기준 DB를 재사용한다. 과거 SALE10만·환불1만·READY7천을 한 번 준비하고 원천 fact와 금융 대조를 검증한다. 과거 D 집계 초기 구축은 전체 파이프라인 비교 단계에서 별도로 한 번 수행한다.
- `check`: 기준본 봉인과 회차별 신규 결제 사용 여부·미처리 작업을 표시한다. 소비가 진행된 회차도 그대로 보존한다.
- `run`: 소스가 같으면 컴파일 산출물을 재사용한다. 승인 접수/완료는 실제 application TX, PG 승인 응답만 제어 입력이다. 기존 워커의 batch100·실행 후1초 대기를 유지한다. 고정 업무 시각과 실측 monotonic 시간을 구분한다.
- 같은 조건으로 성공한 `run`을 다시 호출하면 기존 결과 경로만 반환한다. 조건 변경이나 실패/중단은 자동 재실행하지 않는다. `prepare --cases db1_retry`로 같은 기준의 새 복제본을 만든다. 과거 실행 DB/결과는 보존한다.
- 준비/복제는 측정 창 밖에서 한다. DB/Kafka CPU quota2, Java ActiveProcessorCount=2·heap768MB·DB풀20을 고정한다. Java의 인식 CPU 수는 호스트 OS quota가 아니므로 앱 CPU2 제한의 포화 TPS로 해석하지 않는다. 내부 입력 스레드8개의 지연을 함께 수집해 생성기가 목표 입력률을 못 따른 경우를 구분한다.
- 결과/manifest/로그/Java argfile은 `backend/payment-service/build/settlement-benchmark/` 아래에 모인다. DB 전후 통계와 JVM CPU/heap, 전용 컨테이너 cgroup CPU/I/O 누적값을 저장한다. 다른 서비스 통계를 초기화하지 않는다.
- DONE 관측은100ms 폴링이다. 예정 입력→DONE 지연에는 관측 오차와 폴링 읽기 부하가 포함된다. PG HTTP 지연, Kafka 전달 지연, 판매자 집계 완료 지연을 뜻하지 않는다.
- 모든 입력 완료를 최대180초 drain으로 기다린다. 미처리/실패 시 성공 결과로 기록하지 않는다. 원장·정산·작업·fact·환불·판매자 잔액 oracle을 전후 검증한다.

기준선 생성 이후 스키마를 확장할 때는 기준본을 복제하고 필요한 이행만 적용한다. 과거 이력을 다시 생성하지 않는다.

```powershell
python script/settlement-kafka/measure.py clone --case ksmoke --posting-schema
python script/settlement-kafka/measure.py run --case ksmoke --mode kafka --workers 2 --count 100 --rate 50
```

`--posting-schema`는 이행026/027을 복제본에 적용한다. `--mode kafka`는 전용 namespace topic/group을 만들고6파티션 할당을 기다린 뒤 실제 완료 TX→발행기→금융 Consumer를 실행한다. 완료 후10%를 중복 전송하고 전체 offset 수렴·금융 oracle을 확인한다. DB 모드는 `--mode db`(기본값)다. 성공한 과거 baseline raw manifest는 이전 실행기 버전 조건으로 보존되며 새 소스/조건으로 조용히 재실행하지 않는다.

제품 신규 기본값은 KAFKA/B다. 기존 인스턴스·DB 작업·집계 세대의 전환은 docs/infra.md를 따른다. 시험 실행기는 DB 비교군을 명시적으로 선택하고 PG/지급 스케줄러를 끈다. 단일 브로커 복제계수1 환경이므로 HA·복제 장애 내구성으로 일반화할 수 없다.

과거 집계는 한 번 준비해 봉인하고 매 회차 복제한다. 전체 파이프라인의 완료 기준은 금융 DONE과 집계 applied를 구분한다.

```powershell
python script/settlement-kafka/measure.py template --pipeline D
python script/settlement-kafka/measure.py template --pipeline B
python script/settlement-kafka/measure.py clone --case full_d --pipeline D
python script/settlement-kafka/measure.py clone --case full_b --pipeline B
python script/settlement-kafka/measure.py run --case full_d --mode db --pipeline D --workers 4 --warm 1000 --count 6000 --rate 100
python script/settlement-kafka/measure.py run --case full_b --mode kafka --pipeline B --workers 4 --warm 1000 --count 6000 --rate 100
python script/settlement-kafka/fault-process.py --case own_fault --scenario posting
python script/settlement-kafka/fault-process.py --case own_projection_fault --scenario projection
```

template의 과거 상태는 합성 초기 상태이며 Consumer가11만 건을 실제 처리한 속도로 보고하지 않는다. 새 입력은 실제 TX/실행기를 거친다. template 준비 실패/중단은 보존하고 자동 덮어쓰지 않는다. source/DDL fingerprint가 바뀌어도 기존 금융 seed를 자동 재생성하지 않는다.

fault-process는 자기 Process 객체로 생성한 Java PID만 종료한다. POSTING은 broker ACK/발행 기록 간격과 금융 commit/offset 간격, 실제 두 JVM의 Group 참여·인수를 확인한다. PROJECTION은 집계 중지 중 금융 계속 처리·집계 commit/offset 간격·최신 원천을 반영한 D rollback을 확인한다. 로컬 session6초/lease3초는 이 작은 시험에만 사용한다. 결과는 faults/<case>에 남기며 실패 회차를 재사용하지 않는다.

전체 비교는 `measure.py suite --suite-prefix final --warm 1000 --count 6000 --rate 100`으로8회를 순차 실행하고 `summarize.py --prefix final`로 완료·동일 산출물과 자원 창을 확인한다. 금융1/2/4 쌍과 전체 D/B는 종료 기준이 다르다. 기존 성공 회차를 다른 조건으로 덮어쓰지 않는다.

실제 PG HTTP 승인200·환불40은 backend에서 `./gradlew.bat :payment-service:test --tests '*SettlementKafkaDomainHttpTest' -PsettlementKafkaDomain=true`로 별도 실행한다. 예매 JVM·쿠폰·좌석을 포함한 고객 여정 성능 시험은 아니다. HELD로 발행 제외·발행 전 REVERSE·발행 후 RECEIVABLE/DEDUCTION과 금융/B 집계 금액을 검증한다.

실제 DLT Slack 경보 재현은 전용 postgres/kafka가 시작된 상태에서 `python script/settlement-kafka/alert-live.py --webhook-file /path/to/slack_webhook_url`를 실행한다. 기존 컴파일·seed를 재사용하고 전용 DB를 복제한다. 결제 JVM이 잘못된 JSON 한 건을 실제 소비·격리하고, 제품 관측 지표와 제품 Prometheus 규칙·Alertmanager 설정·Slack 템플릿을 그대로 거친다. 가짜 지표나 Alertmanager 경보 주입 API를 사용하지 않는다.

실제 Slack webhook은 `--webhook-file`로 지정한 비추적 전용 파일에서만 읽으며 출력·결과에 복사하지 않는다. 외부 발송이므로 명시적으로 요청받은 경우에만 실행한다. 앱19065·Prometheus19095·Alertmanager19096·Grafana19097 포트를 사용하고 기존 점유 시 중단한다. Grafana는 localhost에서 익명 Viewer로 읽기만 허용한다. 최초 기동과 실제 지표 조회를 확인한 뒤에만 오류 메시지를 전달한다. 결과 `alerts/<case>/result.json`에 직접 생성한 Java PID·compose 경로·DB·실제 경보와 Slack 전송 결과를 남긴다. 환경과 OPEN incident는 자동 종료하지 않으므로 실행 담당자가 확인 후 MASTER 운영 API로 해소한다. 원천 불명 poison은 replay하지 않고 생성 원인을 확인한 뒤 `/incidents/{incidentId}/resolve`에 commandId·reason을 보낸다. 외부 클라이언트는 Gateway 인증을 사용하며 로컬 서비스 포트의 사용자 헤더는 운영 인증을 대체하지 않는다.
