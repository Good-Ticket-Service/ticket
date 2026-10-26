# 문서

구현에 필요한 문서를 도메인별로 모은다. 사람과 AI가 같은 문서를 기준으로 구현한다.

## 구성

| 경로 | 내용 | 바꾸는 방법 |
|---|---|---|
| [shared/](shared/README.md) | 서비스·모듈 간 계약 (내부 API, 모듈 인터페이스, 이벤트), 공통 규약 | 계약 변경 흐름을 따른다 |
| `{도메인}/` | 도메인 안의 외부 API, 테이블, 구현 계획 | 담당자가 구현 PR과 함께 고친다 |
| [project/](project/decisions.md) | 팀 결정사항, 용어집·상태 전이 | 팀 합의 후 고친다 |
| [adr/](adr/README.md) | 기술 결정 기록 | 결정할 때 한 건씩 추가한다 |
| [perf/](perf/README.md) | 부하 테스트 기록 | 측정할 때마다 추가한다 |
| [_template/](_template/) | 계약·API·테이블·계획 문서 양식 | 팀 합의 후 고친다 |

다른 담당자가 보고 코드를 짜는 내용은 `shared/`에, 내 도메인 안에서만 쓰는 내용은 도메인 폴더에 둔다. 같은 내용을 두 곳에 쓰지 않고 링크한다.

## 도메인

| 도메인 | 폴더 | 서비스·모듈 | 담당 |
|---|---|---|---|
| 회원 | [user/](user/) | `user-service` | |
| 공연 | [event/](event/) | `event-service` | |
| 좌석 | [seat/](seat/) | `reservation-service` seat 모듈 | 변정민 |
| 예매 | [reservation/](reservation/) | `reservation-service` booking 모듈 | |
| 대기열 | [queue/](queue/) | `reservation-service` queue 모듈 | 김태현 |
| 결제 | [payment/](payment/) | `payment-service`, `fake-pg` | 강현민 |
| 쿠폰 | [coupon/](coupon/) | `payment-service` coupon 모듈 | |
| 티켓 | [ticket/](ticket/) | 미정 | |

빈 담당 칸은 각 담당자가 채운다.

도메인 폴더의 기본 파일은 셋이다. 필요하면 문서를 더 두고 `plan.md`에서 링크한다.

| 파일 | 내용 |
|---|---|
| `api.md` | 외부 API(`/api/v1/**`). 내부 API는 `shared/internal-api/{도메인}.md`에 두고 링크한다 |
| `tables.md` | ERD(mermaid)와 테이블 명세 |
| `plan.md` | 구현 계획과 작업 목록 |

## 도메인 문서 채우는 법

1. [_template/](_template/)의 양식을 보고 자기 도메인 폴더의 `api.md`, `tables.md`, `plan.md`를 채운다. 예시는 [payment/](payment/)다.
2. 문서 상단의 `상태`를 `작성 중`이나 `작성 완료`로 바꾼다.
3. 자기 도메인 문서만 담아 PR을 올린다.
4. 다른 서비스가 부르는 내부 API는 바로 `shared/`에 쓰지 않고 [계약 변경 흐름](shared/README.md#계약-변경-흐름)의 이슈부터 시작한다.
