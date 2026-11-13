"""좌석 경계 측정 조건(#287)·구역 모드(#397)에 대한 모의 검증. 실제 서버·Docker·k6를 부르지 않는다."""
import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import layout_probe
import scenario2
from argparse import Namespace
from scenario2 import MixedScenario, cpu_summary, encoding_as_requested, layout_measurements, option_error, zone_fixture

SEATS = [{'scheduleSeatId': 501}, {'scheduleSeatId': 502}, {'scheduleSeatId': 503}]
BODY = json.dumps({'status': 200, 'data': {'scheduleId': 7, 'configVersion': 3, 'seats': SEATS}}).encode()

class FakeResponse(io.BytesIO):
    def __init__(self, body, headers):
        super().__init__(body)
        self.status = 200
        self.headers = headers
    def __enter__(self): return self
    def __exit__(self, *a): self.close()

class FakeHttp:
    """compressed=True면 gzip 요청에 gzip으로 답한다. 큰 JSON처럼 Content-Length 없이 chunked로 답한다."""
    def __init__(self, compressed):
        self.compressed = compressed
        self.requests = []
    def open(self, req, timeout=None):
        self.requests.append(req)
        if self.compressed and req.get_header('Accept-encoding') == 'gzip':
            return FakeResponse(gzip.compress(BODY), {'Content-Encoding': 'gzip', 'Transfer-Encoding': 'chunked', 'ETag': '"3"'})
        return FakeResponse(BODY, {'Transfer-Encoding': 'chunked', 'ETag': '"3"'})

class LayoutProbeTests(unittest.TestCase):
    def test_records_wire_and_body_bytes_per_encoding(self):
        result, seats = layout_probe.probe(FakeHttp(compressed=True), 7, 'token', repeat=2)
        self.assertEqual((result['seat_count'], result['config_version']), (3, 3))
        identity, gz = result['encodings']['identity'], result['encodings']['gzip']
        self.assertEqual((identity['wire_bytes'], identity['body_bytes']), (len(BODY), len(BODY)))
        self.assertEqual((gz['wire_bytes'], gz['body_bytes']), (len(gzip.compress(BODY)), len(BODY)))
        self.assertEqual((identity['content_encoding'], gz['content_encoding']), (['identity'], ['gzip']))
        self.assertFalse(gz['content_length_header_present'])
        self.assertEqual(gz['transfer_encoding'], ['chunked'])
        self.assertEqual(len(result['samples']), 4)
        self.assertNotIn('seats', result['samples'][0])

    def test_sends_requested_accept_encoding(self):
        http = FakeHttp(compressed=False)
        layout_probe.probe(http, 7, 'token', repeat=1)
        self.assertEqual([r.get_header('Accept-encoding') for r in http.requests], ['identity', 'gzip'])

    def test_section_is_url_encoded_and_recorded(self):
        # 구역 이름은 그대로 인코딩한다. +를 그대로 두면 서버가 공백으로 읽는다(#397)
        http = FakeHttp(compressed=False)
        result, _ = layout_probe.probe(http, 7, 'token', repeat=1, section='1층 C+')
        self.assertTrue(all(r.full_url.endswith('/layout?section=1%EC%B8%B5%20C%2B') for r in http.requests))
        self.assertEqual(result['section'], '1층 C+')

    def test_whole_layout_has_no_query(self):
        http = FakeHttp(compressed=False)
        result, _ = layout_probe.probe(http, 7, 'token', repeat=1)
        self.assertTrue(all(r.full_url.endswith('/api/seats/schedules/7/layout') for r in http.requests))
        self.assertIsNone(result['section'])

    def test_seat_index_is_layout_order(self):
        self.assertEqual(layout_probe.seat_indexes(SEATS), {501: 0, 502: 1, 503: 2})

def scenario(layout_body='skip', accept_encoding='identity'):
    obj = object.__new__(MixedScenario)
    obj.report = {'workload': {'layout_body': layout_body, 'accept_encoding': accept_encoding}, 'checks': {}}
    obj.out = Path(tempfile.mkdtemp())
    obj.save = Mock()
    return obj

