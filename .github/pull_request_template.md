## 변경 이유

## 주요 변경

## 검증

- [ ] 대상 브랜치 확인: 일반 작업은 `develop`, 릴리스는 `main`
- [ ] 커밋 목록·메시지 정리 (Rebase and merge)
- [ ] `cd backend && ./gradlew build` 통과
- [ ] Compose·Gateway 라우트·config-repo를 바꿨다면 `docker compose up` 후 `script/smoke.sh` 통과
- [ ] Entity를 바꿨다면 `backend/sql/{서비스}/schema.sql`도 함께 수정
- [ ] API·DB 스키마·Kafka 메시지 변경 시 영향을 받는 담당자 리뷰 요청
- [ ] 비밀번호·토큰·키 미포함

## 연결 이슈

Closes #
