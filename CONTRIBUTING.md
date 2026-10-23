# 협업 규칙

코드 구조·설정·스키마 규칙은 [README](README.md)와 [CLAUDE.md](CLAUDE.md)를 따른다. 이 문서는 브랜치·커밋·PR·리뷰 규칙만 다룬다.

## 브랜치

- `develop`: 개발 통합 브랜치. 작업 브랜치는 최신 `develop`에서 만들고 PR도 `develop`으로 보낸다.
- `main`: 릴리스 기준 브랜치. 검증된 `develop`을 PR로 반영한다.
- `develop`과 `main`에는 직접 push·force push하지 않는다.
- 작업 브랜치 이름: `<type>/<scope>/<이슈 번호>-<짧은 설명>`
  - 예: `feat/reservation/12-seat-hold`, `fix/gateway/23-jwt-expiry`
- scope: `user`, `event`, `reservation`, `payment`, `gateway`, `config`, `eureka`, `common`, `infra`, `project`
- 한 브랜치와 PR에는 한 가지 목적만 담는다. 머지된 작업 브랜치는 지운다.
- 선행 PR에 의존하는 작업만 그 작업 브랜치를 임시 base로 쓴다. 선행 PR이 머지되면 최신 `develop` 위로 rebase하고 PR의 base를 `develop`으로 바꾼 뒤 CI와 승인을 다시 확인한다.

## 이슈

- 작업은 이슈부터 만든다. 이슈 템플릿(기능·버그·작업)의 범위와 완료 조건을 채운다.
- 이슈 하나가 너무 크면(1~2일 안에 끝나지 않으면) 나눈다.

## 커밋과 PR 제목

```text
<type>(<scope>): <변경 내용>

feat(reservation): 좌석 선점 API 추가
fix(gateway): 만료 토큰으로 공개 경로 호출 시 401 수정
chore(infra): Kafka 컨테이너 추가
```

- type: `feat`, `fix`, `refactor`, `docs`, `test`, `style`, `build`, `ci`, `chore`
- 설명은 한국어로 짧게 쓴다. 본문이 필요하면 무엇을 왜 바꿨는지와 검증 방법을 적는다.
- 관계없는 포맷 수정이나 생성물(빌드 결과, 로그)을 함께 커밋하지 않는다.
- 커밋은 의미 있는 단위로 남긴다. 임시 커밋은 리뷰 요청 전에 정리한다.
- 공유한 브랜치의 이력을 다시 써야 하면 함께 작업하는 사람과 먼저 맞추고 `--force-with-lease`를 쓴다.

## PR과 리뷰

1. PR 템플릿의 변경 이유, 주요 변경, 검증, 연결 이슈를 채운다. 연결 이슈는 `Closes #번호`로 적는다.
2. 작성자 외 팀원 1명 이상의 승인을 받는다. API·DB 스키마·Kafka 메시지·공통 모듈을 바꾸면 영향을 받는 담당자에게 리뷰를 요청한다.
3. 리뷰 후 커밋이 바뀌면 최종 커밋 기준으로 CI와 승인을 다시 받는다.
4. CI(`build-and-smoke`) 통과, 승인, 대화 해결을 확인하고 **Rebase and merge**로 머지한다. 머지 커밋은 만들지 않는다.

## CI

`.github/workflows/ci.yml`

| 잡 | 하는 일 |
| --- | --- |
| `quality` | `cd backend && ./gradlew build` (컴파일·단위 테스트·Testcontainers 통합 테스트) |
| `smoke` | `.env.example`로 `.env`를 만들고 `docker compose up` → `script/smoke.sh` (Gateway 기동, Eureka 등록, 무인증 401, MASTER 로그인 후 내 정보 조회) |
| `build-and-smoke` | 두 잡이 모두 성공해야 성공. 브랜치 보호 규칙의 필수 검사로 쓴다 |

검사 보고서는 실행 화면의 `test-reports` 아티팩트에서 7일간 내려받을 수 있다. CI는 코드를 고치지 않는다.

로컬에서 같은 검사를 돌리려면:

```bash
cd backend && ./gradlew build
docker compose up -d --build && script/smoke.sh
```

## 비밀값

- `.env`, 토큰, 실제 비밀번호, 개인 키는 커밋하지 않는다. 변수 이름과 로컬 전용 값만 `.env.example`에 둔다.
