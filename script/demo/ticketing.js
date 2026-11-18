// 시연용 티켓팅 봇. 인기 공연 오픈 순간을 재현한다(prepare.py가 만든 input.json 사용).
// 오픈 전 공연 상세 대기 → 오픈 즉시 대기열 진입 → 순번 조회 → 입장 → 앞 구역 가중치로 좌석 선택(0.3~1.0초)
// → 선점(충돌 시 최대 RETRIES번 다시 고름) → 일부 결제 포기 → TOSS 결제 → 확정까지 조회. 결제 실패면 한 번 다시 결제.
// 예매 서비스 선점 위치가 REDIS면(#476) 선점 응답에 holdId만 오고 예약이 없다. 결제 직전에 좌석 선택 완료(holds/{holdId}/reservation)로
// 예약을 만든 뒤 같은 결제를 한다. 결제를 포기한 봇은 좌석 선택 완료를 부르지 않는다(선점은 시간이 지나 풀린다).
//
// 파도 w는 오픈 + w × WAVE_GAP초에 대기열에 들어간다(k6를 언제 켜든 같다). 그 전까지는 공연 상세를 보며 기다린다.
// 환경 변수: INPUT(input.json 경로), BASE(입구), PG(Fake PG), WAVES(파도 수), WAVE_SIZE(파도당 봇), WAVE_GAP(초),
//           POLL_MIN/POLL_MAX(순번 조회 간격 초), ABANDON(결제 포기 비율 0~1), RETRIES(좌석 재시도)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

// 봇마다 input.json을 따로 읽지 않게 공유 배열로 한 번만 올린다(봇 수천 명이면 메모리가 크게 줄어든다)
const meta = new SharedArray('meta', () => [JSON.parse(open(__ENV.INPUT || './input.json')).meta])[0];
const bots = new SharedArray('bots', () => JSON.parse(open(__ENV.INPUT || './input.json')).bots);
const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const PG = __ENV.PG || 'http://127.0.0.1:18090';
const WAVES = Number(__ENV.WAVES || 5);
const WAVE_SIZE = Number(__ENV.WAVE_SIZE || 200);
const WAVE_GAP = Number(__ENV.WAVE_GAP || 10);
const POLL_MIN = Number(__ENV.POLL_MIN || 5);
const POLL_MAX = Number(__ENV.POLL_MAX || 10);
const ABANDON = Number(__ENV.ABANDON || 0.05);
const RETRIES = Number(__ENV.RETRIES || 4);
// 앞 구역일수록 많이 노린다(A가 가장 인기)
const SECTION_WEIGHTS = meta.sections.map((_, i) => Math.max(1, meta.sections.length * 2 - i * 2));
const reserved = new Set(meta.reserved);

const loginFailed = new Counter('demo_login_failed');
const queueFailed = new Counter('demo_queue_failed');
const queued = new Counter('demo_queue_entered');
const admitted = new Counter('demo_admitted');
const holds = new Counter('demo_hold_success');
const conflicts = new Counter('demo_hold_conflict');
const gaveUp = new Counter('demo_gave_up');
const abandoned = new Counter('demo_payment_abandoned');
const payFailed = new Counter('demo_payment_failed');
const confirmed = new Counter('demo_confirmed');
const convertFailed = new Counter('demo_convert_failed');
const errors = new Counter('demo_system_errors');
const queueWait = new Trend('demo_queue_wait', true);
// 단계별 응답 시간(측정용, #476). 선점은 성공·충돌 모두, 좌석 선택 완료는 REDIS 모드에서만 남는다
const holdTime = new Trend('demo_hold_ms', true);
const convertTime = new Trend('demo_convert_ms', true);
const confirmTime = new Trend('demo_confirm_ms', true);

if (WAVES * WAVE_SIZE > bots.length) throw new Error(`봇 계정 부족: ${WAVES * WAVE_SIZE} > ${bots.length}`);

const scenarios = {};
for (let w = 0; w < WAVES; w++) {
  scenarios['wave' + w] = {
    executor: 'per-vu-iterations', exec: 'bot', vus: WAVE_SIZE, iterations: 1,
    // 로그인은 k6 시작 직후 파도마다 2초씩 나눠 한다. 대기열 진입 시각은 오픈 기준이다
    startTime: w * 2 + 's', maxDuration: '30m', env: { WAVE: String(w) },
  };
}
export const options = { scenarios, setupTimeout: '60s', summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'max'] };

