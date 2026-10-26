---
paths:
  - "backend/**/presentation/**"
  - "backend/config-repo/gateway-service.yml"
---

# API 구현 규칙

- 외부 API는 `/api/v1/**` 아래에 두고 성공 응답은 `ApiResponse`로 감싼다. 실패는 `ErrorResponse`다.
- 새 외부 경로는 `backend/config-repo/gateway-service.yml` 라우트에 들어가는지 확인한다. 라우트에 없으면 외부에서 호출되지 않는다.
- 서비스 간 API는 `/internal/**`에 두고 응답을 감싸지 않는다. Gateway가 외부 접근을 404로 막는다.
- 현재 사용자는 `@CurrentUser UserContext`로 받는다. 요청 본문으로 사용자 ID를 받지 않는다.
- 역할 제한은 `@RequireRole`로 하고, 소유자 검사는 application 계층에서 한다.
- 오류 코드는 서비스별 enum이 `ErrorCode`를 구현한다. `CommonErrorCode`에 있는 이름을 다시 정의하지 않는다.
- 컨트롤러는 요청 검증과 응답 변환만 하고 업무 규칙은 application 계층에 둔다.
