// 조회 중심 혼합 부하. 가입·공연 준비는 Python에서 수행하고 측정에서 제외한다.
import http from 'k6/http';
import {sleep} from 'k6';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import encoding from 'k6/encoding';
import {Counter, Rate, Trend} from 'k6/metrics';

const inputPath = __ENV.S2_INPUT;
const meta = new SharedArray('meta', () => [JSON.parse(open(inputPath)).meta])[0];
const buyers = new SharedArray('buyers', () => JSON.parse(open(inputPath)).buyers);
const viewers = new SharedArray('viewers', () => JSON.parse(open(inputPath)).viewers);
const probe = new SharedArray('probe-meta', () => [JSON.parse(open(inputPath)).probe||{}])[0];
const probeBuyers = new SharedArray('probe-buyers', () => JSON.parse(open(inputPath)).probe_buyers||[]);
const base = __ENV.BASE_URL;
const pg = __ENV.FAKE_PG_URL;
const starts = new Counter('journey');
const browseStarted = new Counter('browse_started');
const seatStarted = new Counter('seat_started');
const bookingStarted = new Counter('booking_started');
const bookingDone = new Counter('booking_confirmed');
const errors = new Rate('system_errors');
const successes = new Rate('journey_success');
const business = new Counter('business_rejections');
const duration = new Trend('journey_duration', true);
const bookingDuration = new Trend('booking_completion_duration', true);
const measuredHttp = new Trend('measured_http_duration', true);
const profileDurations = {browse:new Trend('browse_duration',true),seat:new Trend('seat_duration',true),booking:new Trend('booking_journey_duration',true)};
const reads = new Counter('seat_reads');
// split 모드에서 여정마다 받는 layout 수. 좌석 상태 조회(seat_reads)와 따로 센다
const layoutReads = new Counter('seat_layout_reads');
// legacy: 기존 좌석 조회(GET /api/seats/schedules/{id}). split: layout 1번 + status 조회(#271). input.json에 없으면 legacy
const seatApi = meta.seat_api||'legacy';
// 좌석 경계 측정 조건(#287). skip: layout 본문을 받아 두지 않는다(responseType none). 20,000석에서 여정마다 파싱한 layout을 들고 있다가 k6가 OOM으로 끝났다.
// 검증 기준(config_version·seat_count·좌석별 seat_index)은 Python이 측정 전에 layout을 한 번 받아 input.json에 넣는다.
const layoutBody = meta.layout_body||'parse';
// default면 Accept-Encoding을 보내지 않는다(기존 측정과 같음). identity·gzip은 모든 요청에 명시한다
const acceptEncoding = meta.accept_encoding||'default';
const layoutGzip = new Counter('seat_layout_gzip');
const layoutLengthMissing = new Counter('seat_layout_length_missing');
const layoutContentLength = new Trend('seat_layout_content_length');
const failures = new Counter('journey_failures');
const probeStarted = new Counter('probe_started');
const probeConfirmed = new Counter('probe_confirmed');
const probeSuccess = new Rate('probe_success');
const probeDuration = new Trend('probe_duration',true);
const probeHttpDuration = new Trend('probe_http_duration',true);
const faultProbeSuccess = new Rate('s4_probe_success');
const faultProbeDuration = new Trend('s4_probe_duration',true);
function failure(t,e) {failures.add(1,{...t,code:String(e.message||'SCRIPT_ERROR').split(':')[0]});}

