# 서버 배포 (AWS EC2 + Docker Compose)

구성과 이유는 [docs/infra.md](../docs/infra.md)에 있다. 이 폴더는 그 구성을 실행하는 파일이다.
아래는 첫 배포를 손으로 하는 순서다. AWS 계정·보안 그룹·IAM·ECR·S3·EC2는 미리 만들어 둔다.

| 파일 | 서버 | 띄우는 것 |
|---|---|---|
| `compose.data.yml` | vm-data | Postgres, Redis, Kafka, fake-pg, Prometheus, Alertmanager, Grafana, Kafka UI, node-exporter, cAdvisor, postgres·redis·kafka exporter |
| `compose.edge.yml` | vm-edge | Caddy, config-server, eureka, gateway, user, performance, waiting-room, node-exporter |
| `compose.core.yml` | vm-core | reservation, payment, node-exporter |
| `Caddyfile` | vm-edge | `/api/*` → gateway, 나머지는 프론트 정적 파일 |
| `postgres/schemas.sh` | vm-data | 빈 DB에 서비스 테이블 생성 |
| `postgres/metrics.sh` | vm-data | 지표 수집 계정(`metrics_app`)과 SQL 통계 확장(`pg_stat_statements`) 생성 |
| `postgres/exporter-queries.yaml` | vm-data | postgres-exporter의 진단 쿼리(계정별 세션·대기, 오래 열린 트랜잭션, 잠금 대기, WAL, 계정별 SQL 통계) |
| `prometheus/prometheus.yml` | vm-data | 서버용 수집 설정(`environment: dev`, 서버·컨테이너 자원 포함) |
| `grafana/` | vm-data | 대시보드 목록과 서버용 대시보드 3개(서버 자원, 서비스, 데이터). 결제 대시보드는 `infra/grafana`를 함께 쓴다 |
| `env/<서버>.env.example` | 각 서버 | 그 서버의 `/opt/ticket/.env` 틀. 그 서버에 필요한 값만 있다 |
| `build-push.sh` | 노트북 | 이미지 9개를 amd64로 빌드해 ECR에 올림 |

## 0. 서버 준비 (서버마다 처음 한 번)

SSM으로 접속해 Docker와 AWS CLI를 설치한다. Docker를 설치한 직후, 이미지를 받기 전에 containerd 저장소를 끈다.
Docker 29부터는 새로 설치하면 켜져 있고, 켜져 있으면 cAdvisor가 컨테이너를 찾지 못해 컨테이너별 CPU가 비어 있다.
AWS CLI는 snap 대신 AWS 공식 설치 파일을 쓴다. EC2에서 snap 스토어 접속이 시간 초과로 실패한 적이 있다.

```bash
curl -fsSL https://get.docker.com | sudo sh
echo '{"features":{"containerd-snapshotter":false}}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
sudo docker info --format '{{.Driver}}'
sudo apt-get install -y unzip
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp && sudo /tmp/aws/install
sudo mkdir -p /opt/ticket
```

처음 켠 직후에는 자동 업데이트가 apt를 잡고 있어 설치가 잠금 대기로 멈출 수 있다. 몇 분 기다렸다 다시 실행한다.

`docker info`가 `overlay2`를 출력해야 한다. vm-data에서는 Redis가 AOF를 다시 쓸 때 fork가 메모리 부족으로 실패하지 않게 한 번 더 실행한다.

```bash
echo 'vm.overcommit_memory = 1' | sudo tee /etc/sysctl.d/99-redis.conf && sudo sysctl --system
```

## 1. 이미지 올리기 (노트북)

배포할 develop 커밋을 깨끗하게 체크아웃한 뒤 실행한다. 마지막 줄의 `IMAGE_TAG`를 서버 `.env`에 넣는다.

```bash
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin <ECR 레지스트리>
deploy/build-push.sh <ECR 레지스트리>
```

## 2. 배포 파일 올리기 (노트북 → S3 → 서버)

서버의 `/opt/ticket`에 저장소와 같은 상대 경로로 둔다. compose가 `../infra`, `../backend/sql`을 참조한다.

```bash
(cd frontend && npm ci && npm run build)
for d in deploy infra backend/sql script/alert-mock frontend/dist; do
  aws s3 sync "$d" "s3://<버킷>/<IMAGE_TAG>/$d" --delete --exclude 'integration/*'
done
```

각 서버에서(SSM 세션):

```bash
for d in deploy infra backend/sql script/alert-mock frontend/dist; do
  sudo aws s3 sync "s3://<버킷>/<IMAGE_TAG>/$d" "/opt/ticket/$d" --delete
done
```

폴더별로 받으므로 `/opt/ticket/.env`와 `/opt/ticket/secrets/`는 지워지지 않는다.

## 3. .env 만들기 (서버마다 처음 한 번)

