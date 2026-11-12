// 좌석 선점 경쟁(#313). 인기 좌석 몇 개에 선점을 몰고, 성공하면 잠시 쥐었다가 해제해 다른 사용자가 다시 선점하게 한다.
// 조회·결제는 하지 않는다. 가입·회차 준비·DB 검증은 Python(scenario5.py)이 하고 측정에서 뺀다.
// 부하 방식 두 가지
//   rate : 초당 rate회를 고르게 보낸다(constant-arrival-rate). 요청이 4ms 간격으로 와서 같은 좌석 잠금이 거의 겹치지 않았다(11/12, 1석 250/s도 잠금 대기 0)
//   burst: 매 wave_ms(1초) 경계에 burst명이 같은 순간에 보낸다(오픈 순간의 몰림). VU 하나가 wave마다 한 번 보낸다
import http from 'k6/http';
import {sleep} from 'k6';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import {Counter, Rate, Trend} from 'k6/metrics';

const inputPath = __ENV.S2_INPUT;
const meta = new SharedArray('meta', () => [JSON.parse(open(inputPath)).meta])[0];
const users = new SharedArray('users', () => JSON.parse(open(inputPath)).users);
const base = __ENV.BASE_URL;

const HOLD = 'POST /api/seats/schedules/{id}/holds';
const RELEASE = 'DELETE /api/seats/schedules/{id}/holds/{reservationId}';
// 선점 결과. 409 SEAT_ALREADY_TAKEN은 메시지로 나눈다. NOWAIT 잠금 실패는 코드는 같고 메시지만 다르다(#313 결정)
const OUTCOMES = ['created', 'existing', 'taken', 'lock_busy', 'purchase_limit', 'error'];
const attempts = new Counter('hold_attempts');
const counts = Object.fromEntries(OUTCOMES.map(o => [o, new Counter('hold_' + o)]));
const durations = Object.fromEntries(OUTCOMES.map(o => [o, new Trend('hold_duration_' + o, true)]));
const holdDuration = new Trend('hold_duration', true);
const releaseDuration = new Trend('release_duration', true);
const releaseOk = new Counter('release_ok');
const releaseFailed = new Counter('release_failed');
const errors = new Rate('system_errors');
// burst: wave 경계보다 늦게 보낸 정도. 앞 wave 응답이 늦어 VU가 늦으면 커진다(그만큼 동시성이 줄어든다)
const burstSkew = new Trend('burst_skew', true);
const burstLate = new Counter('burst_late');
const BURST = meta.mode === 'burst';
const LATE_MS = 100;

export const options = {
  setupTimeout: '30m',
  // 측정 끝에 쥐고 있는 좌석도 해제까지 마치게 한다(gracefulStop·maxDuration 여유). 끊긴 반복은 DB 대조에서 짝 없는 예약으로 드러난다
  scenarios: {hold: BURST
    ? {executor: 'per-vu-iterations', exec: 'burst', vus: meta.burst, iterations: meta.duration,
       maxDuration: (meta.duration + 60) + 's', gracefulStop: '30s', tags: {phase: 'measure', journey: 'hold'}}
    : {executor: 'constant-arrival-rate', exec: 'hold', rate: meta.rate, timeUnit: '1s',
       duration: meta.duration + 's', preAllocatedVUs: meta.vus, maxVUs: meta.max_vus,
       gracefulStop: '30s', tags: {phase: 'measure', journey: 'hold'}}},
  tags: {test_id: meta.run_id, lock_mode: meta.lock_mode},
  discardResponseBodies: false,
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error', 'error_code'],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  // 오류는 측정할 현상일 수 있어 멈추지 않는다(abortOnFail 없음). Python이 checks로 판정한다
  thresholds: Object.assign({system_errors: ['rate<0.01'], release_failed: ['count==0']},
    BURST ? {} : {dropped_iterations: ['count==0']}),
};

function classify(status, body) {
  if (status === 201) return 'created';
  if (status === 200) return 'existing';
  if (status === 409 && body.code === 'SEAT_ALREADY_TAKEN') return body.message === meta.lock_busy_message ? 'lock_busy' : 'taken';
  if (status === 409 && body.code === 'PURCHASE_LIMIT_EXCEEDED') return 'purchase_limit';
  return 'error';
}

function parse(r) {try {return r.json() || {};} catch (_) {return {};}}

