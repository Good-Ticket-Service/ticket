"""S5 좌석 선점 경쟁(#313)의 판정 함수에 대한 모의 검증. 실제 서버·Docker·k6를 부르지 않는다."""
from pathlib import Path
import unittest
import scenario5
from scenario5 import (LOCK_BUSY_MESSAGE, base_checks, base_measurements, base_seat_shortage, classify, collector_sources,
                       sample_summary, seat_timeline, series_summary)

def row(rid, seat, status, created, updated):
    return {'reservation_id': rid, 'user_id': rid, 'seat': seat, 'status': status, 'created': created, 'updated': updated}

class ClassifyTests(unittest.TestCase):
    def test_outcomes(self):
        self.assertEqual(classify(201, {}), 'created')
        self.assertEqual(classify(200, {}), 'existing')
        self.assertEqual(classify(409, {'code': 'SEAT_ALREADY_TAKEN', 'message': '이미 다른 예약이 선점했거나 판매된 좌석입니다.'}), 'taken')
        self.assertEqual(classify(409, {'code': 'SEAT_ALREADY_TAKEN', 'message': LOCK_BUSY_MESSAGE}), 'lock_busy')
        self.assertEqual(classify(409, {'code': 'PURCHASE_LIMIT_EXCEEDED'}), 'purchase_limit')

    def test_other_responses_are_errors(self):
        # 교착(40P01)은 409로 바뀌지 않고 500이다. 판매 검증 거절(400)·시간 초과(0)도 오류로 센다
        for status, body in [(500, {'code': 'INTERNAL_SERVER_ERROR'}), (400, {'code': 'SALE_NOT_OPEN'}), (0, {}),
                             (409, {'code': 'SEAT_CONFIG_VERSION_MISMATCH'})]:
            self.assertEqual(classify(status, body), 'error', status)

    def test_k6_uses_same_rules(self):
        # k6는 메시지를 input.json으로 받는다. 분류 규칙 문자열이 Python과 같은지 본다
        js = (Path(scenario5.__file__).parent/'k6'/'scenario5.js').read_text(encoding='utf-8')
        self.assertIn("body.message === meta.lock_busy_message ? 'lock_busy' : 'taken'", js)
        self.assertIn("body.code === 'PURCHASE_LIMIT_EXCEEDED') return 'purchase_limit'", js)
        for outcome in scenario5.OUTCOMES:
            self.assertIn("'"+outcome+"'", js)

    def test_burst_mode_fires_waves_together(self):
        # burst는 VU마다 wave당 한 번, 같은 경계 시각(t0 + k*wave_ms)에 보낸다. 고른 부하(rate)로는 잠금 경합이 생기지 않았다
        js = (Path(scenario5.__file__).parent/'k6'/'scenario5.js').read_text(encoding='utf-8')
        self.assertIn("executor: 'per-vu-iterations', exec: 'burst', vus: meta.burst, iterations: meta.duration", js)
        self.assertIn('const target = data.t0 + k * meta.wave_ms;', js)
        self.assertIn('attempt(k * meta.burst + slot, data);', js)

class SeatTimelineTests(unittest.TestCase):
    def test_sequential_holds_pass(self):
        rows = [row(1, 10, 'RELEASED', 100.0, 100.5), row(2, 10, 'RELEASED', 100.5, 101.0), row(3, 11, 'RELEASED', 100.2, 100.7)]
        t = seat_timeline(rows, [10, 11])
        self.assertEqual(t['overlaps'], [])
        self.assertEqual(t['active_holds'], {})
        self.assertEqual(t['holds_per_seat'], {'10': 2, '11': 1})

    def test_hold_before_previous_release_is_overlap(self):
        rows = [row(1, 10, 'RELEASED', 100.0, 100.5), row(2, 10, 'RELEASED', 100.4, 101.0)]
        self.assertEqual(seat_timeline(rows, [10])['overlaps'], [{'seat': 10, 'previous': 1, 'next': 2}])

    def test_unfinished_previous_is_overlap_and_active_hold(self):
        rows = [row(1, 10, 'HOLD', 100.0, 100.0), row(2, 10, 'RELEASED', 101.0, 101.5)]
        t = seat_timeline(rows, [10])
        self.assertEqual(len(t['overlaps']), 1)
        self.assertEqual(t['active_holds'], {'10': [1]})

    def test_other_seats_are_ignored(self):
        # 기능 확인 좌석은 인기 좌석 판정에 넣지 않는다
        self.assertEqual(seat_timeline([row(1, 99, 'HOLD', 1.0, 1.0)], [10])['holds_per_seat'], {'10': 0})

