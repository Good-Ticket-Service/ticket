---
paths:
  - "backend/*-service/**"
  - "backend/sql/**"
---

# 도메인 문서 먼저 읽기

도메인 기능을 구현하거나 고치기 전에 그 도메인의 문서를 읽는다. 도메인·서비스·담당 표는 `docs/README.md`에 있다.

| 서비스·모듈 | 문서 |
|---|---|
| `user-service` | `docs/user/` |
| `performance-service` | `docs/performance/` |
| `reservation-service` seat 모듈 | `docs/seat/` |
| `reservation-service` booking 모듈 | `docs/reservation/` |
| `reservation-service` queue 모듈 | `docs/queue/` |
| `payment-service` payment 모듈, `fake-pg` | `docs/payment/` |
| `reservation-service` coupon 모듈 | `docs/coupon/` |

- `api.md`의 경로·요청·응답·오류 코드와 다르게 구현하지 않는다. 문서가 틀렸다고 판단되면 구현 전에 알린다.
- 엔티티·`schema.sql`을 바꾸면 `tables.md`도 같은 PR에서 고친다.
- 다른 도메인이 제공하는 기능을 쓰면 `docs/shared/`의 해당 계약을 읽는다.
