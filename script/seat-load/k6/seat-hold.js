// =====================================================================================================
// 좌석 선점 부하 스크립트(#172)
// =====================================================================================================
//
// [이 스크립트가 하는 일]
//   reservation-service의 좌석 API(조회·선점·해제)에 동시에 많은 요청을 보내,
//   1) 같은 좌석을 여러 명이 동시에 잡으려 할 때 정확히 1명만 성공하는지(정합성)
//   2) 그때 응답 시간(p95 등)과 처리량이 얼마인지(기준 측정값)
//   를 확인한다. 나중에 잠금 방식을 바꿨을 때 이 값과 비교한다.
//
// [k6 기초 - 처음 보는 사람을 위한 설명]
//   - k6는 JavaScript로 "가상 사용자(VU, Virtual User)"의 행동을 적으면, 그 행동을 여러 VU가 동시에 반복하는 부하 도구다.
//   - VU 하나는 독립된 사용자 한 명이라고 생각하면 된다. VU마다 이 파일을 따로 실행하므로 VU끼리 변수를 공유하지 않는다.
//   - 실행 흐름은 세 단계다.
//       1. 초기화(init): 파일 맨 위의 코드(import, const, options 등). VU마다 한 번씩 실행된다. 여기서는 HTTP 요청을 못 보낸다.
//       2. setup(): 시험 전체에서 딱 한 번 실행된다. 여기서 돌려준 값이 각 VU 함수의 인자(data)로 전달된다.
//       3. VU 함수: options.scenarios의 exec에 적은 함수다. executor(실행 방식) 규칙에 따라 여러 VU가 반복 실행한다.
//   - check(): 응답이 기대와 같은지 확인한다. 실패해도 시험은 멈추지 않고, 요약에 성공 비율만 남는다.
//   - thresholds(임계값): 시험이 끝난 뒤 "합격 조건"이다. 하나라도 어기면 k6가 종료 코드 99로 끝나 실패로 표시된다.
//   - Counter: 직접 만드는 숫자 집계다. add(1, 태그)로 더하고, 태그별로 나눠 볼 수 있다.
//
// [호출 방식]
//   Gateway를 거치지 않고 reservation-service를 직접 부른다. Gateway는 JWT를 검사한 뒤 회원 정보를 헤더
//   (X-User-Id 등)로 바꿔 넘겨 주는데, 여기서는 그 헤더를 k6가 직접 채운다. 그래서 JWT·라우팅 비용은 측정에서 빠지고
//   좌석 로직과 DB 잠금 비용만 잰다. 단, 선점할 때 reservation-service가 부르는 공연 판매 검증(Feign) 시간은 포함된다.
//
// [시나리오] SCENARIO 환경 변수로 하나씩 고른다
//   hot  인기석 경합: VU마다 다른 회원이 "같은 좌석"을 한 번씩 동시에 선점한다.
//        정답은 201(선점 성공) 정확히 1건, 나머지는 모두 409 SEAT_ALREADY_TAKEN이다.
//   loop 조회 → 선점 → 해제 반복: 정해진 시간 동안 VU마다 비어 있는 좌석을 골라 선점하고 바로 해제한다.
//        해제로 좌석이 다시 비므로 좌석 수가 적어도(회차당 40석) 부하를 계속 줄 수 있다.
//   두 시나리오 모두 좌석·예약 상태를 바꾼다. 실행 전마다 reservation-service를 재시작해 처음 상태로 돌린다
//   (local은 기동할 때마다 테이블을 새로 만들고 시드를 다시 넣는다).
//
// [실행] script/seat-load/README.md를 본다.
//
// [환경 변수] k6 실행 때 -e 이름=값 으로 넘긴다. 모두 기본값이 있다
//   SCENARIO         hot | loop                                       (기본 hot)
//   RESERVATION_URL  reservation-service 주소                          (기본 http://reservation-service:8083)
//   SCHEDULE_ID      대상 회차                                         (기본 hot=1, loop=2)
//   VUS              동시에 움직이는 가상 사용자 수                     (기본 hot=100, loop=50)
//   DURATION         loop를 돌리는 시간                                (기본 1m)
//   USER_ID_BASE     가상 회원 ID 시작값. VU 번호를 더해 회원 ID를 만든다 (기본 10000)
// =====================================================================================================