function think() { sleep(0.3 + Math.random() * 0.7); }

function call(method, url, body, token, queueToken) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  if (queueToken) headers['X-Queue-Token'] = queueToken;
  if (body !== null && body !== undefined) headers['Content-Type'] = 'application/json';
  const r = http.request(method, url, body == null ? null : JSON.stringify(body), { headers, timeout: '60s' });
  let parsed = {};
  try { parsed = r.json() || {}; } catch (e) { parsed = {}; }
  if (r.status >= 500 || r.status === 0) errors.add(1, { url: method + ' ' + url.replace(/\d+/g, '{id}') });
  return { status: r.status, data: parsed.data, code: parsed.code, raw: parsed, ms: r.timings.duration };
}

function pickSection() {
  const total = SECTION_WEIGHTS.reduce((a, b) => a + b, 0);
  let x = Math.random() * total;
  for (let i = 0; i < SECTION_WEIGHTS.length; i++) { x -= SECTION_WEIGHTS[i]; if (x < 0) return i; }
  return 0;
}

function selectable(sid, section, layout, token, qt) {
  const s = call('GET', `${BASE}/api/seats/schedules/${sid}/status?section=${section}`, null, token, qt);
  if (s.status !== 200) return [];
  const bits = new Uint8Array(encoding.b64decode(s.data.selectableBitmap, 'std'));
  const out = [];
  for (let i = 0; i < layout.length; i++) {
    if (((bits[i >> 3] >> (i & 7)) & 1) === 1 && !reserved.has(layout[i].scheduleSeatId)) out.push(layout[i].scheduleSeatId);
  }
  return out;
}

function pay(rid, token) {
  const path = `${BASE}/api/reservations/${rid}`;
  const checkout = call('POST', path + '/payments/checkout', { pgProvider: 'TOSS' }, token);
  if (checkout.status !== 200) return 'checkout_' + (checkout.code || checkout.status);
  const auth = http.post(`${PG}/fake/toss/checkout`, JSON.stringify({ orderId: checkout.data.orderId, amount: checkout.data.amount }),
    { headers: { 'Content-Type': 'application/json' } });
  if (auth.status !== 200) return 'pg_auth_' + auth.status;
  let r = call('POST', path + '/payments/confirm', { orderId: checkout.data.orderId, pgAuthToken: auth.json().paymentKey }, token);
  confirmTime.add(r.ms);
  const deadline = Date.now() + 90000;
  while (r.status === 200 && r.data && r.data.status === 'PAYMENT_IN_PROGRESS' && Date.now() < deadline) {
    sleep(1); r = call('GET', path, null, token);
  }
  return r.status === 200 && r.data ? r.data.status : 'confirm_' + (r.code || r.status);
}

// REDIS 모드 선점을 예약으로 바꾼다(좌석 선택 완료). 같은 선점은 다시 불러도 같은 예약이라, 결과를 모르는 오류(5xx·연결 실패)만 한 번 다시 부른다.
// 409(좌석이 이미 팔림·선점 만료 등)·404면 예약을 만들지 못한 것이다
function convert(sid, holdId, token) {
  const path = `${BASE}/api/seats/schedules/${sid}/holds/${holdId}/reservation`;
  let r = call('POST', path, null, token);
  if (r.status >= 500 || r.status === 0) { sleep(1); r = call('POST', path, null, token); }
  convertTime.add(r.ms);
  if ((r.status === 200 || r.status === 201) && r.data && r.data.reservationId) return r.data.reservationId;
  convertFailed.add(1, { code: String(r.code || r.status) });
  return null;
}

