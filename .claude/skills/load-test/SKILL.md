---
name: load-test
description: (틀) k6 부하 테스트를 실행하고 docs/perf/ 양식으로 결과를 기록한다.
disable-model-invocation: true
---

# 부하 테스트 (틀)

아직 틀만 있다. 트러블슈팅 기간(11/9~)에 첫 측정을 하면서 실제 절차로 채운다.

## 채울 단계

1. 측정 환경 기록: 커밋, 장비, 컨테이너 자원 제한, 인스턴스 수, 장애 주입 설정(fake PG 등)
2. 시나리오 선택과 k6 실행: 스크립트 위치, 가상 사용자 수, 지속 시간
3. 결과 수집: 처리량(RPS), 평균·p95 응답 시간, 성공률, 오류 종류. Grafana 대시보드 캡처
4. `docs/perf/YYYY-MM-DD-{도메인}-{시나리오}.md`에 `docs/perf/template.md` 양식으로 기록하고 `docs/perf/README.md` 표에 한 줄 추가
5. 개선 전후 비교라면 같은 환경·시나리오인지 확인