function arrival(name, weight) {
  return {executor:'constant-arrival-rate', exec:name, rate:meta.rate*weight,
    timeUnit:'10s', duration:meta.duration+'s', startTime:meta.warmup ? (meta.warmup+5)+'s' : '0s',
    preAllocatedVUs:Math.max(1,Math.ceil(meta.vus*weight/10)),
    maxVUs:Math.max(1,Math.ceil(meta.max_vus*weight/10)), gracefulStop:'120s',
    tags:{phase:'measure',journey:name}};
}
let scenarios = meta.mode==='probe'?{}:{browse:arrival('browse',7),seat:arrival('seat',2),booking:arrival('booking',1)};
if (meta.mode==='probe'||meta.mode==='mixed_probe') scenarios.probe={executor:'constant-arrival-rate',exec:'booking',
  rate:meta.probe_rate,timeUnit:'1s',duration:meta.duration+'s',
  startTime:meta.warmup?(meta.warmup+5)+'s':'0s',preAllocatedVUs:meta.probe_vus,maxVUs:meta.probe_max_vus,
  gracefulStop:'120s',tags:{phase:'measure',journey:'booking',cohort:'B'}};
if (meta.warmup) scenarios.warmup = {executor:'constant-arrival-rate',exec:'browse',rate:5,timeUnit:'1s',
  duration:meta.warmup+'s',preAllocatedVUs:10,maxVUs:20,gracefulStop:'5s',tags:{phase:'warmup',journey:'browse'}};
if (meta.warmup&&meta.mode==='mixed_probe') scenarios.warmup_probe={...scenarios.warmup,tags:{phase:'warmup',journey:'browse',cohort:'B'}};
const faultServices=['user','performance','seat','booking','payment','fakepg'];
if (meta.mode==='db_fault') for (const service of faultServices) scenarios['dbprobe_'+service]={
  executor:'constant-arrival-rate',exec:'dbProbe',rate:1,timeUnit:'1s',duration:meta.duration+'s',
  startTime:meta.warmup?(meta.warmup+5)+'s':'0s',preAllocatedVUs:10,maxVUs:20,gracefulStop:'15s',
  tags:{phase:'diagnostic',journey:'probe',service}};
const thresholds={dropped_iterations:['count==0'],'system_errors{phase:measure}':['rate<0.01'],
  'journey_success{phase:measure}':['rate>=0.99']};
if (meta.mode!=='probe') Object.assign(thresholds,{browse_started:['count>0'],seat_started:['count>0'],booking_started:['count>0'],booking_confirmed:['count>0']});
if (meta.mode==='probe'||meta.mode==='mixed_probe') Object.assign(thresholds,{probe_started:['count>0'],probe_confirmed:['count>0'],probe_success:['rate>=0.99']});
if (meta.mode==='db_fault') {
  delete thresholds['system_errors{phase:measure}'];
  delete thresholds['journey_success{phase:measure}'];
  delete thresholds.booking_confirmed;
}
export const options = {
  setupTimeout:'30m',
  scenarios, tags:{test_id:meta.run_id,comparison_phase:meta.comparison_phase||'standalone',comparison_id:meta.comparison_id||'none'}, discardResponseBodies:false,
  systemTags:['status','method','name','scenario','expected_response','error','error_code','check'],
  summaryTrendStats:['avg','min','med','max','p(95)','p(99)'],
  thresholds,
};

function tags(journey) {return {journey,phase:exec.scenario.name.startsWith('warmup')?'warmup':'measure',
  cohort:meta.mode==='probe'||exec.scenario.name.includes('probe')?'B':'A'};}
