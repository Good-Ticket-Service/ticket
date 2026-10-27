# 문서

구현에 필요한 문서를 도메인별로 모은다. 사람과 AI가 같은 문서를 기준으로 구현한다.

## 구성

| 경로 | 내용 | 바꾸는 방법 |
|---|---|---|
| [shared/](shared/README.md) | 서비스·모듈 간 계약(내부 API, 모듈 인터페이스)과 공통 규약 | 계약 변경 흐름을 따른다 |
| `{도메인}/` | 도메인 안의 외부 API, 테이블, 구현 계획 | 담당자가 구현 PR과 함께 고친다 |
| [project/](project/decisions.md) | 팀 결정사항, 용어집·상태, [시스템 구성도](project/architecture.md), [인프라 구성도](project/infra.md) | 팀 합의 후 고친다 |
| [perf/](perf/README.md) | 부하 테스트 기록 | 측정할 때마다 추가한다 |
| [_template/](_template/) | 계약·API·테이블·계획 문서 양식 | 팀 합의 후 고친다 |

다른 담당자가 보고 코드를 짜는 내용은 `shared/`에, 내 도메인 안에서만 쓰는 내용은 도메인 폴더에 둔다. 같은 내용을 두 곳에 쓰지 않고 링크한다.

## 도메인

| 도메인 | 서비스·모듈 | 담당 | 문서 |
|---|---|---|---|
| 회원 | `user-service` | 김태현 | [api](user/api.md) · [tables](user/tables.md) |
| 공연 | `event-service` | 박의연 | [api](event/api.md) · [tables](event/tables.md) |
| 좌석 | `reservation-service` seat 모듈 | 변정민 | [api](seat/api.md) · [tables](seat/tables.md) |
| 예매 | `reservation-service` booking 모듈 | 지한얼 | [api](reservation/api.md) · [tables](reservation/tables.md) · [설계](reservation/orchestration.md) |
| 대기열 | `reservation-service` queue 모듈 | 김태현 | [api](queue/api.md) |
| 결제 | `payment-service`, `fake-pg` | 강현민 | [plan](payment/plan.md) · [api](payment/api.md) · [tables](payment/tables.md) |
| 쿠폰 | `reservation-service` coupon 모듈 | 성윤범 | [api](coupon/api.md) · [tables](coupon/tables.md) |
| 티켓 | 미정 | | |

결제 외 도메인 문서는 Notion(2026-10-26 기준)에서 옮긴 것이다.

도메인 폴더에는 아래 파일을 둔다. 없는 파일은 [_template/](_template/)을 복사해 만든다. 예시는 [payment/](payment/)다.

| 파일 | 내용 |
|---|---|
| `api.md` | 외부 API(`/api/v1/**`). 내부 API는 `shared/internal-api/{도메인}.md`에 두고 링크한다 |
| `tables.md` | ERD(mermaid)와 테이블 명세 |
| `plan.md` | 구현 계획과 작업 목록 |

## 도메인 문서 채우는 법

1. 자기 도메인 문서를 고치거나 양식으로 새로 만든다.
2. 문서 상단의 `상태`를 `작성 중`이나 `작성 완료`로 바꾼다.
3. 자기 도메인 문서만 담아 PR을 올린다.
4. 다른 서비스가 부르는 내부 API를 바꾸거나 새로 만들 때는 [계약 변경 흐름](shared/README.md#계약-변경-흐름)의 이슈부터 시작한다.
