# 좌석 내부 API

- 제공: 좌석 (`reservation-service` seat 모듈, 변정민)
- 소비: 공연 (`event-service`)
- 출처: Notion "도메인별 API 명세"의 좌석 내부 항목과 "공연 API 공통 계약" 5.12 (2026-10-26 기준)

---

## 회차 좌석 준비 (생성·재생성)

- 상태: 합의 (Notion, 2026-10-26)
- 통신 방식: 협의 필요. 이벤트안은 공연 서비스가 발행한 `ScheduleSeatSetupRequested`를 좌석이 소비한다. HTTP안은 `PUT`(Notion 메서드 값)이다. 두 방식을 함께 쓰지 않는다.
- 권한·허용 호출자: 공연 서비스 (서비스 인증, Gateway 외부 노출 안 함)
- 성공 HTTP: 200 (HTTP안)

회차 등록·DRAFT 구성 변경 시 공연 서비스가 보낸 좌석 구성으로 `schedule_seat`을 만들거나 교체한다.

요청

```json
{
  "requestId": "...",
  "configVersion": 1,
  "seats": [
    { "venueSeatId": "1001", "venueSeatGradeId": "101", "gradeName": "VIP", "basePrice": 150000, "isSellable": true }
  ]
}
```

`basePrice`는 판매 제외 좌석이면 null일 수 있다.

응답

```json
{ "scheduleId": "201", "configVersion": 1, "seatCount": 1, "sellableCount": 1 }
```

처리 규칙 (공연 공통 계약 5.12 "좌석 준비 계약")

- scheduleId, venueSeatId, venueSeatGradeId, gradeName, basePrice, isSellable, configVersion으로 행을 생성한다.
- requestId·eventId는 예매 측 소비 이력 저장소에서 관리하는 제안이며 좌석마다 반복 저장하지 않는다.
- 동일 이벤트 재처리로 좌석을 중복 생성하지 않는다.
- 모든 좌석의 구성 검증·저장이 완료되면 `ScheduleSeatSetupCompleted`를 발행한다. 성공 후 공연 서비스가 회차를 READY로 변경한다.
- 이벤트 대신 HTTP를 선택하면 동등한 준비·결과 조회 계약을 별도로 합의한다.
- 재생성할 때 config_version을 확인하고, 선점·판매 이력이 있는 좌석을 초기 상태로 덮어쓰지 않는다([seat/tables.md](../../seat/tables.md)).

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-26 | #3 | 합의 내용 반영. Notion 좌석 API 명세에서 옮김 |