function target(t) {return meta.mode==='mixed_probe'&&t.cohort==='B'?probe:meta;}
function requestedService(name) {
  if (name.includes('FakePG')) return 'fakepg';
  if (name.includes('/api/auth')||name.includes('/api/users')) return 'user';
  if (name.includes('/api/seats')) return 'seat';
  if (name.includes('/api/reservations')) return 'booking';
  return 'performance';
}
function faultEvidence(row) {if(meta.mode==='db_fault')console.log('S4_REQUEST '+JSON.stringify(row));}
function request(method,path,name,data,token,t,raw=false,responseType=undefined) {
  const scenarioName=t.phase==='setup'?'setup':exec.scenario.name;
  const iteration=t.phase==='setup'?0:exec.scenario.iterationInTest;
  const headers={'Content-Type':'application/json'};
  if(meta.mode==='db_fault')headers['X-Request-Id']='s4-'+meta.run_id+'-'+scenarioName+'-'+iteration+'-'+Date.now();
  if (token) headers.Authorization='Bearer '+token;
  if (acceptEncoding!=='default') headers['Accept-Encoding']=acceptEncoding;
  const started=Date.now()/1000;
  const r=http.request(method,(raw?pg:base)+path,data===null?null:JSON.stringify(data),
    {headers,timeout:'65s',tags:{...t,name},responseCallback:http.expectedStatuses(200,201,409),
     ...(responseType?{responseType}:{})});
  if (t.phase==='measure') measuredHttp.add(r.timings.duration,{...t,name});
  if (t.phase==='measure'&&t.cohort==='B') probeHttpDuration.add(r.timings.duration,{...t,name});
  if (name===LAYOUT) layoutHeaders(r,t);
  // 본문을 받아 두지 않은 응답은 상태 코드만 본다. 업무 오류 코드가 필요한 409는 layout 조회에서 나오지 않는다
  if (responseType==='none') {
    const ok=r.status===200;errors.add(!ok,t);
    if (!ok) throw new Error('HTTP_'+r.status+':BODY_NOT_READ');
    return r;
  }
  let body;
  try {body=r.json();} catch (_) {body={};}
  faultEvidence({start_epoch:started,end_epoch:Date.now()/1000,service:requestedService(name),
    name,method,status:r.status,code:body.code||null,duration_ms:r.timings.duration,
    request_id:headers['X-Request-Id']||null,scenario:scenarioName,iteration,
    ok:r.status===200||r.status===201,source:'journey',journey:t.journey,phase:t.phase});
  const expectedBusiness=r.status===409 && ['SEAT_ALREADY_TAKEN','PURCHASE_LIMIT_EXCEEDED'].includes(body.code);
  const bad=!(r.status===200||r.status===201||expectedBusiness);
  errors.add(bad,t);
  if (expectedBusiness) {business.add(1,{...t,code:body.code});throw new Error('BUSINESS:'+body.code);}
  if (bad) throw new Error('HTTP_'+r.status+':'+(body.code||'TRANSPORT_OR_CONTRACT'));
  if (!raw && (body.status!==r.status||!Object.prototype.hasOwnProperty.call(body,'data')))
    throw new Error('API_RESPONSE_CONTRACT');
  return raw?body:body.data;
}
function get(path,name,token,t) {return request('GET',path,name,null,token,t);}
function login(account,t) {
  const auth=request('POST','/api/auth/login','POST /api/auth/login',
    {email:account.email,password:meta.password},null,t);
  if (!auth.accessToken) throw new Error('LOGIN_TOKEN_MISSING');
  return auth.accessToken;
}
function think() {sleep(0.3+Math.random()*0.7);}
function listing(token,t) {
  const fixture=target(t);
  get('/api/performances?page=0&size=20','GET /api/performances',token,t);think();
  const detail=get('/api/performances/'+fixture.performance_id,'GET /api/performances/{id}',token,t);
  if (detail.id!==fixture.performance_id) throw new Error('PERFORMANCE_MISMATCH');
  get('/api/performances/'+fixture.performance_id+'/schedules','GET /api/performances/{id}/schedules',token,t);
}
const LAYOUT='GET /api/seats/schedules/{id}/layout';
// 압축 조건 확인용. 크기는 Content-Length가 있을 때만 안다(chunked면 없음). 전송 바이트는 Python의 layout-cost.json이 기준이다
function layoutHeaders(r,t) {
  if ((r.headers['Content-Encoding']||'').toLowerCase()==='gzip') layoutGzip.add(1,t);
  const length=r.headers['Content-Length'];
  if (length===undefined) layoutLengthMissing.add(1,t); else layoutContentLength.add(Number(length),t);
}
// split 모드 좌석 API(#271). layout은 여정마다 1번 받는다. 여정마다 새 사용자라 VU 단위로 재사용하면 서로 다른 사용자가
// layout을 함께 쓰는 셈이 되어 결과가 실제보다 좋게 나온다.
function seatLayout(scheduleId,token,t) {
  const path='/api/seats/schedules/'+scheduleId+'/layout';
  if (layoutBody==='skip') {
    // ETag는 configVersion이다. 본문 없이 같은 구성인지 확인한다
    const r=request('GET',path,LAYOUT,null,token,t,false,'none');
    if (r.headers['Etag']!=='"'+meta.config_version+'"') throw new Error('SEAT_LAYOUT_VERSION_MISMATCH');
    layoutReads.add(1,t);
    return {scheduleId,configVersion:meta.config_version,seatCount:meta.seat_count,seats:null};
  }
  const layout=get(path,LAYOUT,token,t);
  if (layout.scheduleId!==scheduleId) throw new Error('SEAT_SCHEDULE_MISMATCH');
  layoutReads.add(1,t);
  return {scheduleId,configVersion:layout.configVersion,seatCount:layout.seats.length,seats:layout.seats};
}
// status는 layout과 같은 구성 버전·좌석 수여야 비트 위치가 맞는다. 측정 중에는 좌석 준비가 없으므로 다르면 오류로 본다
function seatStatus(layout,token,t) {
  const status=get('/api/seats/schedules/'+layout.scheduleId+'/status','GET /api/seats/schedules/{id}/status',token,t);
  if (status.scheduleId!==layout.scheduleId) throw new Error('SEAT_SCHEDULE_MISMATCH');
  if (status.configVersion!==layout.configVersion||status.seatCount!==layout.seatCount)
    throw new Error('SEAT_LAYOUT_STATUS_MISMATCH');
  return new Uint8Array(encoding.b64decode(status.selectableBitmap,'std'));
}
// i번째 좌석 = (i/8)번째 바이트의 (i%8)번째 비트(가장 낮은 비트부터). docs/api.md 좌석 절
function selectableAt(bits,i) {return ((bits[i>>3]>>(i&7))&1)===1;}
function begin(kind,t) {
  if (t.phase==='measure' && exec.scenario.iterationInTest===0)
    console.log('S2_WINDOW '+JSON.stringify({scenario:exec.scenario.name,start:exec.scenario.startTime/1000,end:exec.scenario.startTime/1000+meta.duration}));
  starts.add(1,{...t,kind:'started'});
  if (t.phase==='measure') {
    if (t.cohort==='B'&&kind==='booking') probeStarted.add(1);
    else ({browse:browseStarted,seat:seatStarted,booking:bookingStarted})[kind].add(1);
  }
}
function finish(t,start,ok) {
  successes.add(ok,t);duration.add(Date.now()-start,t);
  if (t.phase==='measure') profileDurations[t.journey].add(Date.now()-start,t);
  if (t.phase==='measure'&&t.cohort==='B'&&t.journey==='booking') {probeDuration.add(Date.now()-start,t);probeSuccess.add(ok,t);}
  starts.add(1,{...t,kind:ok?'completed':'failed'});
}