export function setup() {
  // 로그인은 측정에서 뺀다. JWT는 k6 메모리에만 둔다
  console.info('S2_PRELOGIN started; authentication is excluded from measurement');
  const tokens = users.map(u => {
    const r = http.post(base + '/api/auth/login', JSON.stringify({email: u.email, password: meta.password}),
      {headers: {'Content-Type': 'application/json'}, tags: {name: 'POST /api/auth/login', phase: 'setup'}});
    const token = r.status === 200 ? (parse(r).data || {}).accessToken : null;
    if (!token) throw new Error('LOGIN_FAILED_' + r.status);
    return token;
  });
  console.info('S2_PRELOGIN completed');
  // burst의 첫 wave 시각. 모든 VU가 이 시각부터 wave_ms마다 함께 보낸다. VU가 뜰 여유로 2초 뒤 정각에 맞춘다
  const t0 = Math.ceil((Date.now() + 2000) / 1000) * 1000;
  return {tokens, t0};
}

export function hold(data) {
  const i = exec.scenario.iterationInTest;
  if (i === 0)
    console.log('S2_WINDOW ' + JSON.stringify({scenario: 'hold', start: exec.scenario.startTime / 1000,
      end: exec.scenario.startTime / 1000 + meta.duration}));
  attempt(i, data);
}

export function burst(data) {
  // VU slot(0..burst-1)이 wave k마다 한 번 보낸다. 시도 번호 i = k*burst + slot으로 사용자·좌석을 고른다
  const k = exec.vu.iterationInScenario;
  const slot = exec.vu.idInTest - 1;
  if (k === 0 && slot === 0)
    console.log('S2_WINDOW ' + JSON.stringify({scenario: 'hold', start: data.t0 / 1000, end: data.t0 / 1000 + meta.duration * meta.wave_ms / 1000}));
  const target = data.t0 + k * meta.wave_ms;
  const wait = target - Date.now();
  if (wait > 0) sleep(wait / 1000);
  const skew = Date.now() - target;
  burstSkew.add(skew);
  if (skew > LATE_MS) burstLate.add(1);
  attempt(k * meta.burst + slot, data);
}

function attempt(i, data) {
  // 사용자와 좌석을 돌아가며 고른다. 같은 사용자는 users.length/(초당 시도)초마다 다시 온다
  const index = i % users.length;
  const user = users[index];
  const seat = meta.hot_seats[i % meta.hot_seats.length];
  const token = data.tokens[index];
  const headers = {'Content-Type': 'application/json', Authorization: 'Bearer ' + token};
  const path = '/api/seats/schedules/' + meta.schedule_id + '/holds';
  attempts.add(1);
  const r = http.post(base + path, JSON.stringify({scheduleSeatId: seat}),
    {headers, timeout: '65s', tags: {name: HOLD}, responseCallback: http.expectedStatuses(200, 201, 409)});
  const body = parse(r);
  const outcome = classify(r.status, body);
  counts[outcome].add(1, outcome === 'error' ? {code: body.code || 'HTTP_' + r.status} : {});
  durations[outcome].add(r.timings.duration);
  holdDuration.add(r.timings.duration);
  errors.add(outcome === 'error' || outcome === 'purchase_limit');
  if (outcome !== 'created') return;

  // 성공한 사람은 hold_ms 동안 쥐었다가 해제한다. 해제한 좌석은 다른 사용자가 다시 선점한다
  const reservationId = (body.data || {}).reservationId;
  const evidence = {outcome: 'won', iteration: i, user_id: user.user_id, schedule_seat_id: seat,
    reservation_id: reservationId, hold_status: r.status, hold_ms: r.timings.duration, released: false};
  try {
    if (!reservationId) throw new Error('RESERVATION_ID_MISSING');
    sleep(meta.hold_ms / 1000);
    const d = http.del(base + path + '/' + reservationId, null,
      {headers, timeout: '65s', tags: {name: RELEASE}, responseCallback: http.expectedStatuses(204)});
    releaseDuration.add(d.timings.duration);
    evidence.release_status = d.status;
    evidence.released = d.status === 204;
    if (!evidence.released) evidence.release_code = parse(d).code || null;
  } catch (e) {
    evidence.error = String(e.message || 'SCRIPT_ERROR');
  } finally {
    (evidence.released ? releaseOk : releaseFailed).add(1);
    errors.add(!evidence.released);
    evidence.completed_epoch = Date.now() / 1000;
    console.log('S2_EVIDENCE ' + JSON.stringify(evidence));
  }
}

export function handleSummary(data) {
  return {[__ENV.S2_SUMMARY]: JSON.stringify(data, null, 2),
    stdout: 'S2_SUMMARY written; hold outcomes and seat ownership are verified by the Python runner.\n'};
}
