"""확정 좌석 검사가 배치도 1초 스냅샷(#270)을 기다리는지에 대한 모의 검증."""
import unittest
from unittest.mock import Mock, patch
import scenario1
from scenario1 import Failure, Scenario

SID, SEAT = 7, 11
VENUE, GRADE = 3, 5

def seats(selectable):
    return {'seats': [{'scheduleSeatId': SEAT, 'selectable': selectable}, {'scheduleSeatId': SEAT + 1, 'selectable': True}]}

class FakeClock:
    """time.monotonic과 time.sleep을 대신해 실제로 기다리지 않는다."""
    def __init__(self):
        self.now = 0.0
    def monotonic(self):
        return self.now
    def sleep(self, seconds):
        self.now += seconds

class ConfirmedSeatWaitTests(unittest.TestCase):
    def check(self, *responses):
        obj = object.__new__(Scenario)
        obj.request = Mock(side_effect=list(responses))
        clock = FakeClock()
        with patch.object(scenario1.time, 'monotonic', clock.monotonic), patch.object(scenario1.time, 'sleep', clock.sleep):
            obj.wait_seat_unselectable(SID, SEAT, 'token')
        return obj.request

    def test_passes_when_stale_snapshot_expires(self):
        request = self.check(seats(True), seats(False))
        self.assertEqual(2, request.call_count)

    def test_fails_when_seat_stays_selectable(self):
        with self.assertRaisesRegex(Failure, 'Confirmed seat is still selectable'):
            self.check(*[seats(True)] * 20)

    def test_does_not_retry_when_already_unselectable(self):
        request = self.check(seats(False))
        request.assert_called_once_with('GET', f'/api/seats/schedules/{SID}', token='token')

class RegisterSeatsTests(unittest.TestCase):
    """좌석 일괄 등록 상한(#272) 때문에 5,000개씩 나눠 보내고 venueVersion을 이어 쓰는지 모의 검증."""
    def register(self, seat_count):
        obj = object.__new__(Scenario)
        obj.request = Mock(side_effect=[{'venueVersion': v} for v in range(2, 12)])
        version = obj.register_seats(VENUE, GRADE, seat_count, 1, 'token')
        return obj.request, version

    def test_splits_by_chunk_and_chains_version(self):
        request, version = self.register(12000)
        bodies = [c.args[2]['seats'] for c in request.call_args_list]
        self.assertEqual([5000, 5000, 2000], [len(b) for b in bodies])
        self.assertEqual([1, 5001, 10001], [b[0]['seatNumber'] for b in bodies])
        self.assertEqual(12000, bodies[-1][-1]['seatNumber'])
        self.assertEqual(['1', '2', '3'], [c.kwargs['headers']['If-Match'] for c in request.call_args_list])
        self.assertEqual(4, version)

    def test_sends_one_request_up_to_limit(self):
        request, version = self.register(5000)
        self.assertEqual(1, request.call_count)
        self.assertEqual(2, version)

class VenueSeatsTests(unittest.TestCase):
    """공연장 좌석 배치(#397). single은 기존과 같고, sections는 한 열 25석·구역당 1,000석이다."""
    def test_single_is_section_a_row_1(self):
        seats = scenario1.venue_seats(3)
        self.assertEqual([{'section': 'A', 'rowLabel': '1', 'seatNumber': n} for n in (1, 2, 3)], seats)

    def test_sections_fill_rows_then_next_section(self):
        seats = scenario1.venue_seats(2001, 'sections')
        self.assertEqual({'section': 'A', 'rowLabel': '1', 'seatNumber': 25}, seats[24])
        self.assertEqual({'section': 'A', 'rowLabel': '2', 'seatNumber': 1}, seats[25])
        self.assertEqual({'section': 'A', 'rowLabel': '40', 'seatNumber': 25}, seats[999])
        self.assertEqual({'section': 'B', 'rowLabel': '1', 'seatNumber': 1}, seats[1000])
        self.assertEqual({'section': 'C', 'rowLabel': '1', 'seatNumber': 1}, seats[2000])

    def test_20000_seats_are_20_sections_of_1000(self):
        seats = scenario1.venue_seats(20000, 'sections')
        counts = {}
        for s in seats:
            counts[s['section']] = counts.get(s['section'], 0) + 1
        self.assertEqual([chr(65 + i) for i in range(20)], list(counts))
        self.assertEqual({1000}, set(counts.values()))
        self.assertEqual(20000, len({(s['section'], s['rowLabel'], s['seatNumber']) for s in seats}))

    def test_smaller_sections_make_more_sections(self):
        seats = scenario1.venue_seats(20000, 'sections', 250)
        self.assertEqual({'section': 'A', 'rowLabel': '10', 'seatNumber': 25}, seats[249])
        self.assertEqual({'section': 'B', 'rowLabel': '1', 'seatNumber': 1}, seats[250])
        self.assertEqual(80, len({s['section'] for s in seats}))
        self.assertEqual('CB', seats[-1]['section'])

    def test_section_seats_must_be_row_multiple(self):
        with self.assertRaises(Failure):
            scenario1.venue_seats(100, 'sections', 30)

    def test_section_names_continue_after_z(self):
        self.assertEqual(['A', 'Z', 'AA', 'AB'], [scenario1.section_name(i) for i in (0, 25, 26, 27)])

    def test_unknown_layout_fails(self):
        with self.assertRaises(Failure):
            scenario1.venue_seats(1, 'grid')

    def test_register_uses_venue_layout(self):
        obj = object.__new__(Scenario)
        obj.venue_layout = 'sections'
        obj.section_seats = 1000
        obj.request = Mock(side_effect=[{'venueVersion': v} for v in range(2, 12)])
        obj.register_seats(VENUE, GRADE, 6000, 1, 'token')
        bodies = [c.args[2]['seats'] for c in obj.request.call_args_list]
        self.assertEqual([5000, 1000], [len(b) for b in bodies])
        self.assertEqual({'section': 'F', 'rowLabel': '1', 'seatNumber': 1, 'venueSeatGradeId': GRADE}, bodies[1][0])

if __name__ == '__main__':
    unittest.main()

class DbErrorTests(unittest.TestCase):
    """읽기 전용 DB 검사가 실패하면 psql 오류 내용을 결과 폴더에 남긴다(#397 측정에서 원인을 알 수 없었음)."""
    def scenario(self, folder):
        obj = object.__new__(Scenario)
        obj.cmd, obj.env, obj.out = ['docker', 'compose'], {'POSTGRES_USER': 'ticket'}, folder
        return obj

    def test_failure_saves_stderr_and_puts_first_line_in_error(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            obj = self.scenario(Path(d))
            failed = Mock(returncode=3, stdout='', stderr='ERROR:  canceling statement due to statement timeout\nCONTEXT: x\n')
            with patch.object(scenario1.subprocess, 'run', return_value=failed):
                with self.assertRaises(Failure) as e:
                    obj.db('SELECT 1')
            self.assertIn('canceling statement due to statement timeout', str(e.exception))
            saved = (Path(d)/'db-error.txt').read_text(encoding='utf-8')
            self.assertIn('exit=3', saved)
            self.assertIn('CONTEXT: x', saved)

    def test_success_writes_nothing(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            obj = self.scenario(Path(d))
            with patch.object(scenario1.subprocess, 'run', return_value=Mock(returncode=0, stdout='[1]', stderr='')):
                self.assertEqual(obj.db('SELECT 1'), [1])
            self.assertFalse((Path(d)/'db-error.txt').exists())