export function browse() {
  const t=tags('browse'),start=Date.now();begin('browse',t);let ok=false;
  try {listing(null,t);think();ok=true;} catch (e) {failure(t,e);} finally {finish(t,start,ok);}
}
export function seat(data) {
  const t=tags('seat'),start=Date.now();begin('seat',t);let ok=false;
  try {
    const index=exec.scenario.iterationInTest%viewers.length;
    const account=viewers[index];
    const token=journeyToken(account,t,data,'viewers',index);listing(token,t);
    const repeat=3+(exec.scenario.iterationInTest%3);
    const layout=seatApi==='split'?seatLayout(meta.schedule_id,token,t):null;
    for (let i=0;i<repeat;i++) {
      if (layout) {
        seatStatus(layout,token,t);
      } else {
        const seats=get('/api/seats/schedules/'+meta.schedule_id,'GET /api/seats/schedules/{id}',token,t);
        if (seats.scheduleId!==meta.schedule_id) throw new Error('SEAT_SCHEDULE_MISMATCH');
      }
      reads.add(1,t);think();
    }
    ok=true;
  } catch (e) {failure(t,e);} finally {finish(t,start,ok);}
}
export function booking(data) {
  const t=tags('booking'),start=Date.now();begin('booking',t);let ok=false;
  const fixture=target(t);
  const account=(meta.mode==='mixed_probe'&&t.cohort==='B'?probeBuyers:buyers)[exec.scenario.iterationInTest];
  const evidence={cohort:t.cohort,iteration:exec.scenario.iterationInTest,at:new Date().toISOString(),outcome:'failed'};
  try {
    if (!account) throw new Error('BUYER_FIXTURE_EXHAUSTED');
    evidence.user_id=account.user_id;evidence.schedule_seat_id=account.schedule_seat_id;
    evidence.stage=meta.prelogin?'authentication':'login';
    const group=meta.mode==='mixed_probe'&&t.cohort==='B'?'probeBuyers':'buyers';
    const token=journeyToken(account,t,data,group,exec.scenario.iterationInTest);
    evidence.stage='browse';listing(token,t);think();
    evidence.stage='seat_query';
    // 두 모드 모두 배정 좌석을 선점한다. 좌석 선택 방식이 같아야 legacy와 split을 비교할 수 있고, 금융 검증도 배정 좌석을 전제로 한다
    if (seatApi==='split') {
      const layout=seatLayout(fixture.schedule_id,token,t);
      const bits=seatStatus(layout,token,t);
      reads.add(1,t);
      const index=layout.seats?layout.seats.findIndex(s=>s.scheduleSeatId===account.schedule_seat_id):account.seat_index;
      if (index<0||!selectableAt(bits,index)) throw new Error('ASSIGNED_SEAT_NOT_SELECTABLE');
    } else {
      const seats=get('/api/seats/schedules/'+fixture.schedule_id,'GET /api/seats/schedules/{id}',token,t);
      reads.add(1,t);
      const target=seats.seats.find(s=>s.scheduleSeatId===account.schedule_seat_id);
      if (!target||!target.selectable) throw new Error('ASSIGNED_SEAT_NOT_SELECTABLE');
    }
    evidence.stage='hold';
    const hold=request('POST','/api/seats/schedules/'+fixture.schedule_id+'/holds','POST /api/seats/schedules/{id}/holds',
      {scheduleSeatId:account.schedule_seat_id},token,t);
    evidence.reservation_id=hold.reservationId;
    const path='/api/reservations/'+hold.reservationId;
    evidence.stage='checkout';
    const checkout=request('POST',path+'/payments/checkout','POST /api/reservations/{id}/payments/checkout',{pgProvider:'TOSS'},token,t);
    evidence.order_id=checkout.orderId;evidence.amount=checkout.amount;
    if (checkout.amount!==10000) throw new Error('CHECKOUT_AMOUNT_MISMATCH');
    evidence.stage='pg_auth';
    const auth=request('POST','/fake/toss/checkout','POST FakePG checkout',{orderId:checkout.orderId,amount:checkout.amount},null,t,true);
    evidence.stage='confirm';
    let final=request('POST',path+'/payments/confirm','POST /api/reservations/{id}/payments/confirm',
      {orderId:checkout.orderId,pgAuthToken:auth.paymentKey},token,t);
    const deadline=Date.now()+90000;
    while (final.status==='PAYMENT_IN_PROGRESS'&&Date.now()<deadline) {
      sleep(1);final=get(path,'GET /api/reservations/{id}',token,t);
    }
    final=get(path,'GET /api/reservations/{id}',token,t);
    if (final.status!=='CONFIRMED'||final.orderId!==checkout.orderId||final.paymentAmount!==checkout.amount||
      final.scheduleId!==fixture.schedule_id||!final.scheduleSeatIds.includes(account.schedule_seat_id)||
      !final.tickets||final.tickets.length!==1||!final.tickets[0].ticketCode) throw new Error('FINAL_STATE_MISMATCH');
    if (t.cohort==='B') probeConfirmed.add(1,t);else bookingDone.add(1,t);
    bookingDuration.add(Date.now()-start,t);
    evidence.outcome='confirmed';ok=true;
  } catch (e) {
    failure(t,e);
    // 에러 문자열에는 상태/고정 코드만 포함하고 응답 본문·인증값을 기록하지 않는다.
    evidence.error=String(e.message||'SCRIPT_ERROR');
    if (evidence.error.startsWith('BUSINESS:')) evidence.outcome='business_rejected';
    if(meta.mode==='db_fault') {
      const writeStage=['hold','checkout','pg_auth','confirm'].includes(evidence.stage);
      evidence.client_classification=writeStage&&(/HTTP_(0|5\d\d|408)/.test(evidence.error)||!evidence.error.startsWith('HTTP_'))?'unknown':'explicit_failure';
    }
  } finally {
    evidence.completed_epoch=Date.now()/1000;
    evidence.duration_ms=Date.now()-start;
    console.log('S2_EVIDENCE '+JSON.stringify(evidence));finish(t,start,ok);
  }
}