export function bot() {
  // 파도마다 봇 계정 구간이 겹치지 않는다(파도 w의 i번째 = w × WAVE_SIZE + i)
  const bot = bots[Number(__ENV.WAVE) * WAVE_SIZE + exec.scenario.iterationInTest];
  const sid = meta.schedule_id;
  // prepare.py가 미리 받아 둔 토큰을 쓴다. 없을 때만(준비 중 로그인 실패) 여기서 로그인한다
  let token = bot.token;
  if (!token) {
    const login = call('POST', `${BASE}/api/auth/login`, { email: bot.email, password: meta.password });
    if (login.status !== 200) { loginFailed.add(1); return; }
    token = login.data.accessToken;
  }

  // 1) 내 차례(오픈 + 파도 × 간격 + 1초 안의 흩어짐)까지 공연 상세를 3초마다 다시 보며 기다린다
  const arrive = meta.open_at_epoch_ms + Number(__ENV.WAVE) * WAVE_GAP * 1000 + Math.random() * 1000;
  while (Date.now() < arrive) {
    call('GET', `${BASE}/api/performances/${meta.performance_id}`, null, token);
    sleep(Math.min(3, Math.max(0.05, (arrive - Date.now()) / 1000)));
  }

  // 2) 대기열 진입 → 순번 조회
  const started = Date.now();
  let q = call('POST', `${BASE}/api/waiting-rooms/${meta.room_id}/entries`, null, token);
  for (let i = 0; q.status === 409 && q.code === 'WAITING_ROOM_NOT_OPEN' && i < 20; i++) {
    sleep(0.5); q = call('POST', `${BASE}/api/waiting-rooms/${meta.room_id}/entries`, null, token);
  }
  if (q.status !== 200) { queueFailed.add(1, { stage: 'enter', code: String(q.code || q.status) }); return; }
  queued.add(1);
  while (q.status === 200 && q.data.status === 'WAITING') {
    sleep(POLL_MIN + Math.random() * (POLL_MAX - POLL_MIN));
    q = call('GET', `${BASE}/api/waiting-rooms/${meta.room_id}/entries/me`, null, token);
  }
  if (q.status !== 200 || !q.data.entryToken) { queueFailed.add(1, { stage: 'poll', code: String(q.code || q.status) }); return; }
  admitted.add(1); queueWait.add(Date.now() - started);
  const qt = q.data.entryToken;

  // 3) 좌석: 구역을 골라 배치·상태를 보고 빈 좌석을 누른다. 충돌하면 다시 고른다
  let section = pickSection();
  let layout = call('GET', `${BASE}/api/seats/schedules/${sid}/layout?section=${meta.sections[section]}`, null, token, qt).data;
  let hold = null;
  for (let attempt = 0; attempt <= RETRIES && !hold; attempt++) {
    let free = layout ? selectable(sid, meta.sections[section], layout.seats, token, qt) : [];
    // 이 구역이 다 팔리면 뒤 구역으로 옮긴다. 모든 구역이 비면 매진이라 포기한다
    for (let step = 1; free.length === 0 && step < meta.sections.length; step++) {
      section = (section + 1) % meta.sections.length;
      layout = call('GET', `${BASE}/api/seats/schedules/${sid}/layout?section=${meta.sections[section]}`, null, token, qt).data;
      free = layout ? selectable(sid, meta.sections[section], layout.seats, token, qt) : [];
    }
    if (free.length === 0) break;
    // 앞쪽 좌석일수록 먼저 노린다: 앞 1/3 안에서 고른다
    const seat = free[Math.floor(Math.random() * Math.max(1, Math.ceil(free.length / 3)))];
    think();
    const r = call('POST', `${BASE}/api/seats/schedules/${sid}/holds`, { scheduleSeatId: seat }, token, qt);
    holdTime.add(r.ms);
    if (r.status === 200 || r.status === 201) { hold = r.data; holds.add(1); }
    else if (r.status === 409) conflicts.add(1);
    else break;
  }
  if (!hold) { gaveUp.add(1); return; }

  // 4) 결제: 일부는 포기하고 떠난다. 실패하면 한 번 다시 결제한다
  think();
  if (Math.random() < ABANDON) { abandoned.add(1); return; }
  const rid = hold.holdId ? convert(sid, hold.holdId, token) : hold.reservationId;
  if (!rid) return;
  let result = pay(rid, token);
  if (result !== 'CONFIRMED') { payFailed.add(1); sleep(2); result = pay(rid, token); }
  if (result === 'CONFIRMED') confirmed.add(1);
}
