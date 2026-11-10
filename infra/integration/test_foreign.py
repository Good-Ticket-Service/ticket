"""측정 전후 외부 컨테이너 CPU 경합 검사(#336)에 대한 모의 검증. 실제 Docker를 부르지 않는다."""
import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch
import foreign
from scenario1 import Failure, Scenario
from scenario3 import baseline_usable

INTEGRATION = {'a' * 64: 'reservation-service', 'b' * 64: 'k6'}

def container(name, cpuset, project='ticket', restarts=0, policy='no'):
    labels = {'com.docker.compose.project': project} if project else None
    return {'name': '/' + name, 'labels': labels, 'cpuset': cpuset, 'restart_policy': policy, 'restarts': restarts}

class FakeDocker:
    """foreign.run 대신 답한다. vanished에 든 컨테이너는 docker ps 뒤 inspect 전에 사라진 것으로 친다."""
    def __init__(self, others=None, vanished=(), broken=()):
        self.others = others or {}
        self.vanished = set(vanished)
        self.broken = set(broken)

    def __call__(self, args, timeout=12):
        if args[:2] == ['docker', 'ps']:
            if '--filter' in args:
                return '\n'.join(INTEGRATION) + '\n'
            alive = [c for c in self.others if c not in self.vanished]
            first = not hasattr(self, 'listed')
            self.listed = True
            return '\n'.join([*INTEGRATION, *(self.others if first else alive)]) + '\n'
        if args[:2] == ['docker', 'inspect']:
            cid = args[-1]
            if cid in self.vanished or cid in self.broken:
                raise RuntimeError('docker exited 1')
            return json.dumps(self.others[cid])
        raise AssertionError('예상하지 않은 명령: ' + ' '.join(args))

class CpusetTests(unittest.TestCase):
    def test_parses_ranges_and_lists(self):
        self.assertEqual(foreign.parse_cpuset('0-1,4'), {0, 1, 4})
        self.assertEqual(foreign.parse_cpuset('6-9'), {6, 7, 8, 9})

    def test_empty_cpuset_means_all_cpus(self):
        self.assertIsNone(foreign.parse_cpuset(''))
        self.assertIsNone(foreign.parse_cpuset(None))

    def test_integration_cpus_is_union_of_compose_cpusets(self):
        resources = {'caddy': {'cpuset': '0-1'}, 'postgres': {'cpuset': '4-5'}, 'k6': {'cpuset': '6-9', 'mem_limit': 1}}
        self.assertEqual(foreign.integration_cpus(resources), {0, 1, 4, 5, 6, 7, 8, 9})

    def test_integration_service_without_cpuset_uses_all_cpus(self):
        self.assertIsNone(foreign.integration_cpus({'caddy': {'cpuset': '0-1'}, 'tool': {}}))

    def test_overlap(self):
        cpus = frozenset({0, 1, 2, 3})
        self.assertTrue(foreign.overlaps('3-4', cpus))
        self.assertFalse(foreign.overlaps('6-7', cpus))
        self.assertTrue(foreign.overlaps('', cpus))
        self.assertTrue(foreign.overlaps('6-7', None))

class ScanTests(unittest.TestCase):
    def test_excludes_integration_and_marks_overlap(self):
        fake = FakeDocker({'c' * 64: container('ticket-reservation-1', '2-4', restarts=31, policy='always'),
                           'd' * 64: container('other-db', '7')})
        result = foreign.scan(frozenset({0, 1, 2, 3, 4, 5}), run=fake)
        self.assertEqual([(c['name'], c['overlaps_integration_cpus']) for c in result['containers']],
                         [('ticket-reservation-1', True), ('other-db', False)])
        self.assertEqual(result['containers'][0]['restarts'], 31)
        self.assertEqual(result['vanished'], 0)

    def test_unlabeled_probe_is_foreign_and_empty_cpuset_overlaps(self):
        fake = FakeDocker({'e' * 64: container('quirky_probe', '', project=None)})
        [probe] = foreign.scan(frozenset({6}), run=fake)['containers']
        self.assertIsNone(probe['project'])
        self.assertTrue(probe['overlaps_integration_cpus'])
        self.assertIn('(not compose)', foreign.table([probe]))
        self.assertIn('(all CPUs)', foreign.table([probe]))

    def test_vanished_container_is_counted_not_listed(self):
        fake = FakeDocker({'f' * 64: container('rm-probe', '2-3', project=None)}, vanished={'f' * 64})
        self.assertEqual(foreign.scan(frozenset({2}), run=fake), {'containers': [], 'vanished': 1})

    def test_inspect_failure_of_running_container_is_not_hidden(self):
        fake = FakeDocker({'f' * 64: container('still-here', '2-3')}, broken={'f' * 64})
        with self.assertRaises(RuntimeError):
            foreign.scan(frozenset({2}), run=fake)

def scenario(allow=False):
    obj = object.__new__(Scenario)
    obj.allow_foreign = allow
    obj.report = {'resources': {'caddy': {'cpuset': '0-1'}, 'reservation-service': {'cpuset': '2-3'}}, 'checks': {}}
    obj.save = Mock()
    return obj