서버마다 자기 틀(`deploy/env/<data|edge|core>.env.example`)로 `/opt/ticket/.env`를 만든다. 다른 서버 몫의 값은 넣지 않는다.
값을 채우기 전에 root만 읽고 쓸 수 있게(권한 600) 만든다. 이미 있으면 덮어쓰지 않는다.

```bash
cd /opt/ticket
sudo test -e .env || sudo install -m 600 -o root -g root deploy/env/<data|edge|core>.env.example .env
sudoedit .env
sudo ls -l .env
```

마지막 줄이 `-rw------- 1 root root`로 나와야 한다.

## 4. 띄우기 (data → edge → core 순서)

`.env`는 root만 읽을 수 있으므로 docker 명령도 sudo로 실행한다.

```bash
cd /opt/ticket
sudo sh -c 'aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin "$(grep ^ECR_REGISTRY= .env | cut -d= -f2)"'
sudo docker compose -f deploy/compose.<data|edge|core>.yml --env-file .env up -d
sudo docker compose -f deploy/compose.<data|edge|core>.yml --env-file .env ps
```

## 5. 확인

- vm-edge: `curl -s localhost:8761/eureka/apps | grep -o '<ipAddr>[^<]*'` — 서비스마다 vm-edge·vm-core의 VPC IP가 보여야 한다. `172.17.`이 보이면 `EDGE_IP`·`CORE_IP` 값을 확인한다.
- 노트북: `curl -s -o /dev/null -w '%{http_code}\n' https://<DOMAIN>/api/users/me` → `401`
- 노트북: `https://<DOMAIN>/actuator/prometheus`가 지표가 아니라 프론트 HTML을 돌려줘야 한다.
- Grafana·Kafka UI는 vm-data로 SSM 포트 포워딩해서 본다(3000, 8989).
- Prometheus(9090)도 같은 방법으로 열어 Status → Targets에서 `node` 3개, `cadvisor`, `postgres`, `redis`, `kafka`, `fake-pg`가 UP인지 본다.
- Grafana의 Server 폴더에서 「서버 01 · 서버 자원」, 「서버 02 · 서비스」, 「서버 03 · 데이터」를 연다. 서비스 대시보드의 응답 시간 p95·p99는 compose가 켠 히스토그램으로 계산한다.
- Grafana Explore에서 시험 시간대의 CPU를 직접 볼 때는 아래 쿼리를 쓴다.
  - 서버별 CPU 사용률: `1 - avg by (server) (rate(node_cpu_seconds_total{mode="idle"}[1m]))`
  - vm-data 컨테이너별 CPU(코어 수): `sum by (container_label_com_docker_compose_service) (rate(container_cpu_usage_seconds_total{container_label_com_docker_compose_service!=""}[1m]))`

## 주의

- 비밀값은 서버마다 그 서버 몫만 둔다. vm-core에는 DB 관리자 비밀번호와 JWT·입장 토큰 키가 없으므로, vm-core가 뚫려도 토큰을 위조하거나 다른 서비스의 DB에 들어갈 수 없다.
- `.env`를 권한 600으로 막아도 서버에서 docker를 쓸 수 있는 사람은 `docker inspect`로 컨테이너에 넘긴 값을 볼 수 있다. 서버 접속(SSM) 권한은 필요한 사람에게만 준다.
- 비밀값을 채팅·슬랙·이슈에 붙여 넣지 않는다.
- `docker compose down -v`를 쓰지 않는다. DB·Kafka·Redis 볼륨이 지워진다.
- 지표 계정과 SQL 통계 확장(`postgres/metrics.sh`)도 빈 볼륨에서만 자동으로 돈다. 이미 데이터가 있는 DB에는 vm-data에서 한 번 실행한다. 여러 번 실행해도 결과가 같다.

  ```bash
  cd /opt/ticket && sudo docker compose -f deploy/compose.data.yml --env-file .env exec -T postgres bash -s < deploy/postgres/metrics.sh
  ```

- Postgres 기동 옵션(`command`)이나 볼륨을 바꾸면 `up -d` 때 Postgres 컨테이너가 다시 만들어진다. 데이터 볼륨은 그대로지만 몇 초 동안 접속이 끊긴다.
- 테이블 생성(`postgres/schemas.sh`)은 빈 볼륨에서 한 번만 돈다. 그 뒤 스키마가 바뀌면 `backend/sql/<서비스>/migrations/`의 SQL을 해당 서비스 계정으로 직접 적용하고 앱을 다시 띄운다.
- `backend/config-repo` 설정은 config-server 이미지 안에 있다. 설정만 바꿔도 이미지를 다시 올리고 vm-edge·vm-core 앱을 다시 띄운다.
- node-exporter·cAdvisor도 서버 CPU를 조금 쓴다. 시험 결과는 수집기를 켠 같은 조건끼리 비교한다.
- 앱은 `restart: unless-stopped`다. 서버를 끄고 켜면 Docker가 다시 띄우고, 다른 서버가 늦게 떠도 config-server에 닿을 때까지 재시작하며 맞춰진다.
