# 시스템 아키텍처

기준: develop 7a48c5e, 저장소의 Notion 시스템 구성도(2026-10-26) 및 공연 #54·결제 D16~D18·모듈 합의 #34. [Notion 원본](https://app.notion.com/p/404b3ff41d3980e89bb9e17ce9f7106f)은 실시간 대조하지 못했다. 아래는 구현 완료 보고가 아닌 설계 기준이다. API/DB/공통 규약/배치는 다른 4개 문서에만 정의한다.

## 책임

| 서비스·모듈 | 소유·책임 | 담당 |
|---|---|---|
| user-service | 회원·판매자 가입/인증/역할·탈퇴 | 김태현 |
| performance-service | 공연장·고정 좌석 등급/배치, 공연 가격·공개, 회차·판매 정책 | 박의연 |
| reservation-service / queue | 대기열·입장 토큰(Redis) | 김태현 |
| reservation-service / seat | 회차 좌석 스냅샷·선점/반환·잔여석 | 변정민 |
| reservation-service / booking | 예매/티켓·만료·결제 조정·취소·보상 | 지한얼 |
| reservation-service / coupon | 쿠폰 발급/점유/사용/복원(이전 계약 초안 #27) | 성윤범 |
| payment-service | 결제/환불·PG 실행·복구·대사, 쿠폰 처리 없음 | 강현민 |
| fake-pg | 독립 PG 모의 거래·장애 재현 | 강현민 |

서비스별 DB 계정/스키마 격리, 서비스 간 물리 FK/직접 조회 금지. seat/booking/coupon은 같은 reservation DB의 로컬 트랜잭션을 공유한다. Common-core는 비웹 계약, common은 MVC 공통. Config/Eureka/Gateway는 공통 인프라다. 정산은 현행 확장 범위이며 수수료/취소 수수료/정산 배치 계약은 확정되지 않았다.

## 통신·단계

| 흐름 | MVP | 고도화 |
|---|---|---|
| 외부 → 서비스 | Gateway JWT/사용자 재확인 → Eureka lb | 동일, 경로 미적용은 infra 참조 |
| Gateway → user | WebClient 내부 회원 조회, 권한 캐시60초 | 즉시 권한 폐기 필요성은 별도 합의 |
| 공연 → seat 준비 | Feign 계획(#54), URI/응답 미합의 | 합의 후 Kafka Requested/Completed/Failed, HTTP와 중복 실행 금지 |
| seat → 공연 판매 검증 | 선점 트랜잭션 밖 내부 HTTP | 장애·정책 변경 경합은 별도 보상 |
| booking → payment 준비/승인/조회 | Feign 동기 | **준비·승인·조회 유지(D16)**. 인증값/승인 명령 Kafka 전송 안 함 |
| payment → PG | HTTP RestClient+JDK HTTP/1.1(D17); connect2초/read10초 | 동일. PG 교체는 PgClient 어댑터 |
| booking → payment 환불 | HTTP 인수 후 최초 PG 시도, 이후 DB 복구 | Kafka 환불/확정 실패 보상 명령 + 영속 인계/워커 비교 |
| payment → booking 결과 | 동기 응답 + 예약 스케줄러 폴링 | 확정 사실 이벤트+Outbox, 동기 승인 응답 유지 |
| 쿠폰 발급 | 동기201·조건부 수량 갱신 | Kafka 비동기 접수 대상/응답 의미 별도 합의 |

예매의 Feign read20초는 제안, Gateway 현행60초. PG RestClient는 공통 Feign의 사용자 헤더/도메인 오류 변환을 적용하지 않는다. 내부 타임아웃과 최종 상태는 동일 개념이 아니다.

## 공연·좌석 준비

- 물리 좌석/등급은 공연장 고정, 가격·제외 정책은 공연 단위로 모든 회차가 공유. 회차별 점유만 seat 소유. 공개 후 배치/가격/제외 불변, 표시 정보 수정 범위는 API 계약.
- DRAFT이며 삭제 회차 포함 등록 이력이 없을 때만 venue 변경. 가격/제외 초기화와 공연 버전 갱신을 한 트랜잭션에 수행.
- 공개 공연에도 새 회차 추가 가능. 부모 공연 잠금으로 공개/회차 구성 변경 직렬화. performance.version과 schedule.version은 독립, 회차 시간 변경으로 performance.version 증가 안 함.
- 회차 PENDING → 준비 성공 READY / 실패 FAILED. 재전송·자동 재시도는 같은 requestId. **#54 최신 기준: configVersion 변경 시에만 새 requestId**. 결과의 현재 requestId/configVersion 일치 확인. 이전 회의의 '확정 실패 후 무조건 새 requestId'보다 이 계약이 최신이다.
- 같은 버전 재처리는 좌석 중복 생성 없이 이번 요청에 맞는 결과 재현. 낮은 버전 무시, 높은 버전 교체는 기존 모든 좌석 AVAILABLE일 때만. 만료 미정리 HOLD는 AVAILABLE 아님. 회차 시간 변경은 좌석 스냅샷에 없으므로 재준비 불필요.
- 공개 조건에 READY를 강제하지 않지만 실제 판매는 READY 필요. 영구 준비 실패·미완성 공개 회차의 경보/재시도 운영 기준 미합의.
- 일시 준비 장애(연결/timeout/5xx)는 PENDING 유지·같은 requestId 자동 재시도, 최대 횟수 초과/4xx 검증 오류는 FAILED+안전한 오류 요약. 가격/제외 구성 변경 시 configVersion 증가·새 requestId로 재준비. 판매자 전용 재시도 API 없음.
- 이미 공개된 공연의 publication 재요청은 If-Match 불일치에도 현재 결과200·최초 publishedAt 보존, DRAFT 복귀 없음. 공연이 참조한 물리 배치는 변경 금지(VENUE_LAYOUT_IN_USE), 미삭제 좌석이 쓰는 등급 삭제 금지(VENUE_GRADE_IN_USE). 일괄 좌석 중복은 전체 롤백.
- 판매 검증은 신규 선점 정책/가격 확인이며 좌석 확보 보장 아님. 검증 직후 취소 경쟁은 전파/보상으로 처리. 판매 중지는 신규 선점 차단, 기존 유효 HOLD 결제는 원래 만료까지 허용.

## 선점·예매 로컬 원자성

- 선점10분, MVP 회원·회차당1장. seat가 공유 Clock(Asia/Seoul)으로 holdExpiresAt을 한 번 계산, 좌석/예매에 동일 저장. 결제 중 연장 안 함. 과거 DB now() 초안은 #34 공유 Clock으로 대체.
- 잠금 순서 **좌석 ID 오름차순 → 예약**. 선점·만료·확정·해제·취소 모두 동일. 모듈 facade 모든 메서드 MANDATORY. CouponUsage는 REQUIRED로 예매 트랜잭션 참여(초안).
- 매수 제한 사전 count + DB 부분 UNIQUE로 최종 방어. 유니크 위반은 전체 선점 롤백, 제약 이름이 맞을 때만 트랜잭션 밖409 변환. 본인 만료 HOLD는 옛 좌석 잠금→예약 EXPIRED→좌석 반환 뒤 재선점.
- 같은 회원/좌석의 만료 전 HOLD 재요청은 기존 예약200. 가격은 seat.base_price→ticket.price로 복사, 결제 totalPrice는 티켓 합. 클라이언트 가격 신뢰 금지.
- 만료 `HOLD AND expires<=:now`, 결제 시작 `HOLD AND expires>:now`. PAYMENT_IN_PROGRESS는 만료 정리 대상 아님. 상태 변경0행은 재조회/분기, 성공 가정 금지.
- 선택 가능/잔여석 = 판매 가능 AVAILABLE + 만료됐고 예약이 PAYMENT_IN_PROGRESS가 아닌 HOLD. 만료 HOLD의 예약ID를 모아 findPaymentInProgress 한 번 호출, 다른 모듈 테이블 JOIN 안 함. 실제 재선점은 expireIfDue 조건부 변경으로 최종 방어.
- SeatFacade.sell은 HOLD+reservation_id 일치, release도 reservation_id 일치. 갱신 행 수가 요청 수와 다르면 확정 성공으로 처리하지 않는다. 가격 재조회(priceSnapshot) 없음.
- release 결과 RELEASED/EXPIRED/ALREADY_CLOSED만 좌석 반환. NOT_RELEASABLE은409, PAYMENT_IN_PROGRESS/CONFIRMED/CANCELING/CANCELED 좌석은 해제하지 않음.

## 결제·환불·Saga

| 단계 | 설계 동작 |
|---|---|
| 준비 | 예매가 쿠폰/티켓에서 금액 확정, 시도 orderId 저장/재사용 → payment 준비. 원격 준비 성공과 예매 저장 순서 역전 방지는 별도 미합의(R01) |
| 인증 | 브라우저 PG 인증 → 토스 paymentKey/카카오 pg_token → 예매 승인. PG 인증값은 미완료 재전송에 필요한 동안만 예매 보관, 종료 시 제거. 응답/로그/이벤트 금지 |
| 승인 | 예매 PAYMENT_IN_PROGRESS 커밋 → payment 접수/실행 점유 커밋 → 트랜잭션 밖 HTTP PG → 결과 검증·저장. 실행 직전 상태/만료 재확인 |
| 결과 DONE | 현재 시도인지 확인 → 좌석 잠금/소유 확인 → 예매 CONFIRMED+좌석 SOLD+티켓 코드+쿠폰 USED를 한 로컬 트랜잭션으로 |
| 결과 FAILED | 시간 남으면 HOLD(이전 orderId/인증값 정리, 쿠폰 유지), 지났으면 EXPIRED+좌석/쿠폰 반환 |
| 결과 불명 | payment IN_PROGRESS, 예매는 조회/폴링. READY면 confirmRequestedAt 확인 후 계약에 따라 같은 승인 재전송, IN_PROGRESS면 기다림. 조회 불가만으로 좌석/쿠폰 해제 금지 |
| 결과 확정 시한 | approve_requested_at부터60초를 판단 기준으로 PG_UNRESOLVED FAILED. 서버 중단/적체 중60초 완료 보장은 아님. 실제 과금 없음의 증명 아님 |
| 늦은 승인 | FAILED는 유지, 해당 orderId만 AUTO_VOID 전액 환불. 새 시도의 예매/쿠폰/티켓 건드리지 않음. AMOUNT_MISMATCH 과금도 실제 승인액 검증 후 보상 |
| 사용자/회차 취소 | 예매 CANCELING(티켓 사용 불가) 먼저 커밋 → 동일 orderId 환불 인수 → CANCELED+좌석 반환+쿠폰 복원. 응답 유실은 CANCELING에서 멱등 재요청 |
| 환불 완료 | payment 정상 DONE 원거래는 환불 DONE과 CANCELED를 함께 커밋. AUTO_VOID 원거래 FAILED는 유지. 환불 FAILED/차단은 운영 미완료로 남기며 예매/티켓을 되살리지 않음 |
| 확정 불가 | 일시 DB/잠금 장애는 예매 확정 재시도. 영구 불가는 과금 orderId+CONFIRM_FAILED 보상 의도 영속 저장 후 재시도. 예매 기존 B19 로그/지표만의 MVP 방침과 충돌하여 합의 대기 |

환불 접수, PG 취소 성공, 고객 카드/계좌 반영은 별개다. NOT_REQUIRED/SKIPPED의 의미는 API 계약 기준이며 돈이 없다는 근거 없이 확정하지 않는다. 사용자는 회차 시작 전 정책에 따라 취소, 시작 후 불가; 회차 취소/확정 실패 보상은 사용자 취소 기한 예외. 부분 환불·취소 수수료 차감은 현재 미지원.

환불 WAITING은 결제 DONE 확인 후 REQUESTED, FAILED 확인 후 SKIPPED; SKIPPED에서 늦은 과금 발견 시 REQUESTED로 재개. 취소 키는 기존 세대 내 유지하고 관리자 재시도에서만 새 세대를 만든다.

CouponUsage #27 초안: reservationId 점유, 발급 시 할인액 사용. reserve 시 유효기간 확인, 이후 RESERVED는 만료 배치가 풀지 않음. 유효기간 경과 후 결제 확정도 USED. FAILED→HOLD는 유지, EXPIRED/RELEASED는 해제, CANCELED 시 USED 복원(원래 만료됐으면 EXPIRED). 쿠폰 변경은 기존 release→새 reserve 같은 트랜잭션. 취소 복원 시점은 결제/예매 동의·쿠폰 담당 합의 대기다.

## 복구·워커 고도화

- PG 응답은 Approved/Rejected/Unknown으로 분류. 요청 전 연결 실패·명시 거절과 전송 후 timeout/5xx/파싱 오류를 구분. 후자는 Unknown. 429 미처리 보장은 fake 가정이며 실PG는 확인 전 Unknown.
- payment의 트랜잭션은 별도 `*Transactions` bean에 두어 Spring 프록시를 거친다. 자기 호출로 트랜잭션을 우회하지 않으며 접수/완료 저장과 외부 실행을 나눈다. 예매 스케줄러도 후보 조회/짧은 상태 변경/원격 조회를 분리한다.
- 승인은 무조건 재전송하지 않고 같은 PG/업무 키로 조회. 결과 불명 중 다른 PG로 승인 전환 금지. 응답 거래ID/주문/금액/상태 및 제공된 가맹점/통화를 대조. 전액 환불 완료도 실제 취소액을 대조.
- 결제 복구 조회 주기10초, 예매 폴링은 별도. 후보별 예외 격리/공정성/조회 예산 필요. FAILED의 PG 호출 대상은 종료11분·24시간 뒤 최소 대사 계획; Unknown은 체크포인트 소비 안 함. 마지막 대사 후 늦은 실행까지 안전하다는 보장은 없음. reconciled_at 이행은 tables 참조.
- 환불 불명은 REQUESTED 유지+기존 키 조회/재시도, 한도 이후 REQUESTED 그대로 차단/경보. 업무 거절은 FAILED, 관리자 retry는 cancel_attempt 증가·새 취소 세대, unblock은 기존 세대 자동 복구 재개. 늦은 이전 거절이 새 성공을 덮지 않게 상태/세대 확인.
- 순서: 동기 기준선 → 환불/복구 실행 풀 분리 → 같은 payment 코드/DB의 API·워커 프로세스 분리 → Kafka 직접 실행과 Inbox 인계 비교. 별도 업무 서비스가 아님. 준비/Inline 승인은 유지.
- 기존 IN_PROGRESS/REQUESTED/next_attempt_at을 영속 작업으로 활용. 별도 job 테이블 기본 추가 금지. 복잡한 다단계가 필요할 때만 설계. API와 워커가 같은 최초 환불 실행을 이중 수행하지 않도록 역할별 scheduler/consumer/publisher 설정.
- Kafka 수신은 **Inbox(processed_event 역할)+환불 의도/WAITING·REQUESTED 같은 트랜잭션 커밋 → offset**. PG 실행은 별도 워커. eventId 중복 제거와 orderId 업무 멱등은 둘 다 필요. Inbox만 먼저 저장 금지.
- Outbox는 발신 상태와 발행 의도를 같이 저장, ACK 유실 재발행 전제. Kafka 발행/결제 DB 인수/PG 완료를 구분해 접수 확인 방식을 합의한다. final fact만 결과 이벤트로 발행. 승인 인증값은 메시징 저장소에 싣지 않음.
- 점유는 짧은 FOR UPDATE SKIP LOCKED 트랜잭션→커밋→외부 호출→결과 재확인. 임대 만료로 이전 외부 호출이 취소되는 것은 아님. 실행 소유/세대·PG 멱등·조회·보상을 함께 사용.
- PG별 승인/환불/조회 동시성·초당 호출·총 인스턴스 예산 제한, 조회/복구 자원 예약. 큐 용량/최대 대기/포화 정책 설정. 이미 인수한 환불 드롭 금지. DLT는 실패 보관/운영 재처리이며 완료가 아님.

## 필수 구현·검증 관문

아래는 기존 reliability R01~R18과 작업 완료 조건을 압축한 요구사항이다. 미실행 항목은 미완료이며 문서 통합으로 삭제되지 않는다.

| ID·시점 | 반드시 검증할 경계 |
|---|---|
| R01 MVP | 준비 동시/역순/재시작, 오래된 카카오 tid 응답이 새 시도/종료 상태를 덮지 않음; 예매와 직렬화/세대 계약 합의 |
| R02 MVP | HTTP200+READY_FAILED 매핑, 서버 계산 금액·음수/초과할인/정수 범위 |
| R03 MVP | 승인 중복·인증값 재사용·만료 경계·접수된 READY 대체 금지 |
| R04 MVP | 타거래/누락/비정상 금액 성공 응답 거절, 실제 전액 취소 검증 |
| R05 MVP | PG 성공 후 DB 실패/DB 커밋 후 응답 유실/호출 전 종료/조회 장애 |
| R06 MVP | A시도 시한종료 후 B성공·A늦은승인, A만 보상; 일시 복수 과금 금액/노출시간 |
| R07 MVP/운영 전 | 대사 체크포인트 점유·재시작·Unknown 재조회, 지연 실행·PG 보장 검증 |
| R08 MVP→고도화 | 59/60초 경계와 실제 실행 지연 분리, 느린 조회로 묶음 지연/공정성/최고 나이 |
| R09 MVP→고도화 | 다중 실행·재인수·구 워커 늦은 성공/거절의 최신 상태 역전 방지 |
| R10 MVP | 영구 확정 불가의 영속 보상·재시작, 로그/지표만으로 완료 판정 금지 |
| R11 MVP | CANCELING 저장/전송/접수응답/CANCELED 저장 각각 중단·복구, 환불 실패로 티켓 부활 금지 |
| R12 MVP | 좌석/예약/쿠폰 원자 롤백·갱신행 부족·이전 orderId 격리 |
| R13 고도화 | Outbox ACK 유실·DB/offset 사이 종료·다른 eventId 같은업무·역순·DLT 재처리 |
| R14 고도화 | 대량환불 포화에서 일반승인/조회 보호·합산PG예산·미인수/인수작업 구분 |
| R15 MVP/운영 전 | 관리자/상태 전이 이력(전후상태,거래ID,세대,시각,작업자,사유,PG근거), 미완료 목록·건수/금액/나이·담당·경보·종료 근거. 이력 스키마 미정 |
| R16 MVP | 내부/관리자 무권한 접근·위조헤더 거절, 오류/URL/로그/이력/DLT 비밀값 제거 |
| R17 운영 전 | DONE/CANCELED 포함 전체 PG 대사·외부취소/부분취소/내부누락; 웹훅 채택 시 공식 진위검증·중복/역순/유실복구 |
| R18 운영 전 | 버전별 DDL 이행·미완료작업 보존·백업복원 후 PG 재대조, RPO/RTO·재개 승인자 |

Saga 완료는 재시작 후 다음 작업 발견·일시 실패 재시도·영구 실패 보상·접수 유실 재개·로컬 원자성·미완료 운영 인수를 연결 시험으로 증명해야 한다. Kafka/별도 saga 테이블 존재 또는 결제 단독 가짜 포트 시험만으로 완료가 아니다. 기존 Task 매핑: 1-1(경계합의),7~12/12-1(MVP),10-1(이력/운영),13~18(성능·Kafka),19/21/21-1(전체대사·실PG·운영).

## 측정·예상 한계

| 비교/장애 | 지표·판정 |
|---|---|
| 정상·TOSS/KAKAO 혼합 | 같은 자원/데이터/PG 한도에서 접수RPS와 승인 DB커밋 완료TPS 분리, p50/p95/p99·오류·금액 대조 |
| 거절·처리전5xx·전송후유실·지연승인·조회실패·429 | 고정 장애 설정/실행조건 기록, Unknown 복구·1분 판정 지연·AUTO_VOID 완료·중복/누락 금액 |
| 중복·만료·동시환불·프로세스종료 | 위 R 행렬 자동화, PG/payment/booking 대조; 측정 전후 미해소 건수·금액0 또는 담당 인수 |
| 순차HTTP→제한병렬HTTP→Kafka직접→Inbox워커 | 총자원/PG예산 고정. 병렬화 효과와 Kafka 효과 구분. 환불 접수/완료, 일반승인/조회p95,p99,DB풀/락/CPU,PG동시수·429 |
| 적체·복구 | Kafka lag만 보지 않고 Outbox/DB작업 최고대기·건수·미환불금액·최장 Unknown·복구시간 |
| 쿠폰발급+좌석선점 동시 부하 | 같은 reservation 프로세스/DB 경합과 사용자별1장/수량 불변식 |

동기 MVP는 느린 PG가 HTTP 스레드/실행 풀을 점유하고 복구 후보 순차 조회가 밀릴 수 있다. Kafka는 버퍼링·재시작·격리에 유리하지만 같은 PG/DB 한도에서 완료TPS 자동 증가를 보장하지 않는다. 측정 결과는 이슈/PR에 커밋·장비·컨테이너 자원·seed/설정·부하·지속시간·원본 결과·개선 전후·최종 정합성을 기록한다. 목표값/시나리오는 실측 결과로 보고하지 않는다.

기존 실험 입력(측정값 아님): MVP PG50ms/실패0 기준선, 지연1·3·5초, 승인한도50건/초; 고도화 환불5,000건+일반요청, 서비스30초 중지, 확장 정산100만건. 단독 payment→PG 비교가 주 실험이고 전체 Gateway→예매 연결은 영향 확인용이다. 워밍업 뒤3회·중앙값, 한 번에 변수 하나 변경. 총 CPU/메모리/DB풀 합계 고정, 목표/실제 유입률·k6 dropped_iterations도 기록한다.

| 장애 행렬 | 고정 입력·기대 결과 |
|---|---|
| 4a 처리전 실패 | CONFIRM serverErrorRate=1 → PG 미승인이어도 호출자는 Unknown/조회로 확정 |
| 4b 명시 거절 | CONFIRM failRate=1 → REJECTED, 자동 승인 재전송 없음 |
| 4c 승인 응답 유실 | CONFIRM responseLossRate=1,holdMs>read timeout → 조회 DONE 또는 시한 뒤 AUTO_VOID |
| 4d 지연 승인 | delayedApprovalRate=1,delayedApprovalMs=70000; PG_UNRESOLVED 목표면 INQUIRE 장애도 설정 → FAILED 유지·늦은 과금만 환불 |
| 4e/4f 환불 장애 | CANCEL serverErrorRate=1 또는 responseLossRate=1 → REQUESTED 유지·동일 키/조회 복구·실제 취소1회 |
| 4g 조회 장애 | INQUIRE serverErrorRate=1 또는 latencyMs>timeout → 승인/취소 사실과 조회 실패 구분 |
| 4h 환불 거절 | CANCEL failRate=1 → FAILED·경보, 관리자 retry의 새 취소 세대로 재개 |

확률1 단일 장애 검증 후 0/1%/10% 부하로 확대하고 실제 적용 건수/분모를 기록한다. responseLossRate는 TCP 단절이 아니라 처리 후 holdMs 대기·500 반환 모사다. 설정 PUT의 생략 항목은 기존 값 유지가 아니라 기본값으로 초기화된다. 특정 orderId/시도별 강제 시나리오·seed/거래ID 기반 선택은 추가 구현 필요(현재 Random 재현 보장 없음). 장애 해제 후 관찰 기간·24시간 대사 미관찰 구간을 남긴다.

## 미합의·구현 차이 — 여기서만 관리

| 항목 | 필요한 결정/후속 작업 |
|---|---|
| 준비 순서 R01 | 예매의 준비 직렬화/영속 점유/세대 검증, 오래된 원격 시도 차단, READY_FAILED 매핑 |
| 결제 연결 필드 | sellerId/performanceId 확보 경로·스냅샷. 현재 판매 검증 응답에는 두 필드 없음; 임의 추가 금지 |
| CouponUsage #27 | 쿠폰 담당의 인자/반환/상태·취소 복원 합의와 실제 이전·DDL·호출 연결 |
| 예매 보상/취소 | CANCELING enum/CHECK/집계/복구 구현(부분UNIQUE는 이미 포함), B19와 R10 범위 합의 |
| 회차 취소 | 공연202만 확정. 공연→예매 URI·payload·영속재시도 주체·결제중보호·좌석재판매차단·PG환불진행 조회 합의. reservation-summary 건수만으로 PG환불 완료 판단 금지 |
| 삭제 전 조회 | 공연/회차의 예매 존재 확인·오류·조회 불가 정책 계약 |
| 연령·회원탈퇴 | 나이 미달 차단의 생년월일/기준시각/ageRating 전달, 진행 예매/공연 보유 회원 탈퇴 정책 |
| 시간·시각화·페이징 | 공통 Offset 직렬화 이행, Page DTO/size 통일; 현행 형식 차이는 conventions/API 참조 |
| 입장·캐시 | 공연단위 토큰TTL10분, 좌석조회/선점에만 적용. 발급/조회응답·Redis키·오픈전 진입·잔여석캐시TTL 미정 |
| 만료 직전 요청 | payment.expiresAt=holdExpiresAt 경계·전송 중 만료 처리·grace 합의 |
| 내부 보안·운영 | 서비스/관리자 호출 인증·감사주체·이력스키마·재처리 승인·미완료경보 합의 |
| 추가 API | 티켓 조회/관리자예매목록/전체공연취소는 현행 계약 없음. 공연 전체는 회차별 취소 |
| 공연 세부 | 등급/좌석 미존재 오류 코드, 공개정적배치 접근권한, 좌석준비 URI/타임아웃/자동재시도/영구실패 운영 |
