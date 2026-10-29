// 결제 흐름 부하 스크립트: 준비 → 사용자 결제 시뮬레이션 → 승인 → (일부) 환불.
// Task 12 혼합 부하(D1·D2)와 #120 기준선 측정에서 같이 쓴다. 예매 서비스 없이 payment 내부 API를 직접 부른다.
//
// 실행(단독 환경 네트워크 안에서):
//   docker run --rm --network ticket-t12_t12-net -v "$PWD/script/task12/k6:/scripts" \
//     -e ITERATIONS=500 -e VUS=20 grafana/k6:0.54.0 run --summary-export=/scripts/out/summary.json /scripts/payment-flow.js
//
// 환경 변수: PAYMENT_URL, FAKE_PG_URL, ITERATIONS, VUS, REFUND_RATIO(0~1, 기본 0.5), AMOUNT, RUN_ID(주문번호 접두어)
// 승인·환불 응답이 5xx나 타임아웃이어도 실패로 단정하지 않는다. 최종 판정은 장애를 푼 뒤 DB·PG 대조(verify.sql)로 한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const PAYMENT = __ENV.PAYMENT_URL || 'http://payment-service:8084';
const FAKE_PG = __ENV.FAKE_PG_URL || 'http://fake-pg:8090';
const AMOUNT = Number(__ENV.AMOUNT || 10000);
const REFUND_RATIO = Number(__ENV.REFUND_RATIO || 0.5);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const CHECKOUT_RETRIES = Number(__ENV.CHECKOUT_RETRIES || 0);
const PACE_SECONDS = Number(__ENV.PACE_SECONDS || 0);

const confirmOutcome = new Counter('t12_confirm_outcome');
const refundOutcome = new Counter('t12_refund_outcome');

export const options = {
  scenarios: {
    flow: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 20),
      iterations: Number(__ENV.ITERATIONS || 100),
      maxDuration: __ENV.MAX_DURATION || '20m',
    },
  },
  // 결제 정합성 시험이 목적이라 지연 기준으로 실패 처리하지 않는다. 지연은 #120에서 따로 기준을 정한다.
  thresholds: {},
};

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' }, timeout: '30s' };

function outcomeTag(res) {
  if (res.status === 0) return 'transport_error';
  return `${Math.floor(res.status / 100)}xx`;
}

export default function () {
  if (PACE_SECONDS > 0) sleep(PACE_SECONDS);
  // 전체 실행에서 몇 번째 반복인지. VU 수와 무관하게 같은 입력이면 같은 주문·PG·환불 대상이 나온다
  const iteration = exec.scenario.iterationInTest;
  const provider = iteration % 2 === 0 ? 'TOSS' : 'KAKAO';
  const orderId = `t12-${RUN_ID}-${provider.toLowerCase()}-${iteration}`;
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();

  const checkoutBody = JSON.stringify({
    orderId,
    reservationId: iteration + 1,
    userId: 1001,
    sellerId: 2001,
    performanceId: 3001,
    scheduleId: 4001,
    totalPrice: AMOUNT,
    discountAmount: 0,
    pgProvider: provider,
    expiresAt,
  });
  let checkout;
  // 재시작 중 연결 실패는 같은 주문·같은 본문으로 재시도한다. 업무 거절은 재시도하지 않는다.
  for (let attempt = 0; attempt <= CHECKOUT_RETRIES; attempt++) {
    checkout = http.post(`${PAYMENT}/internal/payments`, checkoutBody, JSON_HEADERS);
    if (checkout.status !== 0 && checkout.status < 500) break;
    if (attempt < CHECKOUT_RETRIES) sleep(2);
  }
  if (!check(checkout, { 'checkout 200': (r) => r.status === 200 })) return;

  let token;
  if (provider === 'TOSS') {
    const auth = http.post(`${FAKE_PG}/fake/toss/checkout`, JSON.stringify({ orderId, amount: AMOUNT }), JSON_HEADERS);
    if (!check(auth, { 'toss auth 200': (r) => r.status === 200 })) return;
    token = auth.json('paymentKey');
  } else {
    const url = checkout.json('checkoutUrl') || '';
    const tid = url.substring(url.lastIndexOf('/') + 1);
    const auth = http.post(`${FAKE_PG}/fake/kakao/approve`, JSON.stringify({ tid }), JSON_HEADERS);
    if (!check(auth, { 'kakao auth 200': (r) => r.status === 200 })) return;
    token = auth.json('pg_token');
  }

  const confirm = http.post(`${PAYMENT}/internal/payments/${orderId}/confirm`,
    JSON.stringify({ pgAuthToken: token, amount: AMOUNT }), JSON_HEADERS);
  confirmOutcome.add(1, { provider, outcome: outcomeTag(confirm) });

  // 환불 대상은 반복 번호로 정해 같은 입력이면 같은 건을 환불한다(재현성). 37은 100과 서로소라 100건마다 비율이 정확히 맞는다
  if (confirm.status === 200 && (iteration * 37) % 100 < REFUND_RATIO * 100) {
    const refund = http.post(`${PAYMENT}/internal/payments/${orderId}/refund`,
      JSON.stringify({ reason: 'USER_CANCEL' }), JSON_HEADERS);
    refundOutcome.add(1, { provider, outcome: outcomeTag(refund) });
  }
}
