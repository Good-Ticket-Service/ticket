"""확정 좌석 검사가 배치도 1초 스냅샷(#270)을 기다리는지에 대한 모의 검증."""
import unittest
from unittest.mock import Mock, patch
import scenario1
from scenario1 import Failure, Scenario

SID, SEAT = 7, 11

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

if __name__ == '__main__':
    unittest.main()
