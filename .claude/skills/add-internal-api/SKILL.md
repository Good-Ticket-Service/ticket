---
name: add-internal-api
description: (틀) 합의된 docs/shared 계약대로 서비스 간 내부 API의 제공 쪽 컨트롤러와 소비 쪽 Feign 클라이언트를 만든다.
disable-model-invocation: true
---

# 내부 API 추가 (틀)

아직 틀만 있다. 첫 내부 API를 구현하면서 실제 절차로 채운다.

## 전제

- 계약이 `docs/shared/internal-api/{제공 도메인}.md`에 있고 상태가 `합의`다. `초안`이면 멈추고 계약 변경 흐름(`docs/shared/README.md`)을 안내한다.

## 채울 단계

1. 제공 쪽: `/internal/**` 컨트롤러, 요청·응답 DTO(계약 필드 그대로), 오류 코드 enum, 컨트롤러 테스트
2. 소비 쪽: `infrastructure/client`의 `@FeignClient`, 포트 인터페이스와 어댑터, 계약에 멱등이라고 적힌 API만 `IdempotentRetryConfig`
3. 소비 쪽 테스트: 포트의 가짜 구현
4. 계약 상태를 `구현됨 (#PR)`으로 바꾸고 변경 이력을 남긴다
