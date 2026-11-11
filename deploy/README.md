# 서버 배포 (AWS EC2 + Docker Compose)

구성과 이유는 [docs/infra.md](../docs/infra.md)에 있다. 이 폴더는 그 구성을 실행하는 파일이다.
아래는 첫 배포를 손으로 하는 순서다. AWS 계정·보안 그룹·IAM·ECR·S3·EC2는 미리 만들어 둔다.

| 파일 | 서버 | 띄우는 것 |
|---|---|---|
| `compose.data.yml` | vm-data | Postgres, Redis, Kafka, fake-pg, Prometheus, Alertmanager, Grafana, Kafka UI |
| `compose.edge.yml` | vm-edge | Caddy, config-server, eureka, gateway, user, performance, waiting-room |
| `compose.core.yml` | vm-core | reservation, payment |
| `Caddyfile` | vm-edge | `/api/*` → gateway, 나머지는 프론트 정적 파일 |
| `postgres/schemas.sh` | vm-data | 빈 DB에 서비스 테이블 생성 |
| `prometheus/prometheus.yml` | vm-data | 서버용 수집 설정(`environment: dev`) |
| `env/<서버>.env.example` | 각 서버 | 그 서버의 `/opt/ticket/.env` 틀. 그 서버에 필요한 값만 있다 |
| `build-push.sh` | 노트북 | 이미지 9개를 amd64로 빌드해 ECR에 올림 |

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

## 주의

- 비밀값은 서버마다 그 서버 몫만 둔다. vm-core에는 DB 관리자 비밀번호와 JWT·입장 토큰 키가 없으므로, vm-core가 뚫려도 토큰을 위조하거나 다른 서비스의 DB에 들어갈 수 없다.
- `.env`를 권한 600으로 막아도 서버에서 docker를 쓸 수 있는 사람은 `docker inspect`로 컨테이너에 넘긴 값을 볼 수 있다. 서버 접속(SSM) 권한은 필요한 사람에게만 준다.
- 비밀값을 채팅·슬랙·이슈에 붙여 넣지 않는다.
- `docker compose down -v`를 쓰지 않는다. DB·Kafka·Redis 볼륨이 지워진다.
- 테이블 생성(`postgres/schemas.sh`)은 빈 볼륨에서 한 번만 돈다. 그 뒤 스키마가 바뀌면 `backend/sql/<서비스>/migrations/`의 SQL을 해당 서비스 계정으로 직접 적용하고 앱을 다시 띄운다.
- `backend/config-repo` 설정은 config-server 이미지 안에 있다. 설정만 바꿔도 이미지를 다시 올리고 vm-edge·vm-core 앱을 다시 띄운다.
- 앱은 `restart: unless-stopped`다. 서버를 끄고 켜면 Docker가 다시 띄우고, 다른 서버가 늦게 떠도 config-server에 닿을 때까지 재시작하며 맞춰진다.