class PrepareLayoutTests(unittest.TestCase):
    def prepare(self, obj, compressed=False):
        obj.http = FakeHttp(compressed)
        buyers = [{'schedule_seat_id': 503}, {'schedule_seat_id': 501}]
        with patch('builtins.print'):
            meta = obj.prepare_layout(7, 'token', buyers)
        return meta, buyers

    def test_default_options_do_not_touch_layout(self):
        obj = scenario('parse', 'default')
        obj.http = Mock()
        self.assertEqual(obj.prepare_layout(7, 'token', [{'schedule_seat_id': 501}]), {})
        obj.http.open.assert_not_called()
        self.assertNotIn('layout', obj.report)

    def test_skip_gives_k6_version_count_and_seat_index(self):
        obj = scenario()
        meta, buyers = self.prepare(obj)
        self.assertEqual(meta, {'config_version': 3, 'seat_count': 3})
        self.assertEqual([b['seat_index'] for b in buyers], [2, 0])
        self.assertTrue((obj.out/'layout-cost.json').exists())

    def test_records_what_each_encoding_returned_without_stopping(self):
        # layout은 강한 ETag라 Tomcat 압축이 걸리지 않는다(TS-9). gzip 요청에 identity가 와도 준비를 멈추지 않고 기록만 한다
        obj = scenario(accept_encoding='gzip')
        meta, _ = self.prepare(obj, compressed=False)
        self.assertEqual(meta['seat_count'], 3)
        self.assertEqual(obj.report['layout']['encodings']['gzip']['content_encoding'], ['identity'])

class LayoutResultTests(unittest.TestCase):
    def summary(self, reads, gz, missing):
        m = {'seat_layout_reads': {'values': {'count': reads}}, 'seat_layout_gzip': {'values': {'count': gz}},
             'seat_layout_length_missing': {'values': {'count': missing}}}
        value = lambda name, key, default=0: m.get(name, {}).get('values', {}).get(key, default)
        return layout_measurements(value, m)

    def test_gzip_requires_every_layout_compressed(self):
        self.assertTrue(encoding_as_requested('gzip', self.summary(10, 10, 10)))
        self.assertFalse(encoding_as_requested('gzip', self.summary(10, 9, 10)))
        self.assertFalse(encoding_as_requested('gzip', self.summary(0, 0, 0)))

    def test_identity_requires_no_compressed_layout(self):
        self.assertTrue(encoding_as_requested('identity', self.summary(10, 0, 10)))
        self.assertFalse(encoding_as_requested('identity', self.summary(10, 1, 10)))

    def test_cpu_summary_uses_measure_window_and_sums_containers(self):
        data = {'data': {'result': [
            {'metric': {'service': 'reservation-service', 'container': 'a'}, 'values': [[100, '0.5'], [105, '1.5'], [200, '9']]},
            {'metric': {'service': 'reservation-service', 'container': 'b'}, 'values': [[105, '0.5']]},
            {'metric': {'service': 'postgres', 'container': 'c'}, 'values': [[100, '1']]}]}}
        self.assertEqual(cpu_summary(data, 100, 110), {'reservation-service': {'avg': 1.25, 'max': 2.0, 'samples': 2}})

class ZoneFixtureTests(unittest.TestCase):
    """구역 모드 준비값(#397). 구역 순서와 구역 안 순서는 서버 규칙(가장 작은 좌석 id 순, 구역 좌석 id 오름차순)과 같아야 한다."""
    # 응답 순서가 id 순이 아니고, B·C 구역이 id 순서에서 섞여 있다. 숫자 같은 이름("1")도 순서를 지킨다
    SEATS = [{'scheduleSeatId': i, 'section': s} for i, s in
             [(9, 'B'), (1, 'A'), (6, '1'), (5, 'B'), (2, 'A'), (7, 'B'), (8, '1')]]

    def test_sections_in_first_seat_order_with_counts(self):
        self.assertEqual([{'section': 'A', 'seat_count': 2}, {'section': 'B', 'seat_count': 3}, {'section': '1', 'seat_count': 2}],
                         zone_fixture(self.SEATS, []))

    def test_buyer_gets_section_and_index_within_section(self):
        buyers = [{'schedule_seat_id': 7}, {'schedule_seat_id': 9}, {'schedule_seat_id': 8}, {'schedule_seat_id': 1}]
        zone_fixture(self.SEATS, buyers)
        self.assertEqual([('B', 1), ('B', 2), ('1', 1), ('A', 0)], [(b['section'], b['section_index']) for b in buyers])

