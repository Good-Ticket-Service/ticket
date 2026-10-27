# 공통 규약

모든 서비스의 API가 따르는 규칙이다. 코드에 이미 있는 것은 그 위치를 적었다. 바꾸려면 계약 변경 흐름을 따른다.

## 경로

| 구분 | 경로 | Gateway | 응답 |
|---|---|---|---|
| 외부 API | `/api/**` (버전 `v1`을 붙이지 않는다, 2026-10-27 팀 결정) | 노출. 서비스별 라우트는 `backend/config-repo/gateway-service.yml` | 성공은 `ApiResponse`, 실패는 `ErrorResponse` |
| 내부 API | `/internal/**` | 외부 접근을 404로 막는다 | 본문을 감싸지 않는다. 실패는 `ErrorResponse` |

새 외부 경로를 만들면 Gateway 라우트에 들어가는지 확인한다. 목표 라우트는 `/api/auth/**`, `/api/users/**`, `/api/events/**`, `/api/reservations/**`, `/api/payments/**`다. 지금 `gateway-service.yml`과 회원 API 구현은 아직 `/api/v1/**`이고, 담당자가 후속 작업에서 바꾼다. 도메인 `api.md`의 `/api/v1` 경로도 각 담당이 구현할 때 `/api`로 맞춘다.

## 사용자 헤더

Gateway가 JWT를 검증한 뒤 주입한다. 이름은 `common-core`의 `UserHeaders`에 있다.

| 헤더 | 값 |
|---|---|
| `X-User-Id` | 회원 ID |
| `X-Username` | 사용자 이름 |
| `X-User-Role` | 역할 |

컨트롤러에서는 `@CurrentUser UserContext`로 받고, 역할 제한은 `@RequireRole`로 한다. Feign 호출 시 `common`이 헤더를 전달한다. 요청 본문으로 사용자 ID를 받지 않는다.

## 응답 형식

성공 (`ApiResponse`, 외부 API만):

```json
{ "status": 200, "message": "SUCCESS", "data": { } }
```

실패 (`ErrorResponse`, 외부·내부 공통):

```json
{
  "status": 409,
  "code": "PAYMENT_ORDER_CONFLICT",
  "message": "같은 주문 번호로 다른 내용의 요청이 왔다",
  "path": "/internal/payments",
  "timestamp": "2026-10-27T10:00:00",
  "errors": [ { "field": "amount", "reason": "0보다 커야 한다" } ]
}
```

- 오류 코드는 서비스별 enum이 `ErrorCode`를 구현한다. 공통 코드(`VALIDATION_ERROR` 등)는 `CommonErrorCode`에 있고 다시 정의하지 않는다.
- 업무 결과(승인·거절처럼 정상 처리된 결과)를 4xx로 알릴지 본문 상태값으로 알릴지는 API마다 계약에 적는다.

## 값 형식

| 항목 | 규칙 |
|---|---|
| 시각 | ISO 8601. 오프셋 포함 여부는 아래 "합의가 필요한 차이"를 본다. DB 표현은 도메인별 `tables.md`를 따른다 |
| 금액 | 원 단위 정수. 통화는 KRW 고정 |
| 회원 ID | 협의 필요. 결제 초안은 UUID로 적었다 |
| 열거값 | 대문자 스네이크 문자열 (`IN_PROGRESS`) |

## 합의가 필요한 차이

Notion에서 옮긴 도메인 명세끼리 공통 규약이 다르다. 합의 전까지 각 도메인 문서는 Notion 원문을 그대로 두었다.

| 항목 | 이 문서·회원·예매·쿠폰·결제 | 공연·좌석 초안 |
|---|---|---|
| 외부 경로 | 해결: 모두 `/api/**`로 통일한다 (2026-10-27). 이 문서의 기준을 공연·좌석 초안 쪽으로 맞췄다 | `/api/**` (예: `/api/seller/performances`, `/api/schedules/{id}/holds`) |
| 성공 응답 | `ApiResponse`로 감싼다 | 래퍼 없이 DTO를 직접 반환 |
| 실패 응답 | `ErrorResponse` (`status`, `code`, `message`, `path`, `timestamp`, `errors`) | `{code, message, traceId, fieldErrors}` |
| BIGINT ID | 숫자 | 문자열 |
| 시각 | 예매·쿠폰은 오프셋 없는 KST | `+09:00` 포함 |
| 선점 상태·만료 오류 | 예매: `HOLD`, 409 `HOLD_EXPIRED` | 공연 5.12: `HELD`, 410 `HOLD_EXPIRED` |

`/api/**` 경로는 지금 Gateway 라우트에 없어서, 라우트를 바꾸기 전에는 외부에서 호출되지 않는다. 라우트 변경은 gateway 담당 후속 작업이다.

## 멱등성과 재시도

- 재시도(`IdempotentRetryConfig`)는 GET과 계약에 멱등하다고 적은 API에만 붙인다.
- 멱등한 쓰기 API는 계약에 멱등 키(예: `orderId`)와 같은 키 재요청 시 동작을 적는다.
- 타임아웃은 쓰는 쪽이 정하되, 제공하는 쪽의 최악 응답 시간을 계약에 적는다.