// k6 내장 모듈. http는 요청, check·fail은 검증, Counter는 직접 만드는 집계 지표다
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

// -----------------------------------------------------------------------------------------------------
// 설정값. __ENV는 k6가 넘겨 주는 환경 변수 객체다(-e 로 넘긴 값). 없으면 || 뒤의 기본값을 쓴다
// -----------------------------------------------------------------------------------------------------
const SCENARIO = __ENV.SCENARIO || 'hot';
// compose 네트워크 안에서는 서비스 이름(reservation-service)이 곧 호스트 이름이다
const BASE = __ENV.RESERVATION_URL || 'http://reservation-service:8083';
// local 시드(#124)에서 판매 중인 회차는 1~3이다. 두 시나리오가 서로의 좌석을 건드리지 않게 회차를 나눈다
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || (SCENARIO === 'hot' ? 1 : 2));
const VUS = Number(__ENV.VUS || (SCENARIO === 'hot' ? 100 : 50));
// 좌석은 회원·회차당 1장만 잡을 수 있다(1인 1장). VU마다 다른 회원 ID를 써야 제한에 걸리지 않는다.
// 다른 시험 데이터의 회원 ID와 겹치지 않도록 큰 수에서 시작한다
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 10000);

// -----------------------------------------------------------------------------------------------------
// 직접 만드는 집계 지표(Counter). k6가 기본으로 주는 지표(http_req_duration 등)만으로는
// "201이 몇 건, 409 중 어떤 code가 몇 건"을 알 수 없어서 따로 센다. 이름은 요약 화면에 그대로 나온다
// -----------------------------------------------------------------------------------------------------
// 선점 결과를 outcome 태그로 나눠 센다. 태그 값 예: '201', '409:SEAT_ALREADY_TAKEN', '500:INTERNAL_SERVER_ERROR'
const holdOutcome = new Counter('seat_hold_outcome');
// 선점에 새로 성공(201)한 횟수. hot에서는 정확히 1이어야 한다
const holdCreated = new Counter('seat_hold_created');
// 해제 결과를 outcome 태그로 나눠 센다. 정상은 '204'
const releaseOutcome = new Counter('seat_release_outcome');
// 1인 1장 초과(409 PURCHASE_LIMIT_EXCEEDED) 횟수. loop에서는 VU가 선점 후 바로 해제하므로
// 이게 나오면 해제가 빠졌거나 실패한 것이다. 0건이어야 한다
const purchaseLimitExceeded = new Counter('seat_purchase_limit_exceeded');

// -----------------------------------------------------------------------------------------------------
// "실패한 요청"의 기준을 정한다. k6는 기본으로 4xx·5xx를 모두 실패(http_req_failed)로 센다.
// 하지만 이 시험에서 409는 경합에서 진 정상 결과다. 그래서 200·201·204·409를 정상으로 등록해 두면
// http_req_failed에는 5xx나 연결 오류처럼 "예상하지 못한" 응답만 남는다
// -----------------------------------------------------------------------------------------------------
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

// -----------------------------------------------------------------------------------------------------
// 시나리오 정의. executor는 "VU들을 어떤 규칙으로 돌릴지"다
// -----------------------------------------------------------------------------------------------------
const scenarios = {
  hot: {
    // per-vu-iterations: VU마다 정확히 iterations번 실행한다. 여기서는 VU마다 한 번이다.
    // 모두 거의 동시에 출발하므로 같은 좌석에 요청이 몰린다.
    // Note: shared-iterations(반복 횟수를 VU들이 나눠 가짐)를 쓰면 빨리 끝난 VU가 두 번 실행할 수 있다. 좌석을 잡은
    // VU가 한 번 더 돌면 같은 회원·같은 좌석 재요청이라 서버가 기존 예약을 200으로 돌려줘 201·409 건수 판정이 어긋난다
    executor: 'per-vu-iterations',
    vus: VUS,
    iterations: 1,
    // 이 시간 안에 끝나지 않으면 남은 반복을 버리고 끝낸다(무한 대기 방지)
    maxDuration: '2m',
    // 각 VU가 실행할 함수 이름(아래 export function hotSeat)
    exec: 'hotSeat',
  },
  loop: {
    // constant-vus: VU 수를 일정하게 유지하며 duration 동안 함수를 계속 반복한다. 처리량·응답 시간을 볼 때 쓴다
    executor: 'constant-vus',
    vus: VUS,
    duration: __ENV.DURATION || '1m',
    exec: 'holdAndRelease',
  },
};