def run_check(obj, *results):
    """before → after 순서로 scan 결과를 돌려준다. 출력은 문자열로 모은다."""
    out = io.StringIO()
    with patch.object(foreign, 'scan', Mock(side_effect=list(results))), redirect_stdout(out):
        obj.check_foreign('before')
        ok = obj.finish_foreign() if len(results) > 1 else None
    return ok, out.getvalue()

CLEAN = {'containers': [], 'vanished': 0}
NOT_OVERLAPPING = {'containers': [{'name': 'far-away', 'project': 'x', 'cpuset': '7', 'restart_policy': 'no',
                                   'restarts': 0, 'overlaps_integration_cpus': False}], 'vanished': 0}
OVERLAPPING = {'containers': [{'name': 'ticket-reservation-1', 'project': 'ticket', 'cpuset': '2-4',
                               'restart_policy': 'always', 'restarts': 31, 'overlaps_integration_cpus': True}], 'vanished': 0}

class ScenarioCheckTests(unittest.TestCase):
    def test_clean_run_passes(self):
        ok, out = run_check(scenario(), CLEAN, CLEAN)
        self.assertTrue(ok)
        self.assertEqual(out, '')

    def test_records_before_after_and_check(self):
        obj = scenario()
        run_check(obj, CLEAN, CLEAN)
        record = obj.report['foreign_containers']
        self.assertEqual((record['allowed'], record['integration_cpus']), (False, [0, 1, 2, 3]))
        self.assertEqual((record['before'], record['after']), (CLEAN, CLEAN))
        self.assertIs(obj.report['checks']['no_foreign_containers'], True)

    def test_non_overlapping_container_only_warns(self):
        ok, out = run_check(scenario(), NOT_OVERLAPPING, NOT_OVERLAPPING)
        self.assertTrue(ok)
        self.assertIn('not blocked): far-away', out)
        self.assertNotIn('!!!!', out)

    def test_overlap_before_stops_measurement_and_prints_table(self):
        obj = scenario()
        with self.assertRaisesRegex(Failure, 'ticket-reservation-1.*--allow-foreign'):
            run_check(obj, OVERLAPPING)
        self.assertIs(obj.report['checks']['no_foreign_containers'], False)

    def test_table_lists_name_project_cpuset_restarts(self):
        out = io.StringIO()
        with patch.object(foreign, 'scan', Mock(return_value=OVERLAPPING)), redirect_stdout(out):
            with self.assertRaises(Failure):
                scenario().check_foreign('before')
        line = [l for l in out.getvalue().splitlines() if l.startswith('ticket-reservation-1')][0].split()
        self.assertEqual(line, ['ticket-reservation-1', 'ticket', '2-4', 'always', '31', 'yes'])

    def test_allow_foreign_continues_but_fails_check_with_banner(self):
        obj = scenario(allow=True)
        ok, out = run_check(obj, OVERLAPPING, CLEAN)
        self.assertFalse(ok)
        self.assertIs(obj.report['checks']['no_foreign_containers'], False)
        self.assertTrue(obj.report['foreign_containers']['allowed'])
        self.assertIn('--allow-foreign', out)
        edges = [l for l in out.splitlines() if l and set(l) == {'!'}]
        self.assertEqual(len(edges), 8)  # 시작과 끝에 큰 경고 두 번(각 위아래 2줄)

    def test_container_appearing_during_measurement_fails_check(self):
        ok, out = run_check(scenario(), CLEAN, OVERLAPPING)
        self.assertFalse(ok)
        self.assertIn('no_foreign_containers=false', out)

    def test_after_scan_error_fails_check(self):
        obj = scenario()
        out = io.StringIO()
        with patch.object(foreign, 'scan', Mock(side_effect=[CLEAN, RuntimeError('docker exited 1')])), redirect_stdout(out):
            obj.check_foreign('before')
            self.assertFalse(obj.finish_foreign())
        self.assertEqual(obj.report['foreign_containers']['after_error'], 'RuntimeError')

class Scenario3GateTests(unittest.TestCase):
    """S3는 --allow-foreign 회차에서만 기준선 진행 판단에서 외부 컨테이너 검사를 뺀다."""
    def report(self, **checks):
        checks = {'k6_thresholds': True, 'financial_B': True, **checks}
        return {'checks': checks, 'verdict': 'PASS' if all(checks.values()) else 'FAIL'}

    def test_allow_foreign_ignores_only_foreign_check(self):
        self.assertTrue(baseline_usable(self.report(no_foreign_containers=False), True))
        self.assertFalse(baseline_usable(self.report(no_foreign_containers=False, financial_B=False), True))

    def test_without_allow_foreign_baseline_must_pass(self):
        self.assertFalse(baseline_usable(self.report(no_foreign_containers=False), False))
        self.assertTrue(baseline_usable(self.report(no_foreign_containers=True), False))

if __name__ == '__main__':
    unittest.main()
