// =====================================================================================================
// 쿠폰 선착순 발급 부하 스크립트: 지속 부하(#238)
// =====================================================================================================
//
// [이 스크립트가 하는 일]
//   정해진 시간(DURATION) 동안 1초에 RATE건씩 일정하게 발급 요청을 보낸다.
//   coupon-issue.js(한순간에 몰아 보내기)와 달리 몇 분 동안 같은 속도를 유지하므로,
//   Grafana 대시보드에서 처리량·응답 시간·DB 커넥션이 시간에 따라 어떻게 움직이는지 볼 수 있다.
//
// [몰아 보내기와 지속 부하의 차이]
//   몰아 보내기: 5000명이 동시에 한 번씩 → 순간 최대 경합을 본다(1초 안에 끝나 그래프에 잘 안 잡힘)
//   지속 부하:   1초에 50건씩 3분 동안 → "이 속도를 버티는가"를 본다(그래프에 선으로 보임)
//
// [constant-arrival-rate 실행기]
//   VU 수가 아니라 "1초에 몇 건 시작할지"를 정한다. 서버가 느려져도 k6는 정해진 속도로 계속 새 요청을 시작하고,
//   응답을 기다리는 VU가 모자라면 미리 준비한 VU(PRE_VUS)를 넘어 MAX_VUS까지 늘린다.
//   MAX_VUS까지 다 써도 모자라면 요청을 시작하지 못하고 dropped_iterations로 센다.
//   → dropped_iterations가 0보다 크면 "서버가 이 속도를 못 따라간다"는 신호다.
//   VU 수는 부하 크기를 바꾸지 않는다(부하는 RATE가 정한다). VU는 응답을 기다려 주는 일꾼일 뿐이다.
//
// [PRE_VUS 기본값을 MAX_VUS와 같게 둔 이유]
//   측정 중에 VU를 새로 만드는 데는 시간이 걸린다. PRE_VUS를 작게 두면 응답이 몇 초 늦어지는 순간
//   VU를 만드는 사이 요청을 놓쳐, MAX_VUS에 닿지 않았는데도 dropped_iterations가 생긴다.
//   (RATE 150에서 VU를 200명만 쓰고도 dropped 80이 났고, PRE_VUS=MAX_VUS로 다시 재자 0이었다.)
//   처음부터 MAX_VUS만큼 만들어 두면 dropped_iterations는 "MAX_VUS가 모두 묶일 만큼 밀렸다"는 뜻만 갖는다.
//
// [시나리오: 인기 쿠폰 하나에 계속 발급]
//   setup()에서 쿠폰 하나를 만들고, 요청마다 다른 회원으로 그 쿠폰을 발급한다.
//   기본 수량은 "보낼 요청 수 전체"라 모든 요청이 실제 발급(201) 경로를 탄다(소진 409는 가벼운 경로라 측정이 덜 된다).
//   QUANTITY를 일부러 작게 주면 중간에 소진돼 이후는 409가 된다.
//
// [실행] script/coupon-load/README.md를 본다. Grafana 대시보드를 띄워 두면 시간에 따른 변화를 볼 수 있다.
//
// [환경 변수] -e 이름=값 으로 넘긴다. 모두 기본값이 있다
//   RESERVATION_URL  reservation-service 주소                                  (기본 http://reservation-service:8083)
//   RATE             1초에 시작할 발급 요청 수                                   (기본 50)
//   DURATION         유지 시간. 30s, 3m 같은 형식                                (기본 3m)
//   QUANTITY         쿠폰 총 수량                                               (기본 RATE × DURATION초 = 전부 발급)
//   MAX_VUS          최대 VU 수. 약 10초어치 요청이 밀려 쌓이면 실패로 본다      (기본 RATE × 10)
//   PRE_VUS          미리 준비할 VU 수                                          (기본 MAX_VUS)
//   USER_ID_BASE     가상 회원 ID 시작값. 요청 번호를 더해 회원 ID를 만든다       (기본 1000000)
// =====================================================================================================

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
// exec.scenario.iterationInTest: 시험 전체에서 이 요청이 몇 번째인지(0부터, VU가 달라도 겹치지 않음)
import exec from 'k6/execution';