if (!scenarios[SCENARIO]) {
  // 초기화 단계에서 예외를 던지면 k6가 시험을 시작하지 않고 바로 끝난다
  throw new Error(`SCENARIO는 hot 또는 loop다: ${SCENARIO}`);
}

// -----------------------------------------------------------------------------------------------------
// 합격 조건(thresholds). 형식은 { '지표 이름{태그:값}': ['조건'] }이다.
// 기준 측정이 목적이라 응답 시간(p95 등)으로는 불합격 처리하지 않고, 정합성만 판정한다
// -----------------------------------------------------------------------------------------------------
const thresholds = {
  // 예상하지 못한 응답(5xx·연결 오류)이 한 건도 없어야 한다. rate는 실패 비율(0~1)이다
  http_req_failed: ['rate==0'],
  // API별 응답 시간을 요약에 따로 보이게 하는 표시용 조건이다. k6는 임계값을 건 태그만 요약에 나눠 보여 준다.
  // max>=0은 항상 참이라 합격·불합격에 영향이 없다
  'http_req_duration{name:seats}': ['max>=0'],
  'http_req_duration{name:hold}': ['max>=0'],
  'http_req_duration{name:release}': ['max>=0'],
};
if (SCENARIO === 'hot') {
  // 같은 좌석은 정확히 한 명만 잡아야 한다
  thresholds.seat_hold_created = ['count==1'];
  // 나머지 VU-1명은 모두 "이미 선점된 좌석"(409 SEAT_ALREADY_TAKEN)이어야 한다.
  // 중괄호 안은 태그 필터다. outcome 태그가 이 값인 것만 센다
  thresholds['seat_hold_outcome{outcome:409:SEAT_ALREADY_TAKEN}'] = [`count==${VUS - 1}`];
} else {
  // 선점 후 바로 해제하므로 1인 1장 초과는 나오면 안 된다
  thresholds.seat_purchase_limit_exceeded = ['count==0'];
  // 해제가 실제로 한 번 이상 성공했어야 시험이 의미 있다(전부 409로 끝나면 해제 경로를 못 잰 것)
  thresholds['seat_release_outcome{outcome:204}'] = ['count>0'];
}

