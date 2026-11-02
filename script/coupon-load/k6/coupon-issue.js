// =====================================================================================================
// 쿠폰 선착순 발급 부하 스크립트: 순간 몰림(#238)
// =====================================================================================================
//
// [이 스크립트가 하는 일]
//   수량이 정해진 쿠폰 하나에 많은 회원이 동시에 발급을 요청해,
//   1) 수량만큼만 정확히 발급되는지(정합성: 초과 발급 없음)
//   2) 그때 발급 API의 응답 시간(p95 등)과 처리량이 얼마인지(기준 측정값)
//   를 확인한다. 나중에 발급 방식을 바꿨을 때(예: Redis 수량 선차감) 이 값과 비교한다.
//
// [k6 기초]
//   - VU(가상 사용자) 하나는 사용자 한 명이다. VU마다 이 파일을 따로 실행하고, VU끼리 변수를 공유하지 않는다.
//   - 실행 흐름: 초기화(파일 맨 위 코드, VU마다 한 번) → setup()(시험 전체에서 한 번) → VU 함수(여러 VU가 실행)
//   - check(): 응답 확인. 실패해도 시험은 계속되고 요약에 성공 비율만 남는다.
//   - thresholds: 시험이 끝난 뒤의 합격 조건. 하나라도 어기면 k6가 종료 코드 99로 끝난다.
//   - Counter: 직접 만드는 숫자 집계. add(1, 태그)로 더하고 태그별로 나눠 본다.
//
// [호출 방식]
//   Gateway를 거치지 않고 reservation-service를 직접 부른다. Gateway가 JWT를 검사한 뒤 넣어 주는 회원 헤더
//   (X-User-Id, X-Username, X-User-Role)를 k6가 직접 채운다. 그래서 JWT·라우팅 비용은 빠지고 쿠폰 로직과 DB 잠금 비용만 잰다.
//   reservation-service는 PC에 포트를 열지 않으므로, k6를 Docker로 Compose 네트워크(ticket_ticket-net)에 붙여 실행한다.
//
// [시나리오: 인기 쿠폰 선착순]
//   setup()에서 MASTER로 수량 QUANTITY장인 쿠폰을 하나 만들고, VU VUS명이 서로 다른 회원으로 그 쿠폰을 한 번씩 동시에 발급한다.
//   정답은 201(발급 성공) 정확히 QUANTITY건, 나머지는 모두 409 COUPON_SOLD_OUT, 5xx 0건이다.
//   실행할 때마다 새 쿠폰을 만들므로 서비스를 재시작하지 않고 다시 돌려도 된다(1인 1장은 쿠폰마다 따진다).
//
// [실행] script/coupon-load/README.md를 본다.
//
// [환경 변수] -e 이름=값 으로 넘긴다. 모두 기본값이 있다
//   RESERVATION_URL  reservation-service 주소                              (기본 http://reservation-service:8083)
//   VUS              동시에 발급을 요청하는 회원 수                          (기본 200)
//   QUANTITY         쿠폰 총 수량. VUS보다 작아야 소진 경합이 생긴다         (기본 50)
//   USER_ID_BASE     가상 회원 ID 시작값. VU 번호를 더해 회원 ID를 만든다    (기본 100000)
// =====================================================================================================

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

// -----------------------------------------------------------------------------------------------------
// 설정값. __ENV는 -e 로 넘긴 환경 변수다. 없으면 || 뒤의 기본값을 쓴다
// -----------------------------------------------------------------------------------------------------
const BASE = __ENV.RESERVATION_URL || 'http://reservation-service:8083';
const VUS = Number(__ENV.VUS || 200);
const QUANTITY = Number(__ENV.QUANTITY || 50);
// VU마다 다른 회원이어야 1인 1장에 걸리지 않는다. 다른 테스트 회원과 겹치지 않게 큰 값부터 쓴다
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 100000);

if (QUANTITY >= VUS) {
  // 수량이 회원 수 이상이면 모두 받아서 소진 경합이 생기지 않는다. 초기화 단계에서 던지면 시험을 시작하지 않는다
  throw new Error(`QUANTITY(${QUANTITY})는 VUS(${VUS})보다 작아야 한다`);
}

// -----------------------------------------------------------------------------------------------------
// 직접 만드는 집계 지표
// -----------------------------------------------------------------------------------------------------
// 발급 결과를 outcome 태그로 나눠 센다. 태그 값 예: '201', '409:COUPON_SOLD_OUT', '500:INTERNAL_SERVER_ERROR'
const issueOutcome = new Counter('coupon_issue_outcome');
// 발급 성공(201) 건수. 수량과 같아야 한다
const issued = new Counter('coupon_issued');

// k6는 기본으로 4xx·5xx를 모두 실패(http_req_failed)로 센다. 이 시험에서 409(소진)는 경합에서 진 정상 결과라
// 201·409를 정상으로 등록한다. 그러면 http_req_failed에는 5xx·연결 오류처럼 예상하지 못한 응답만 남는다
http.setResponseCallback(http.expectedStatuses(201, 409));

