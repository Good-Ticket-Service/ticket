"""수집기가 컨테이너 하나의 docker inspect 실패를 그 컨테이너에만 가두는지에 대한 모의 검증(#312)."""
import json
import unittest
from unittest.mock import patch
import observe

class FakeDocker:
    """observe.run 대신 docker 명령에 답한다. broken에 든 컨테이너는 docker inspect가 실패한다(--rm 컨테이너가 지워지는 순간)."""
    def __init__(self, services, broken=(), foreign=None):
        self.services = services
        self.broken = set(broken)
        self.foreign = foreign or {}
        self.calls = []

    def __call__(self, args, timeout=12):
        self.calls.append(args)
        if args[:2] == ['docker', 'ps']:
            # 필터 없는 실행 중 전체 조회(#336)에만 외부 컨테이너가 나온다.
            extra = list(self.foreign) if '--filter' not in args else []
            return '\n'.join([*self.services, *extra]) + '\n'
        if args[:2] == ['docker', 'inspect']:
            cid = args[-1]
            if cid in self.foreign:
                return json.dumps(self.foreign[cid])
            if cid in self.broken:
                raise RuntimeError('docker exited 1')
            return json.dumps({'service': self.services[cid], 'running': True, 'oom': False, 'restarts': 0,
                               'memory': 0, 'cpuset': '0-1', 'quota': 0})
        if args[:2] == ['docker', 'exec'] and args[2] in self.services:
            return 'FILE:cpu.stat\nusage_usec 1000000\n'
        raise AssertionError('예상하지 않은 명령: ' + ' '.join(args))

def collect(fake):
    with patch.object(observe, 'run', fake), patch.object(observe.os, 'name', 'posix'):
        return observe.collect().decode().splitlines()

def values(lines, name):
    """지표 이름별 {라벨 문자열: 값}. 라벨 순서는 observe.metric이 쓴 그대로다."""
    return {line[len(name):line.rindex(' ')]: float(line.rsplit(' ', 1)[1]) for line in lines if line.startswith(name + '{')}

SERVICES = {'aaaaaaaaaaaa01': 'reservation-service', 'bbbbbbbbbbbb02': 'load', 'cccccccccccc03': 'gateway-service'}

class ContainerFailureTests(unittest.TestCase):
    def test_one_failed_inspect_keeps_other_containers(self):
        lines = collect(FakeDocker(SERVICES, broken={'bbbbbbbbbbbb02'}))
        self.assertIn('integration_source_up{source="docker"} 1.0', lines)
        cpu = values(lines, 'integration_container_cpu_usage_seconds_total')
        self.assertEqual(sorted(cpu), ['{service="gateway-service",container="cccccccccccc"}',
                                       '{service="reservation-service",container="aaaaaaaaaaaa"}'])
        inspect = values(lines, 'integration_container_inspect_up')
        self.assertEqual(inspect, {'{service="reservation-service",container="aaaaaaaaaaaa"}': 1.0,
                                   '{container="bbbbbbbbbbbb"}': 0.0,
                                   '{service="gateway-service",container="cccccccccccc"}': 1.0})
        self.assertIn('integration_discovered_containers 3.0', lines)

    def test_all_inspect_succeed(self):
        lines = collect(FakeDocker(SERVICES))
        self.assertIn('integration_source_up{source="docker"} 1.0', lines)
        self.assertEqual(len(values(lines, 'integration_container_cpu_usage_seconds_total')), 3)
        self.assertEqual(set(values(lines, 'integration_container_inspect_up').values()), {1.0})

    def test_failed_postgres_skips_vm_and_postgres_sources(self):
        fake = FakeDocker({'pppppppppppp01': 'postgres', 'aaaaaaaaaaaa01': 'reservation-service'}, broken={'pppppppppppp01'})
        lines = collect(fake)
        self.assertIn('integration_source_up{source="docker"} 1.0', lines)
        self.assertFalse(any('source="vm"' in line or 'source="postgres-diagnostics"' in line for line in lines))
        self.assertFalse(any(args[:3] == ['docker', 'exec', 'pppppppppppp01'] for args in fake.calls))
        self.assertEqual(len(values(lines, 'integration_container_cpu_usage_seconds_total')), 1)

class ForeignContainerTests(unittest.TestCase):
    """ticket-integration 밖의 실행 중 컨테이너 수를 지표로 남겨 측정 도중 생긴 경우도 보이게 한다(#336)."""
    def test_counts_running_foreign_containers(self):
        fake = FakeDocker(SERVICES, foreign={
            'ffffffffffff01': {'name': '/ticket-reservation-1', 'labels': {'com.docker.compose.project': 'ticket'},
                               'cpuset': '2-4', 'restart_policy': 'always', 'restarts': 31},
            'ffffffffffff02': {'name': '/quirky_probe', 'labels': None, 'cpuset': '', 'restart_policy': 'no', 'restarts': 0}})
        lines = collect(fake)
        self.assertIn('integration_source_up{source="foreign"} 1.0', lines)
        self.assertIn('integration_foreign_running_containers 2.0', lines)
        self.assertEqual(values(lines, 'integration_foreign_container_info'),
                         {'{container_name="ticket-reservation-1",project="ticket",cpuset="2-4"}': 1.0,
                          '{container_name="quirky_probe",project="",cpuset=""}': 1.0})
        # 기존 ticket-integration 지표는 그대로다.
        self.assertIn('integration_discovered_containers 3.0', lines)

    def test_no_foreign_containers_reports_zero(self):
        lines = collect(FakeDocker(SERVICES))
        self.assertIn('integration_foreign_running_containers 0.0', lines)
        self.assertFalse(any(line.startswith('integration_foreign_container_info') for line in lines))

    def test_foreign_failure_does_not_hide_integration_metrics(self):
        class BrokenPs(FakeDocker):
            def __call__(self, args, timeout=12):
                if args[:3] == ['docker', 'ps', '-q']:
                    raise RuntimeError('docker exited 1')
                return super().__call__(args, timeout)
        lines = collect(BrokenPs(SERVICES))
        self.assertIn('integration_source_up{source="foreign"} 0.0', lines)
        self.assertIn('integration_source_up{source="docker"} 1.0', lines)

if __name__ == '__main__':
    unittest.main()
