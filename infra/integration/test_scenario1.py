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

if __name__ == '__main__':
    unittest.main()