// -----------------------------------------------------------------------------------------------------
// 시험 설정
// -----------------------------------------------------------------------------------------------------
export const options = {
  scenarios: {
    hot: {
      // per-vu-iterations: VU마다 정확히 iterations번 실행한다. 모두 거의 동시에 출발해 같은 쿠폰에 요청이 몰린다
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      // 이 시간 안에 끝나지 않으면 남은 반복을 버리고 끝낸다(무한 대기 방지)
      maxDuration: '2m',
    },
  },
  // 합격 조건. 기준 측정이 목적이라 응답 시간으로는 불합격 처리하지 않고 정합성만 판정한다
  thresholds: {
    // 예상하지 못한 응답(5xx·연결 오류)이 한 건도 없어야 한다
    http_req_failed: ['rate==0'],
    // 수량만큼 정확히 발급돼야 한다(초과 발급 없음)
    coupon_issued: [`count==${QUANTITY}`],
    // 나머지는 모두 소진(409 COUPON_SOLD_OUT)이어야 한다. 중괄호 안은 태그 필터다
    'coupon_issue_outcome{outcome:409:COUPON_SOLD_OUT}': [`count==${VUS - QUANTITY}`],
    // 발급 API의 응답 시간을 요약에 따로 보이게 하는 표시용 조건이다(항상 참)
    'http_req_duration{name:issue}': ['max>=0'],
  },
  // 요약에 보여 줄 응답 시간 통계. p(95)는 요청 95%가 이 시간 안에 끝났다는 뜻이다
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// -----------------------------------------------------------------------------------------------------
// 도움 함수
// -----------------------------------------------------------------------------------------------------

// Gateway가 넣어 주는 회원 헤더를 직접 만든다
function userHeaders(userId, role) {
  return {
    'Content-Type': 'application/json',
    'X-User-Id': `${userId}`,
    'X-Username': `load${userId}@test.com`,
    'X-User-Role': role,
  };
}

// 응답을 집계용 문자열로 바꾼다. 오류면 응답 본문의 code(예: COUPON_SOLD_OUT)까지 붙인다
function outcomeOf(res) {
  // status 0은 응답을 받지 못한 것(연결 실패·타임아웃)이다
  if (res.status === 0) return 'transport_error';
  if (res.status >= 400) {
    let code = 'unknown';
    try {
      code = res.json('code') || code;
    } catch (e) {
      // 본문이 JSON이 아니면 상태 코드만 남긴다
    }
    return `${res.status}:${code}`;
  }
  return `${res.status}`;
}

// -----------------------------------------------------------------------------------------------------
// setup(): 시험 전체에서 한 번. 발급할 쿠폰을 만든다
// -----------------------------------------------------------------------------------------------------
export function setup() {
  const body = {
    name: `부하 테스트 쿠폰 ${new Date().toISOString()}`,
    discountAmount: 1000,
    totalQuantity: QUANTITY,
    // 과거 시작 시각은 서버가 현재 시각으로 저장한다(#197). 그래서 만들자마자 발급 중(ISSUING)이다
    issueStartAt: '2000-01-01T00:00:00',
    issueEndAt: '2099-12-31T00:00:00',
    validDays: 7,
  };
  // 쿠폰 생성은 MASTER만 할 수 있다. 회원 ID 1을 관리자로 쓴다
  const res = http.post(`${BASE}/api/coupons`, JSON.stringify(body), {
    headers: userHeaders(1, 'MASTER'),
    tags: { name: 'create' },
  });
  if (!check(res, { '쿠폰 생성 201': (r) => r.status === 201 })) {
    // setup이 실패하면 VU를 시작해도 의미가 없으므로 시험을 멈춘다
    fail(`쿠폰을 만들지 못했다: ${res.status} ${res.body}`);
  }
  // 여기서 돌려준 값이 VU 함수의 인자(data)로 전달된다
  return { couponId: res.json('data.couponId') };
}

// -----------------------------------------------------------------------------------------------------
// VU 함수: 회원마다 한 번 발급을 요청한다
// -----------------------------------------------------------------------------------------------------
export default function (data) {
  // __VU는 현재 VU 번호(1부터)다. VU마다 회원이 달라진다
  const userId = USER_ID_BASE + __VU;
  const res = http.post(`${BASE}/api/coupons/${data.couponId}/issue`, null, {
    headers: userHeaders(userId, 'USER'),
    // 요약에서 이 요청을 'issue'로 묶어 응답 시간을 따로 본다
    tags: { name: 'issue' },
  });

  const outcome = outcomeOf(res);
  issueOutcome.add(1, { outcome });
  if (res.status === 201) issued.add(1);

  // 201(받음) 또는 409(소진) 말고는 나오면 안 된다. 정확한 건수 판정은 thresholds가 한다
  check(res, { '발급 201 또는 409': (r) => r.status === 201 || r.status === 409 });
}