export function setup() {
  if(meta.prelogin) {
    console.info('S2_PRELOGIN started; authentication is excluded from measurement');
    const t={phase:'setup',journey:'authentication',cohort:'A'};
    const tokens={viewers:[],buyers:[],probeBuyers:[]};
    for(const [group,accounts] of [['viewers',viewers],['buyers',buyers],['probeBuyers',probeBuyers]]) {
      for(const account of accounts) tokens[group].push(login(account,t));
    }
    console.info('S2_PRELOGIN completed; warmup/measurement starts next');
    // setup completes before warmup/measurement. Tokens stay in k6 memory, never result files.
    return {tokens};
  }
  if(meta.mode!=='db_fault') return {};
  const token=login(viewers[0],{phase:'setup',journey:'probe',cohort:'A'});
  return {probeToken:token}; // JWT는 메모리에만 유지한다.
}

function journeyToken(account,t,data,group,index) {
  if(!meta.prelogin) return login(account,t);
  const token=data&&data.tokens&&data.tokens[group]&&data.tokens[group][index];
  if(!token) throw new Error('PRELOGIN_TOKEN_MISSING');
  return token;
}

export function dbProbe(data) {
  const service=exec.scenario.name.replace('dbprobe_','');
  const endpoints={user:[base+'/api/users/me',200],performance:[base+'/api/performances/'+meta.performance_id,200],
    seat:[base+'/api/seats/schedules/'+meta.schedule_id,200],booking:[base+'/api/reservations/9000000000000000',404],
    payment:['http://payment-service:8084/actuator/health',200],fakepg:[pg+'/actuator/health',200]};
  const [url,expected]=endpoints[service];
  const start=Date.now()/1000;
  const r=http.get(url,{headers:{Authorization:'Bearer '+data.probeToken},timeout:'5s',
    tags:{name:'S4 probe '+service,phase:'diagnostic',service},responseCallback:http.expectedStatuses(expected)});
  let body={};try{body=r.json();}catch(_){}
  const ok=r.status===expected&&(service!=='booking'||body.code==='RESERVATION_NOT_FOUND');
  faultProbeSuccess.add(ok,{service});faultProbeDuration.add(r.timings.duration,{service});
  faultEvidence({start_epoch:start,end_epoch:Date.now()/1000,service,name:'S4 probe '+service,method:'GET',
    status:r.status,code:body.code||null,duration_ms:r.timings.duration,ok,source:'probe',phase:'diagnostic'});
}

export function handleSummary(data) {
  return { [__ENV.S2_SUMMARY]:JSON.stringify(data,null,2),
    stdout:'S2_SUMMARY written; booking and financial evidence are verified by the Python runner.\n'};
}