class SummaryTests(unittest.TestCase):
    def test_series_summary_sums_series_per_timestamp(self):
        data = {'data': {'result': [{'values': [[10, '1'], [15, '3'], [99, '50']]}, {'values': [[10, '2'], [15, '0']]}]}}
        self.assertEqual(series_summary(data, 10, 20), {'avg': 3.0, 'max': 3.0, 'samples': 2})

    def test_series_summary_without_samples_is_none_not_zero(self):
        self.assertIsNone(series_summary({'data': {'result': []}}, 0, 10))

    def test_sample_summary_window(self):
        samples = [{'t': 1, 'lock': 9, 'blocked': 9, 'active': 9}, {'t': 10, 'lock': 2, 'blocked': 1, 'active': 4},
                   {'t': 11, 'lock': 0, 'blocked': 0, 'active': 2}]
        s = sample_summary(samples, 10, 20)
        self.assertEqual(s['samples'], 2)
        self.assertEqual(s['lock'], {'avg': 1.0, 'max': 2, 'nonzero_ratio': 0.5})
        self.assertIsNone(sample_summary(samples, 100, 200))

    def test_collector_sources(self):
        text = ('integration_source_up{source="docker"} 1.0\n'
                'integration_source_up{source="postgres-diagnostics"} 1.0\n'
                'integration_source_up{source="postgres-statements"} 0.0\n')
        self.assertEqual(collector_sources(text), {'docker': True, 'postgres-diagnostics': True, 'postgres-statements': False})


class BaseLoadTests(unittest.TestCase):
    """다른 좌석 선점(--base-rate, #390). 인기 좌석 경쟁이 다른 좌석 선점을 늦추는지 따로 센다."""
    def summary(self, created=500, taken=0, release_ok=500, release_failed=0, attempts=500):
        counts = {'base_hold_attempts': attempts, 'base_hold_created': created, 'base_hold_taken': taken,
                  'base_release_ok': release_ok, 'base_release_failed': release_failed}
        value = lambda name, key='count', default=0: counts.get(name, default)
        trend = lambda name: {'p(99)': 12.0} if name == 'base_hold_duration' else {}
        return base_measurements(value, trend, 50, 10)

    def test_seat_shortage(self):
        # 같은 좌석을 2초 안에 다시 고르지 않을 만큼 좌석이 있어야 한다
        self.assertEqual(base_seat_shortage(250, 499), 500)
        self.assertIsNone(base_seat_shortage(250, 500))
        self.assertIsNone(base_seat_shortage(0, 0))

    def test_measurements_are_separate_from_hot_seats(self):
        base = self.summary()
        self.assertEqual(base['expected_attempts'], 500)
        self.assertEqual(base['hold_duration_ms'], {'p(99)': 12.0})
        self.assertEqual(base['outcomes']['created'], 500)

    def test_checks_pass_when_all_created_and_released(self):
        self.assertTrue(all(base_checks(self.summary(), dropped=0).values()))

    def test_one_second_of_attempts_may_differ(self):
        # 측정 구간 밖 반복은 보내지 않아 시도 수가 1초 분량(rate)까지 다를 수 있다
        self.assertTrue(base_checks(self.summary(created=450, release_ok=450, attempts=450), 0)['base_arrival_rate_achieved'])
        self.assertFalse(base_checks(self.summary(created=449, release_ok=449, attempts=449), 0)['base_arrival_rate_achieved'])
        self.assertFalse(base_checks(self.summary(), dropped=1)['base_arrival_rate_achieved'])

    def test_conflict_or_unreleased_fails(self):
        # 다른 좌석끼리 겹치면(taken) 좌석이 모자란 것이다
        self.assertFalse(base_checks(self.summary(created=499, taken=1, release_ok=499), 0)['base_all_created'])
        self.assertFalse(base_checks(self.summary(release_ok=499, release_failed=1), 0)['base_all_released'])

    def test_k6_base_uses_own_users_seats_and_window(self):
        js = (Path(scenario5.__file__).parent/'k6'/'scenario5.js').read_text(encoding='utf-8')
        self.assertIn('const index = hotUsers + j % meta.base_users;', js)
        self.assertIn('const seat = meta.base_seats[j % meta.base_seats.length];', js)
        self.assertIn('if (now < data.t0 || now >= data.t0 + meta.duration * 1000) return;', js)
        self.assertIn("tags: {name: HOLD, target: 'base'}", js)

    def test_k6_burst_slot_does_not_use_vu_id(self):
        # VU 번호는 다른 좌석 선점 시나리오와 함께 매겨진다. slot은 첫 반복의 시나리오 반복 번호다
        js = (Path(scenario5.__file__).parent/'k6'/'scenario5.js').read_text(encoding='utf-8')
        self.assertNotIn('exec.vu.idInTest', js)
        self.assertIn('if (burstSlot === null) burstSlot = exec.scenario.iterationInTest;', js)

if __name__ == '__main__':
    unittest.main()
