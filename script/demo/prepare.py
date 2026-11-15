"""시연용 데이터 준비. 통합 환경(infra/integration)이 떠 있는 상태에서 실행한다.

만드는 것
- 계정: 시연 회원·시연 판매자(고정 이메일, 이미 있으면 재사용), 경쟁 봇 1명, 티켓팅 봇 N명
- 공연장(구역 여러 개) · 좌석 · 공연 · 가격 · 회차(좌석 준비 READY까지 대기) · 공개
- 시연 사용자용 예약 좌석(충돌용 1 + 성공용 2). 봇은 이 좌석을 고르지 않는다
- 회차 대기열(room). openAt = 지금 + --open-in초

결과: script/demo/input.json (k6 봇·경쟁 봇 입력). 공개 로컬 시험 값만 쓴다(infra/integration/compose-inputs.example).
"""
import argparse
import concurrent.futures
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
KST = timezone(timedelta(hours=9))
PASSWORD = 'Demo!2026pw'  # 공개 로컬 시연 계정 전용(가입 정책 8~15자)
DEMO_USER = 'demo-user@demo.local'
DEMO_SELLER = 'demo-seller@demo.local'
ROW_SEATS = 25


class Api:
    def __init__(self, base, pg):
        self.base, self.pg = base, pg
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, payload=None, token=None, expected=(200,), raw=False, headers=None):
        h = {'Accept': 'application/json', 'X-Request-Id': 'demo-' + uuid.uuid4().hex[:12]}
        if token:
            h['Authorization'] = 'Bearer ' + token
        if headers:
            h.update(headers)
        data = None if payload is None else json.dumps(payload).encode()
        if data is not None:
            h['Content-Type'] = 'application/json'
        url = (self.pg if raw else self.base) + path
        try:
            with self.http.open(urllib.request.Request(url, data=data, headers=h, method=method), timeout=60) as r:
                status, body = r.status, json.loads(r.read() or b'{}')
        except urllib.error.HTTPError as e:
            try:
                body = json.loads(e.read())
            except ValueError:
                body = {}
            raise RuntimeError(f'{method} {path} -> HTTP {e.code} {body.get("code")}') from None
        if status not in expected:
            raise RuntimeError(f'{method} {path} -> HTTP {status}')
        return body if raw else body.get('data')

    def login(self, email, password=PASSWORD):
        return self.call('POST', '/api/auth/login', {'email': email, 'password': password})['accessToken']

    def account(self, email, seller=False, name='시연 사용자'):
        """가입하고 로그인 토큰을 준다. 이미 가입돼 있으면 로그인만 한다."""
        try:
            self.call('POST', '/api/auth/' + ('seller/signup' if seller else 'signup'),
                      {'email': email, 'password': PASSWORD, 'name': name, 'phone': '010-1234-5678'}, expected=(201,))
        except RuntimeError as e:
            if 'HTTP 409' not in str(e):
                raise
        return self.login(email)


def inputs_file():
    values = {}
    for line in (ROOT / 'infra/integration/compose-inputs.example').read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.startswith('#'):
            k, v = line.split('=', 1)
            values[k.strip()] = v.strip()
    return values


