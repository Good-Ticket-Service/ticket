// 연결 확인 전용이다. 실제 예매 사용자 흐름·최대 처리량 시험과 구분한다.
import http from 'k6/http';
import {check} from 'k6';

export const options = {
  scenarios: {connectivity: {executor: 'constant-arrival-rate', rate: 1,
    timeUnit: '1s', duration: '10s', preAllocatedVUs: 5, maxVUs: 5}},
  thresholds: {checks: ['rate==1'], http_req_failed: ['rate==0'], dropped_iterations: ['count==0']},
};
export default function () {
  const r = http.get(`${__ENV.BASE_URL}/actuator/health`, {tags: {name: 'gateway_health'}, timeout: '5s'});
  check(r, {'Caddy → Gateway UP': x => x.status === 200 && x.json('status') === 'UP'});
}
