# 도메인 용어와 상태

여러 도메인이 함께 쓰는 용어, 상태 값, 불변식을 모은다. 용어와 상태 값은 SA 문서와 같게 쓴다. 한 도메인 안의 상세 전이는 그 도메인의 `plan.md`에 두고 여기서는 링크한다.

## 용어집

| 용어 | 영문·코드 이름 | 뜻 | 소유 도메인 |
|---|---|---|---|
| | | | |

## 상태 값

| 대상 | 상태 | 소유 도메인 | 상세 |
|---|---|---|---|
| 결제 | READY, IN_PROGRESS, DONE, FAILED, CANCELED | 결제 | [payment/plan.md](../payment/plan.md) 3절 |
| 환불 | WAITING, REQUESTED, DONE, FAILED, SKIPPED | 결제 | [payment/plan.md](../payment/plan.md) 3절 |

각 도메인 담당자가 자기 상태를 한 줄씩 더한다.

## 불변식

여러 도메인에 걸쳐 항상 지켜야 하는 조건이다.

| # | 불변식 | 지키는 곳 |
|---|---|---|
| I1 | 한 예매에 살아 있는(READY·IN_PROGRESS·DONE) 결제는 하나뿐이다 | `payments` 부분 유니크 인덱스 |

## 도메인 이벤트

Phase 2(Kafka)에서 채운다. 메시지 스키마는 그때 `shared/events/`에 둔다.