def section_name(i):
    return chr(65 + i)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base', default='http://127.0.0.1:18080', help='통합 환경 입구(Caddy → Gateway)')
    p.add_argument('--pg', default='http://127.0.0.1:18090', help='통합 환경 Fake PG')
    p.add_argument('--sections', type=int, default=3, help='구역 수. A가 가장 앞(인기) 구역')
    p.add_argument('--section-seats', type=int, default=500, help='구역당 좌석(25의 배수)')
    p.add_argument('--bots', type=int, default=1000, help='티켓팅 봇 계정 수')
    p.add_argument('--open-in', type=int, default=180, help='대기열 오픈까지 남은 초(최소 70)')
    p.add_argument('--close-after', type=int, default=1800, help='오픈 뒤 대기열 종료까지 초')
    p.add_argument('--price', type=int, default=10000)
    a = p.parse_args()
    if a.section_seats % ROW_SEATS or a.open_in < 70:
        p.error('--section-seats는 25의 배수, --open-in은 70 이상이어야 한다(대기열 openAt은 생성 + 1분 이후)')

    env = inputs_file()
    api = Api(a.base, a.pg)
    run = datetime.now(KST).strftime('%m%d%H%M%S')
    print(f'[1/7] 관리자 로그인, 시연 판매자 계정', flush=True)
    master = api.login(env['MASTER_EMAIL'], env['MASTER_PASSWORD'])
    seller = api.account(DEMO_SELLER, seller=True, name='시연 판매자')

    print(f'[2/7] 공연장·좌석 {a.sections}구역 × {a.section_seats}석', flush=True)
    venue = api.call('POST', '/api/admin/venues', {'name': f'시연 공연장 {run}', 'address': '서울 시연로 1', 'region': 'SEOUL'},
                     master, expected=(201,))
    grades = api.call('POST', f'/api/admin/venues/{venue["id"]}/seat-grades', {'grades': [{'name': 'R'}]}, master,
                      expected=(201,), headers={'If-Match': str(venue['version'])})
    grade, version = grades['grades'][0]['id'], grades['venueVersion']
    seats = []
    for s in range(a.sections):
        for n in range(a.section_seats):
            row, num = divmod(n, ROW_SEATS)
            seats.append({'section': section_name(s), 'rowLabel': str(row + 1), 'seatNumber': num + 1,
                          'venueSeatGradeId': grade})
    for i in range(0, len(seats), 5000):
        version = api.call('POST', f'/api/admin/venues/{venue["id"]}/seats', {'seats': seats[i:i + 5000]}, master,
                           expected=(201,), headers={'If-Match': str(version)})['venueVersion']

    print('[3/7] 공연·가격·회차(좌석 준비 대기)·공개', flush=True)
    perf = api.call('POST', '/api/seller/performances', {
        'venueId': venue['id'], 'title': f'시연 콘서트 {run}', 'genre': 'CONCERT', 'ageRating': 'ALL', 'runningTime': 120,
        'description': '최종 발표 시연용 공연', 'posterUrl': 'https://example.com/demo-poster.png'}, seller, expected=(201,))
    prices = api.call('PUT', f'/api/seller/performances/{perf["id"]}/grade-prices',
                      {'gradePrices': [{'venueSeatGradeId': grade, 'price': a.price}]}, seller,
                      headers={'If-Match': str(perf['version'])})
    now = datetime.now(KST).replace(microsecond=0)
    schedule = api.call('POST', f'/api/seller/performances/{perf["id"]}/schedules', {
        'startAt': (now + timedelta(days=7)).isoformat(), 'bookingOpenAt': (now - timedelta(hours=1)).isoformat(),
        'bookingCloseAt': (now + timedelta(days=6)).isoformat()}, seller, expected=(201,))
    deadline = time.monotonic() + 180
    while schedule.get('seatSetupStatus') != 'READY':
        if schedule.get('seatSetupStatus') == 'FAILED' or time.monotonic() > deadline:
            raise RuntimeError('좌석 준비 실패/시간 초과: ' + str(schedule.get('seatSetupStatus')))
        time.sleep(1)
        schedule = api.call('GET', f'/api/seller/schedules/{schedule["id"]}', token=seller)
    api.call('POST', f'/api/seller/performances/{perf["id"]}/publication', token=seller,
             headers={'If-Match': str(prices['performanceVersion'])})
    sid = schedule['id']

    print('[4/7] 시연 회원·경쟁 봇 계정, 예약 좌석(대기열을 만들기 전에 좌석 배치를 읽는다)', flush=True)
    user = api.account(DEMO_USER, name='시연 회원')
    rival_email = f'demo-rival-{run}@demo.local'
    api.account(rival_email, name='경쟁 봇')
    layout = api.call('GET', f'/api/seats/schedules/{sid}/layout?section=A', token=user)['seats']
    # A구역 1열 10~12번: 충돌용 1, 성공용 2. 인기 구역 맨 앞이라 화면에서 자연스럽다
    pick = {(s['rowLabel'], s['seatNumber']): s for s in layout}
    decoy, ok1, ok2 = pick[('1', 10)], pick[('1', 11)], pick[('1', 12)]
    reserved = [decoy['scheduleSeatId'], ok1['scheduleSeatId'], ok2['scheduleSeatId']]

    print(f'[5/7] 티켓팅 봇 계정 {a.bots}명', flush=True)
    bots = [f'demo-bot-{run}-{i}@demo.local' for i in range(a.bots)]

    def signup(email):
        api.call('POST', '/api/auth/signup', {'email': email, 'password': PASSWORD, 'name': '티켓팅 봇',
                                              'phone': '010-1234-5678'}, expected=(201,))
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        for i, _ in enumerate(pool.map(signup, bots), 1):
            if i % 200 == 0:
                print(f'      {i}/{a.bots}', flush=True)

    # 봇 로그인을 시연 전에 끝내 둔다. 시연 중 수천 명이 동시에 로그인하면 회원 서비스 DB 커넥션 풀(통합 환경 10)이
    # 바닥나 로그인이 30초 시간 초과로 실패한다. 동시 8명으로 나눠 받고 토큰(유효 1시간)을 input.json에 저장한다
    print(f'[5/7] 봇 로그인 {a.bots}명(동시 8명)', flush=True)

    def login(email):
        for attempt in range(3):
            try:
                return api.login(email)
            except RuntimeError:
                time.sleep(1 + attempt)
        return None
    tokens = []
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        for i, token in enumerate(pool.map(login, bots), 1):
            tokens.append(token)
            if i % 500 == 0:
                print(f'      {i}/{a.bots}', flush=True)
    failed = sum(t is None for t in tokens)
    if failed:
        print(f'      로그인 실패 {failed}명(이 봇은 시연 중에 다시 로그인한다)', flush=True)
    tokens_at = time.time()

    print('[6/7] 대기열(room) 생성', flush=True)
    open_at = (datetime.now(KST) + timedelta(seconds=a.open_in)).replace(microsecond=0)
    room = api.call('POST', '/api/admin/waiting-rooms', {
        'scopes': [f'schedule:{sid}'], 'openAt': open_at.isoformat(),
        'closeAt': (open_at + timedelta(seconds=a.close_after)).isoformat()}, master, expected=(201,))

    print('[7/7] input.json 저장', flush=True)
    data = {'meta': {'run': run, 'performance_id': perf['id'], 'schedule_id': sid, 'room_id': room['roomId'],
                     'open_at_epoch_ms': int(open_at.timestamp() * 1000), 'open_at': open_at.isoformat(),
                     'sections': [section_name(s) for s in range(a.sections)], 'reserved': reserved,
                     'price': a.price, 'password': PASSWORD,
                     'tokens_expire_epoch_ms': int((tokens_at + 3600) * 1000)},
            'rival': {'email': rival_email, 'seat_id': decoy['scheduleSeatId']},
            'bots': [{'email': e, 'token': t} for e, t in zip(bots, tokens)]}
    (HERE / 'input.json').write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding='utf-8')

    def label(s):
        return f'{s["section"]}구역 {s["rowLabel"]}열 {s["seatNumber"]}번'
    print('\n==================== 준비 완료 ====================')
    print(f'공연: 시연 콘서트 {run} (performanceId={perf["id"]}, scheduleId={sid})')
    print(f'대기열 오픈: {open_at:%H:%M:%S} (roomId={room["roomId"]}) — 이 시각 전에 봇을 시작한다')
    print(f'봇 로그인 토큰 만료: {datetime.fromtimestamp(tokens_at + 3600, KST):%H:%M:%S} — 그 전에 시연을 끝낸다')
    print(f'좌석: {a.sections}구역 × {a.section_seats} = {len(seats)}석, 가격 {a.price:,}원')
    print('로그인 계정 (비밀번호 모두 아래 값)')
    print(f'  회원   {DEMO_USER} / {PASSWORD}')
    print(f'  판매자 {DEMO_SELLER} / {PASSWORD}')
    print(f'  관리자 {env["MASTER_EMAIL"]} / {env["MASTER_PASSWORD"]}')
    print(f'시연 좌석: 충돌용 {label(decoy)}, 성공용 {label(ok1)}·{label(ok2)}')


if __name__ == '__main__':
    main()
