# 공연 내부 API

- 제공: 공연 (`event-service`)
- 소비: 예매 (`reservation-service`)
- 설계 근거: Notion "도메인별 API 명세"의 공연 항목 (2026-10-26 기준). 외부 API는 [event/api.md](../../event/api.md)에 있다.

## 공통

- Gateway에 노출하지 않는다. 호출에는 서비스 인증이 필요하다.
- 응답 본문을 `ApiResponse`로 감싸지 않는다. 오류는 Notion 원본의 공통 오류 형식(`code`, `message`, `traceId`, `fieldErrors`)을 따른다. 프로젝트 `ErrorResponse`와 맞추는 일은 담당자 검토에서 정한다.
- 업무상 거절은 HTTP 상태가 아니라 본문 `allowed=false`와 `reasonCode`로 알린다. 리소스 미존재·인증·권한·요청 형식 오류만 일반 HTTP 오류를 쓴다.
- ID는 JSON string, 버전·원화 가격은 integer, 시각은 `+09:00` ISO 8601이다.

---

## POST /internal/schedules/{id}/sale-validations — 회차 판매 정책·기본 가격 검증

- 상태: 합의 (Notion, 2026-10-26)
- 멱등성: 조회성 검증이다. 상태를 바꾸지 않으므로 같은 요청을 다시 보내도 된다. 결과는 호출 시점의 판매 정책에 따라 달라질 수 있다.
- 타임아웃: 미정
- 재시도: 미정. 상태를 바꾸지 않는 검증이라 재시도 대상 후보다.

> Note: Notion 상태는 "시작 전"이다. 필수 헤더는 "서비스 인증"이다.

예매 서비스가 신규 선점 시 판매 정책과 기본 가격을 검증하려고 부른다. 좌석 점유 여부는 판단하지 않는다.

요청

Path

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| id | string | Y | 공연 회차 ID. |

Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| configVersion | integer | Y | 예매 서비스가 보유한 좌석 구성 버전. 1 이상. |
| venueSeatIds | string[] | Y | 1개 이상, 중복 불가. 공연장 물리 좌석 ID 목록. |

```json
{
  "configVersion": 1,
  "venueSeatIds": [
    "1001"
  ]
}
```

응답 200

| 필드 | 타입 | 설명 |
|---|---|---|
| allowed | boolean | 신규 선점 판매 정책 통과 여부. 좌석 확보 보장 아님. |
| reasonCode | string\|null | 허용 시 null. 거절 시 원인 코드(아래 표). |
| scheduleId | string | 대상 회차 ID. |
| configVersion | integer | 서버 최신 구성 버전. |
| policyVersion | integer | 부모 공연 version. |
| performanceTitle | string | 공연명 스냅샷. |
| scheduleStartAt | datetime | 공연 시각 스냅샷. |
| validatedAt | datetime | 서버 검증 시각. |
| seats | ValidationSeat[] | 허용 시 요청 좌석 가격. 거절 시 빈 배열. |
| seats[].venueSeatId | string | 검증한 물리 좌석 ID. |
| seats[].venueSeatGradeId | string | 공연장 고정 등급 ID. |
| seats[].gradeName | string | 공연장 원본 등급명. |
| seats[].basePrice | integer | 공연 공통 가격. 0 이상. |

허용 예시

```json
{
  "allowed": true,
  "reasonCode": null,
  "scheduleId": "201",
  "configVersion": 1,
  "policyVersion": 3,
  "performanceTitle": "공연 A 서울",
  "scheduleStartAt": "2026-12-01T19:00:00+09:00",
  "validatedAt": "2026-11-01T14:01:00+09:00",
  "seats": [
    {
      "venueSeatId": "1001",
      "venueSeatGradeId": "101",
      "gradeName": "VIP",
      "basePrice": 150000
    }
  ]
}
```

reasonCode (allowed=false일 때)

| reasonCode | 뜻 |
|---|---|
| SCHEDULE_CANCELED | 회차가 취소됨 |
| SALES_SUSPENDED | 판매 일시 중지 |
| SEAT_SETUP_NOT_READY | 회차 좌석 준비가 끝나지 않음 |
| BEFORE_OPEN | 예매 오픈 전 |
| SALES_CLOSED | 예매 마감 후 |
| SEAT_NOT_SELLABLE | 판매 제외 좌석 |
| SEAT_CONFIG_VERSION_MISMATCH | 요청 configVersion이 최신 구성과 다름 |

처리·검증 규칙

- 거절도 정상 검증 결과이면 HTTP 200, `allowed=false`다. 이때 reasonCode는 필수이고 `seats=[]`이며 가격 정보를 주지 않는다.
- 가격은 서버 원본으로 결정하며 좌석 점유 여부는 판단하지 않는다.
- policyVersion은 부모 performance.version이다.
- venueSeatGradeId와 gradeName은 venue_seat의 고정 등급을 기준으로 반환한다.
- basePrice는 해당 공연의 performance_grade_price에서 가져오며 같은 공연의 모든 회차에 동일하다.
- 요청 좌석이 공연장 소속·판매 대상인지 확인하고, 예매 측 스냅샷이 최신 configVersion과 일치해야 한다.
- 좌석 확보는 예매 측 조건부 UPDATE로 처리한다.
- 검증 직후 회차가 취소되는 경쟁은 이 API만으로 막을 수 없다. 취소 버전 전파·최종 확정·보상 규칙이 별도로 필요하다.
- 알 수 없는 필드, 타입 불일치, 필수값 누락은 400이다.

오류

| 상태 | 코드 | 때 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드·타입·필수값 오류, venueSeatIds 비었거나 중복 |
| 401 / 403 | `UNAUTHENTICATED` / `FORBIDDEN` | 서비스 인증 실패 |
| 404 | `SCHEDULE_NOT_FOUND` | 회차 없음·삭제됨 |

---

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 합의 내용 반영. Notion 공연 API 명세에서 옮김 |