// options는 k6가 읽는 시험 설정이다. export해야 k6가 찾는다
export const options = {
  // 고른 시나리오 하나만 등록한다
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds,
  // 요약에 보여 줄 응답 시간 통계. p(95)는 "요청 95%가 이 시간 안에 끝났다"는 뜻이다
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// -----------------------------------------------------------------------------------------------------
// 도움 함수
// -----------------------------------------------------------------------------------------------------

// Gateway가 넣어 주는 회원 헤더를 직접 만든다. __VU는 현재 VU 번호(1부터)다. VU마다 회원이 달라진다
function userHeaders(extra) {
  const userId = USER_ID_BASE + __VU;
  return {
    headers: Object.assign(
      { 'X-User-Id': `${userId}`, 'X-Username': `load${userId}`, 'X-User-Role': 'USER' },
      extra || {},
    ),
  };
}

// 응답을 집계용 문자열로 바꾼다. 오류면 응답 본문의 code(예: SEAT_ALREADY_TAKEN)까지 붙인다
function outcomeOf(res) {
  // status 0은 응답을 받지 못한 것(연결 실패·타임아웃)이다
  if (res.status === 0) return 'transport_error';
  if (res.status >= 400) {
    let code = 'unknown';
    try {
      // res.json('code')는 응답 JSON에서 code 필드를 꺼낸다. 오류 응답은 { code, message, ... } 형식이다
      code = res.json('code') || code;
    } catch (e) {
      // 본문이 JSON이 아니면(예: 프록시 오류 페이지) 상태 코드만 남긴다
    }
    return `${res.status}:${code}`;
  }
  return `${res.status}`;
}

// 좌석 조회 API로 지금 선택 가능한 좌석 ID 목록을 받는다. 조회는 회원 헤더가 필요 없다
function selectableSeatIds() {
  // tags.name은 요약에서 요청을 묶는 이름이다. URL마다 따로 보지 않고 'seats'로 모아 응답 시간을 본다
  const res = http.get(`${BASE}/api/seats/schedules/${SCHEDULE_ID}`, { tags: { name: 'seats' } });
  if (!check(res, { 'seats 200': (r) => r.status === 200 })) return [];
  // 응답 형식: { status, message, data: { scheduleId, seats: [{ scheduleSeatId, selectable, ... }] } }
  return res.json('data.seats').filter((seat) => seat.selectable).map((seat) => seat.scheduleSeatId);
}

// 좌석 1장을 선점하고 결과를 집계한다
function hold(scheduleSeatId) {
  const res = http.post(
    `${BASE}/api/seats/schedules/${SCHEDULE_ID}/holds`,
    JSON.stringify({ scheduleSeatId }),
    Object.assign(userHeaders({ 'Content-Type': 'application/json' }), { tags: { name: 'hold' } }),
  );
  const outcome = outcomeOf(res);
  // 두 번째 인자의 태그로 결과별로 나눠 센다
  holdOutcome.add(1, { outcome });
  if (res.status === 201) holdCreated.add(1);
  if (outcome === '409:PURCHASE_LIMIT_EXCEEDED') purchaseLimitExceeded.add(1);
  return res;
}

// -----------------------------------------------------------------------------------------------------
// setup: 시험 전체에서 한 번만 실행된다. 돌려준 값은 모든 VU 함수에 data 인자로 복사돼 전달된다
// -----------------------------------------------------------------------------------------------------
export function setup() {
  // 좌석 ID는 DB가 시드를 넣는 순서에 따라 정해져 하드코딩하면 틀릴 수 있다. 조회해서 고른다
  const seatIds = selectableSeatIds();
  if (seatIds.length === 0) {
    // fail()은 지금 실행을 중단한다. setup에서 부르면 시험 전체가 시작되지 않는다
    fail(`회차 ${SCHEDULE_ID}에 선택 가능한 좌석이 없다. reservation-service를 재시작해 시드를 다시 넣는다`);
  }
  // hot 시나리오에서 모든 VU가 노릴 좌석. 선택 가능한 첫 좌석을 쓴다
  return { hotSeatId: seatIds[0] };
}

// -----------------------------------------------------------------------------------------------------
// VU 함수(시나리오의 exec). export해야 k6가 이름으로 찾는다
// -----------------------------------------------------------------------------------------------------

// 인기석 경합: 모든 VU가 setup에서 고른 같은 좌석을 한 번씩 선점한다
export function hotSeat(data) {
  const res = hold(data.hotSeatId);
  // 201(내가 잡음) 또는 409(남이 먼저 잡음) 말고는 나오면 안 된다. 정확한 건수 판정은 thresholds가 한다
  check(res, { 'hold 201 or 409': (r) => r.status === 201 || r.status === 409 });
}

// 조회 → 선점 → 해제 반복: constant-vus라 duration 동안 이 함수가 VU마다 계속 다시 불린다
export function holdAndRelease() {
  // 1. 지금 비어 있는 좌석을 조회한다. 모두 다른 VU가 잡고 있으면 이번 반복은 건너뛴다
  const seatIds = selectableSeatIds();
  if (seatIds.length === 0) return;

  // 2. 그중 하나를 무작위로 골라 선점한다. 조회와 선점 사이에 다른 VU가 먼저 잡으면 409 SEAT_ALREADY_TAKEN이 나오는데,
  //    실제 사용자 상황과 같은 정상 경합이다
  const res = hold(seatIds[Math.floor(Math.random() * seatIds.length)]);
  check(res, { 'hold 201 or 409': (r) => r.status === 201 || r.status === 409 });
  if (res.status !== 201) return;

  // 3. 잡은 좌석을 바로 해제한다. 선점 응답의 reservationId로 부른다. 해제해야 같은 회원(VU)이 다음 반복에서 다시 선점할 수 있다
  const reservationId = res.json('data.reservationId');
  const release = http.del(
    `${BASE}/api/seats/schedules/${SCHEDULE_ID}/holds/${reservationId}`,
    // DELETE는 본문이 없다
    null,
    Object.assign(userHeaders(), { tags: { name: 'release' } }),
  );
  releaseOutcome.add(1, { outcome: outcomeOf(release) });
  check(release, { 'release 204': (r) => r.status === 204 });
}