class PrepareZoneTests(unittest.TestCase):
    SEATS = {'scheduleId': 7, 'configVersion': 3,
             'seats': [{'scheduleSeatId': 501, 'section': 'A'}, {'scheduleSeatId': 502, 'section': 'B'}, {'scheduleSeatId': 503, 'section': 'B'}]}

    def prepare(self, obj, visits=1):
        obj.report['workload'].update(seat_api='zone', zone_visits=visits, status_reads=None)
        obj.http = FakeHttp(compressed=False)
        buyers = [{'schedule_seat_id': 503}]
        with patch('builtins.print'):
            meta = obj.prepare_zone(7, 'token', self.SEATS, buyers)
        return meta, buyers

    def test_gives_k6_sections_version_and_buyer_section(self):
        meta, buyers = self.prepare(scenario('parse', 'default'))
        self.assertEqual(meta, {'config_version': 3, 'sections': [{'section': 'A', 'seat_count': 1}, {'section': 'B', 'seat_count': 2}],
                                'zone_visits': 1, 'status_reads': None})
        self.assertEqual((buyers[0]['section'], buyers[0]['section_index']), ('B', 1))

    def test_default_options_do_not_probe_section_layout(self):
        obj = scenario('parse', 'default')
        self.prepare(obj)
        self.assertEqual(obj.http.requests, [])
        self.assertNotIn('layout', obj.report['zone'])

    def test_boundary_conditions_probe_first_section_layout(self):
        obj = scenario()
        self.prepare(obj)
        self.assertTrue(all(r.full_url.endswith('/layout?section=A') for r in obj.http.requests))
        self.assertEqual(obj.report['zone']['layout']['section'], 'A')
        self.assertTrue((obj.out/'layout-cost-section.json').exists())

    def test_more_visits_than_sections_fails(self):
        with self.assertRaises(scenario2.Failure):
            self.prepare(scenario('parse', 'default'), visits=3)

class OptionErrorTests(unittest.TestCase):
    """좌석 조회 옵션 조합(#287 skip, #397 zone)."""
    def args(self, **kw):
        base = dict(seat_api='legacy', layout_body='parse', venue_layout='single', zone_visits=1, status_reads=None)
        return Namespace(**{**base, **kw})

    def test_defaults_and_valid_zone_pass(self):
        self.assertIsNone(option_error(self.args()))
        self.assertIsNone(option_error(self.args(seat_api='split', layout_body='skip')))
        self.assertIsNone(option_error(self.args(seat_api='zone', venue_layout='sections', layout_body='skip', zone_visits=3, status_reads=3)))

    def test_zone_needs_sections_layout(self):
        self.assertIn('venue-layout sections', option_error(self.args(seat_api='zone')))

    def test_zone_options_need_zone(self):
        self.assertIn('need --seat-api zone', option_error(self.args(seat_api='split', zone_visits=2)))
        self.assertIn('need --seat-api zone', option_error(self.args(seat_api='split', status_reads=3)))

    def test_skip_needs_split_or_zone(self):
        self.assertIn('skip', option_error(self.args(layout_body='skip')))

    def test_ranges(self):
        zone = dict(seat_api='zone', venue_layout='sections')
        self.assertIn('zone-visits', option_error(self.args(**zone, zone_visits=0)))
        self.assertIn('status-reads', option_error(self.args(**zone, status_reads=21)))

if __name__ == '__main__':
    unittest.main()
