"""경쟁 봇: 시연 사용자의 충돌용 좌석을 먼저 선점한다.

오픈 직후 대기열에 들어가 입장 토큰을 받아 둔다. 그다음 두 방식 중 하나로 선점한다.
- 자동(기본): 시연 회원이 대기열을 통과해 좌석 화면으로 넘어간 순간부터 --delay초 뒤에 선점한다.
  시연 회원은 좌석 화면이 열리면 바로 충돌용 좌석을 고르고, (delay ~ delay+3)초 사이에 선점 버튼을 누른다.
- 수동(--manual): cmd 창에서 Enter를 누르는 순간 선점한다.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare import Api, DEMO_USER, PASSWORD  # noqa: E402

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--base', default='http://127.0.0.1:18080')
p.add_argument('--delay', type=float, default=5.0, help='시연 회원 입장 뒤 선점까지 초(자동). 부하 중 좌석 화면이 늦게 열리는 것을 감안한다')
p.add_argument('--manual', action='store_true', help='Enter로 선점')
a = p.parse_args()

data = json.loads((Path(__file__).resolve().parent / 'input.json').read_text(encoding='utf-8'))
meta, rival = data['meta'], data['rival']
api = Api(a.base, '')
room = meta['room_id']
token = api.login(rival['email'], meta['password'])

wait = meta['open_at_epoch_ms'] / 1000 - time.time()
if wait > 0:
    print(f'오픈({meta["open_at"]})까지 {wait:.0f}초 대기', flush=True)
    time.sleep(wait + 0.2)
while True:
    try:
        q = api.call('POST', f'/api/waiting-rooms/{room}/entries', token=token)
        break
    except RuntimeError as e:
        if 'WAITING_ROOM_NOT_OPEN' not in str(e):
            raise
        time.sleep(0.5)
while q['status'] == 'WAITING':
    print(f'  경쟁 봇 대기 중: 앞에 {q["aheadCount"]}명', flush=True)
    time.sleep(2)
    q = api.call('GET', f'/api/waiting-rooms/{room}/entries/me', token=token)
print(f'경쟁 봇 입장 완료({datetime.now():%H:%M:%S}). 입장 토큰은 10분 유효.', flush=True)

if a.manual:
    input('시연 사용자가 충돌용 좌석을 고르면 Enter → 즉시 선점 ')
else:
    # 시연 회원의 대기열 상태를 본다. 줄에 서기 전에는 NOT_IN_QUEUE(404)라 계속 기다린다
    user = api.login(DEMO_USER, PASSWORD)
    print('시연 회원이 대기열을 통과하기를 기다리는 중...', flush=True)
    while True:
        try:
            if api.call('GET', f'/api/waiting-rooms/{room}/entries/me', token=user)['status'] == 'ENTERED':
                break
        except RuntimeError as e:
            if 'NOT_IN_QUEUE' not in str(e):
                raise
        time.sleep(0.5)
    print(f'시연 회원 입장({datetime.now():%H:%M:%S}). {a.delay:.0f}초 뒤 충돌용 좌석을 선점한다.', flush=True)
    time.sleep(a.delay)

r = api.call('POST', f'/api/seats/schedules/{meta["schedule_id"]}/holds', {'scheduleSeatId': rival['seat_id']},
             token=token, expected=(200, 201), headers={'X-Queue-Token': q['entryToken']})
print(f'선점 완료({datetime.now():%H:%M:%S}): reservationId={r["reservationId"]}. 3초 안에 선점 버튼을 누르면 충돌 메시지가 뜬다.',
      flush=True)
