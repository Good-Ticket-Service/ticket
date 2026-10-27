# SeatFacade (예매 → 좌석)

- 상태: 합의 (#34). 좌석 담당 구현 전
- 제공: 좌석 (`reservation-service` seat 모듈, 변정민)
- 소비: 예매 (`reservation-service` booking 모듈, 지한얼)
- 짝 계약: [BookingFacade](booking-facade.md) (좌석 → 예매)
- 근거: 좌석 담당 「seat ↔ booking 모듈 Facade 계약 제안 (v1)」, #34

booking이 예약 상태를 바꿀 때 좌석 상태를 같은 트랜잭션에서 맞추는 창구다. booking은 `schedule_seat`을 직접 조회·수정하지 않는다.

## 공통

- **패키지:** `com.sparta.ticket.reservation.seat.application.facade`. booking은 seat의 이 패키지만 쓸 수 있다(ArchUnit `BOOKING_USES_SEAT_ONLY_THROUGH_FACADE`). booking 쪽에서는 `booking.application.port.SeatPort`가 이 인터페이스를 감싸고, 어댑터(`booking.infrastructure.seat`)가 부른다.
- **트랜잭션·잠금·시각:** [BookingFacade](booking-facade.md#공통)와 같다. 모든 메서드 `MANDATORY`, 잠금 순서 좌석 → 예약, 시각은 함께 쓰는 `Clock`.
- **0행:** 바뀐 행 수를 돌려준다. 요청 좌석 수와 다르면 booking이 "이미 다른 경로로 끝난 예약"으로 판단한다.

## 메서드

```java
public interface SeatFacade {

    /** 좌석 행을 id 오름차순으로 잠근다. 잠금 순서: 좌석 → 예약. */
    List<SeatLock> lockSeats(Collection<Long> scheduleSeatIds);

    /** 이 예약이 잡은 HOLD 좌석만 SOLD로 바꾼다. 바뀐 행 수를 돌려준다. */
    int sell(Collection<Long> scheduleSeatIds, Long reservationId);

    /** 이 예약이 잡은 좌석만 AVAILABLE로 돌린다. 바뀐 행 수를 돌려준다. */
    int release(Collection<Long> scheduleSeatIds, Long reservationId);
}

public record SeatLock(Long scheduleSeatId, SeatState state, Long reservationId, LocalDateTime holdExpiresAt) {}

public enum SeatState { AVAILABLE, HOLD, SOLD }   // seat의 domain enum을 내보이지 않는 Facade 전용 타입
```

| 메서드 | booking이 부르는 때 | 동작 |
|---|---|---|
| `lockSeats` | 확정·만료·취소 | id 오름차순 `SELECT ... FOR UPDATE`. 좌석 상태·현재 예약 id·만료 시각을 돌려준다 |
| `sell` | 확정 | `status = HOLD AND reservation_id = :reservationId` → SOLD, `hold_expires_at = NULL` |
| `release` | 만료·취소 | `reservation_id = :reservationId` → AVAILABLE, `reservation_id = NULL`, `hold_expires_at = NULL` |

- `reservation_id` 일치 조건 덕분에 이미 다른 사람에게 넘어간 좌석을 만료 스케줄러나 늦은 결제 결과가 건드리지 못한다.
- 가격은 선점 때 `BookingFacade.hold`로 티켓에 남으므로 `priceSnapshot`은 두지 않는다.
- `SeatState`의 값은 좌석 담당이 seat domain에 맞춰 확정한다.

## booking이 부르는 순서

| 흐름 | 순서 |
|---|---|
| 결제 성공 → 확정 | `lockSeats` → 결과로 좌석이 이 예약의 HOLD인지 **먼저** 확인 → 예약 `PAYMENT_IN_PROGRESS → CONFIRMED`(조건부) → `sell`. 좌석이 이 예약 것이 아니면 확정하지 않고 CANCELING + `CONFIRM_FAILED` 환불 요청 |
| 선점 만료 스케줄러 | 후보 조회(잠금 없음) → 예약마다 `lockSeats` → `HOLD → EXPIRED`(조건부) → 1건이면 `release` |
| 결제 실패, 시간 지남 | `lockSeats` → `PAYMENT_IN_PROGRESS → EXPIRED` → `release` |
| 예매 취소 | `CONFIRMED → CANCELING` 커밋 → 환불 요청 접수 → `lockSeats` → `CANCELING → CANCELED` → `release` |

## 변경 이력

| 날짜 | 이슈·PR | 내용 |
|---|---|---|
| 2026-10-27 | #34 | 초안. 좌석 담당 제안 v1의 SeatFacade를 옮기고, 상태를 Facade 전용 enum으로 바꿈 |