// -----------------------------------------------------------------------------------------------------
// 설정값
// -----------------------------------------------------------------------------------------------------
const BASE = __ENV.RESERVATION_URL || 'http://reservation-service:8083';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '3m';
const DURATION_SEC = toSeconds(DURATION);
// 기본은 보낼 요청 전체만큼. 모든 요청이 실제 발급(행 잠금 + INSERT) 경로를 지나게 한다
const QUANTITY = Number(__ENV.QUANTITY || RATE * DURATION_SEC);
// 합격 기준선. RATE마다 "약 10초어치 요청이 밀려 쌓이면 실패"라는 같은 기준이 되도록 RATE에 비례시킨다
const MAX_VUS = Number(__ENV.MAX_VUS || RATE * 10);
// 측정 중 VU 생성 지연으로 요청을 놓치지 않게, 기준선까지 처음부터 모두 만들어 둔다
const PRE_VUS = Number(__ENV.PRE_VUS || MAX_VUS);
// 1인 1장은 쿠폰마다 따지고 실행마다 새 쿠폰을 만들므로 겹쳐도 되지만, 몰아 보내기 스크립트(100000~)와 구분되게 둔다
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 1000000);

// '30s', '3m', '1m30s' 같은 k6 시간 문자열을 초로 바꾼다. 기본 수량 계산에만 쓴다
function toSeconds(text) {
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(text);
  if (!match || text === '') {
    throw new Error(`DURATION 형식이 잘못됐다: ${text} (예: 30s, 3m, 1m30s)`);
  }
  return Number(match[1] || 0) * 3600 + Number(match[2] || 0) * 60 + Number(match[3] || 0);
}

// -----------------------------------------------------------------------------------------------------
// 집계 지표
// -----------------------------------------------------------------------------------------------------
// 발급 결과를 outcome 태그로 나눠 센다. 예: '201', '409:COUPON_SOLD_OUT'
const issueOutcome = new Counter('coupon_issue_outcome');
// 발급 성공(201) 건수. 수량을 넘으면 안 된다
const issued = new Counter('coupon_issued');

// 201(발급)과 409(소진)를 정상 응답으로 둔다. http_req_failed에는 5xx·연결 오류만 남는다
http.setResponseCallback(http.expectedStatuses(201, 409));

// -----------------------------------------------------------------------------------------------------
// 시험 설정
// -----------------------------------------------------------------------------------------------------
export const options = {
  scenarios: {
    sustained: {
      // 1초(timeUnit)마다 RATE건씩 새 요청을 시작한다. 응답 속도와 관계없이 도착 속도를 일정하게 유지한다
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    // 예상하지 못한 응답(5xx·연결 오류)이 한 건도 없어야 한다
    http_req_failed: ['rate==0'],
    // 수량을 넘겨 발급하면 안 된다(초과 발급 없음)
    coupon_issued: [`count<=${QUANTITY}`],
    // 정해진 속도를 따라가지 못해 시작조차 못 한 요청이 없어야 한다. 이게 깨지면 이 RATE가 한계를 넘은 것이다
    dropped_iterations: ['count==0'],
    // 발급 API 응답 시간을 요약에 따로 보이게 하는 표시용 조건(항상 참). 판정 기준은 아직 정하지 않았다
    'http_req_duration{name:issue}': ['max>=0'],
  },
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

// 응답을 집계용 문자열로 바꾼다. 오류면 응답 본문의 code까지 붙인다
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
    name: `지속 부하 쿠폰 ${new Date().toISOString()}`,
    discountAmount: 1000,
    totalQuantity: QUANTITY,
    // 과거 시작 시각은 서버가 현재 시각으로 저장해 만들자마자 발급 중이 된다(#197)
    issueStartAt: '2000-01-01T00:00:00',
    issueEndAt: '2099-12-31T00:00:00',
    validDays: 7,
  };
  const res = http.post(`${BASE}/api/coupons`, JSON.stringify(body), {
    headers: userHeaders(1, 'MASTER'),
    tags: { name: 'create' },
  });
  if (!check(res, { '쿠폰 생성 201': (r) => r.status === 201 })) {
    fail(`쿠폰을 만들지 못했다: ${res.status} ${res.body}`);
  }
  console.log(`쿠폰 ${res.json('data.couponId')} 생성: 초당 ${RATE}건 × ${DURATION}, 수량 ${QUANTITY}`);
  return { couponId: res.json('data.couponId') };
}

// -----------------------------------------------------------------------------------------------------
// 요청 하나: 매번 다른 회원으로 발급한다
// -----------------------------------------------------------------------------------------------------
export default function (data) {
  // 지속 부하에서는 VU 하나가 요청을 여러 번 보내므로 VU 번호(__VU)로는 회원이 겹친다(1인 1장 409).
  // 시험 전체의 요청 순번을 써서 요청마다 다른 회원을 만든다
  const userId = USER_ID_BASE + exec.scenario.iterationInTest;
  const res = http.post(`${BASE}/api/coupons/${data.couponId}/issue`, null, {
    headers: userHeaders(userId, 'USER'),
    tags: { name: 'issue' },
  });

  const outcome = outcomeOf(res);
  issueOutcome.add(1, { outcome });
  if (res.status === 201) issued.add(1);

  check(res, { '발급 201 또는 409': (r) => r.status === 201 || r.status === 409 });
}
