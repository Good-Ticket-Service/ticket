"""사용자 1명 기준 좌석 layout 비용 측정(#287). 부하 없이 layout을 몇 번 받아 전송 바이트·시간·파싱 시간을 남긴다.

python infra/integration/layout_probe.py --result script/integration/results/scenario2-<run_id> [--repeat 5] [--section A]

시나리오2 결과 폴더의 input.json에 있는 시험 계정(공개 로컬 시험 비밀번호)으로 로그인한다. 새 데이터를 만들지 않는다.
- 전송 바이트: 압축 해제 전 실제로 받은 본문 크기. identity·gzip을 각각 요청한다.
- 응답 헤더: Content-Length 유무와 Transfer-Encoding. k6가 본문을 받아 두지 않을 때 크기를 헤더로 알 수 있는지 확인용이다.
- 파싱 시간: Python json.loads 기준이라 브라우저와 다르다. 좌석 수 사이의 상대 비교로만 쓴다.
- --section: 그 구역 layout만 받는다(#397). 없으면 회차 전체다.
"""
import argparse
from datetime import datetime, timedelta, timezone
import gzip
import json
from pathlib import Path
import statistics
import time
import urllib.parse
import urllib.request

BASE = 'http://127.0.0.1:18080'
ENCODINGS = ('identity', 'gzip')

def opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))

def layout_path(schedule_id, section=None):
    """section이 있으면 구역 layout(#397). 구역 이름은 그대로 URL 인코딩한다(+·공백 포함)."""
    path = f'/api/seats/schedules/{schedule_id}/layout'
    return path if section is None else path + '?section=' + urllib.parse.quote(section, safe='')

def fetch(http, schedule_id, token, encoding, section=None):
    """layout을 한 번 받는다. urllib은 압축을 풀지 않으므로 read() 길이가 전송된 본문 크기다."""
    req = urllib.request.Request(BASE + layout_path(schedule_id, section),
        headers={'Accept': 'application/json', 'Accept-Encoding': encoding, 'Authorization': 'Bearer '+token})
    start = time.perf_counter()
    with http.open(req, timeout=60) as r:
        first = time.perf_counter()
        wire = r.read()
        done = time.perf_counter()
        headers = {k.lower(): v for k, v in r.headers.items()}
        status = r.status
    content_encoding = headers.get('content-encoding', 'identity')
    body = gzip.decompress(wire) if content_encoding == 'gzip' else wire
    parse_start = time.perf_counter()
    data = json.loads(body)['data']
    parsed = time.perf_counter()
    return {'status': status, 'requested_encoding': encoding, 'content_encoding': content_encoding,
            'content_length_header': int(headers['content-length']) if 'content-length' in headers else None,
            'transfer_encoding': headers.get('transfer-encoding'), 'etag': headers.get('etag'),
            'wire_bytes': len(wire), 'body_bytes': len(body),
            'first_byte_ms': (first-start)*1000, 'total_ms': (done-start)*1000, 'parse_ms': (parsed-parse_start)*1000,
            'schedule_id': data['scheduleId'], 'config_version': data['configVersion'], 'seats': data['seats']}

def summarize(rows):
    """encoding별 중앙값. 좌석 목록은 남기지 않는다."""
    out = {}
    for encoding in ENCODINGS:
        group = [r for r in rows if r['requested_encoding'] == encoding]
        if not group:
            continue
        last = group[-1]
        out[encoding] = {'samples': len(group), 'content_encoding': sorted({r['content_encoding'] for r in group}),
            'content_length_header_present': all(r['content_length_header'] is not None for r in group),
            'transfer_encoding': sorted({str(r['transfer_encoding']) for r in group}),
            'wire_bytes': last['wire_bytes'], 'body_bytes': last['body_bytes'],
            **{k+'_median': statistics.median(r[k] for r in group) for k in ('first_byte_ms', 'total_ms', 'parse_ms')}}
    return out

def probe(http, schedule_id, token, repeat, section=None):
    """identity·gzip을 번갈아 repeat번씩 받는다. 첫 응답의 좌석 순서로 비트 위치를 만들 수 있게 마지막 원본을 함께 돌려준다."""
    rows = []
    for _ in range(repeat):
        for encoding in ENCODINGS:
            rows.append(fetch(http, schedule_id, token, encoding, section))
    versions = {r['config_version'] for r in rows}
    if len(versions) != 1 or any(r['status'] != 200 for r in rows):
        raise RuntimeError('layout changed or failed during probe')
    last = rows[-1]
    result = {'schedule_id': schedule_id, 'section': section, 'config_version': last['config_version'], 'seat_count': len(last['seats']),
              'encodings': summarize(rows),
              'samples': [{k: v for k, v in r.items() if k != 'seats'} for r in rows]}
    return result, last['seats']

def seat_indexes(seats):
    """scheduleSeatId -> layout 순서(= status 비트 위치)."""
    return {s['scheduleSeatId']: i for i, s in enumerate(seats)}

def login(http, email, password):
    req = urllib.request.Request(BASE+'/api/auth/login', data=json.dumps({'email': email, 'password': password}).encode(),
                                 headers={'Content-Type': 'application/json', 'Accept': 'application/json'}, method='POST')
    with http.open(req, timeout=30) as r:
        return json.loads(r.read())['data']['accessToken']

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--result', required=True, help='scenario2 result folder that has input.json')
    p.add_argument('--repeat', type=int, default=5, choices=range(1, 21), metavar='1..20')
    p.add_argument('--section', help='section layout only (#397); default whole schedule')
    a = p.parse_args()
    folder = Path(a.result)
    data = json.loads((folder/'input.json').read_text(encoding='utf-8'))
    http = opener()
    token = login(http, data['viewers'][0]['email'], data['meta']['password'])
    result, _ = probe(http, data['meta']['schedule_id'], token, a.repeat, a.section)
    result['measured_at'] = datetime.now(timezone(timedelta(hours=9))).isoformat()
    out = folder/('layout-cost-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.json')
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'section': result['section'], 'seat_count': result['seat_count'], 'encodings': result['encodings']}, indent=2))
    print('saved: '+str(out))
