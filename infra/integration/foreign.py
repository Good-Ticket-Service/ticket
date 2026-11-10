"""ticket-integration 밖의 실행 중 컨테이너를 찾고 통합시험 CPU와 겹치는지 판단한다(#336).

#325에서 다른 compose 프로젝트가 같은 CPU에서 재시작을 반복해 측정값이 크게 흔들렸지만,
수집기와 결과 파일은 ticket-integration만 봐서 드러나지 않았다. 시나리오와 observe.py가 함께 쓴다.
외부 컨테이너를 멈추거나 지우지 않는다. 무엇을 멈출지는 실행하는 사람이 정한다.
"""
import json
import subprocess

PROJECT = 'ticket-integration'
# 라벨 값 대신 Labels 전체를 JSON으로 받는다. 라벨이 하나도 없는 컨테이너(docker run 탐침 등)는 null이 온다.
INSPECT_FORMAT = ('{"name":{{json .Name}},"labels":{{json .Config.Labels}},"cpuset":{{json .HostConfig.CpusetCpus}},'
                  '"restart_policy":{{json .HostConfig.RestartPolicy.Name}},"restarts":{{.RestartCount}}}')

ALLOW_HELP = ('measure even if containers outside ticket-integration share integration CPUs; '
              'the run is recorded as checks.no_foreign_containers=false (verdict FAIL)')
ALLOWED_WARNING = ('WARNING: --allow-foreign: measuring while foreign containers share integration CPUs.',
                   'checks.no_foreign_containers=false, so this run is recorded as FAIL.',
                   'Do not report these numbers as a contention-free measurement.')
FAILED_WARNING = ('WARNING: checks.no_foreign_containers=false',
                  'Foreign containers shared integration CPUs before or after the measurement (or the check failed).',
                  'See foreign_containers in result.json. Do not report these numbers as contention-free.')

def run(args, timeout=12):
    p = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if p.returncode:
        raise RuntimeError(args[0]+' exited '+str(p.returncode))
    return p.stdout

def parse_cpuset(text):
    """'0-1,4' 같은 cpuset을 CPU 번호 집합으로 바꾼다. 비어 있으면 None(모든 CPU 사용)."""
    text = (text or '').strip()
    if not text:
        return None
    cpus = set()
    for part in text.split(','):
        low, _, high = part.strip().partition('-')
        cpus.update(range(int(low), int(high or low)+1))
    return frozenset(cpus)

def integration_cpus(resources):
    """compose config의 서비스별 cpuset 합집합. 하나도 없으면 None(통합시험이 모든 CPU를 쓰는 것으로 본다)."""
    sets = [parse_cpuset(c.get('cpuset')) for c in resources.values()]
    if not sets or any(s is None for s in sets):
        return None
    return frozenset().union(*sets)

def overlaps(cpuset, cpus):
    """외부 컨테이너의 cpuset이 통합시험 CPU와 겹치는지. 어느 한쪽이 비어 있으면(모든 CPU) 겹친다."""
    mine = parse_cpuset(cpuset)
    return mine is None or cpus is None or bool(mine & cpus)

def scan(cpus, run=run):
    """실행 중 전체 - ticket-integration 라벨 컨테이너의 차집합을 외부로 본다.
    라벨 문자열을 파싱하지 않으므로 k6(compose run)·observability 프로필도 라벨로 내부가 된다.
    docker ps 뒤 inspect 전에 사라진 --rm 컨테이너는 목록에서 빼고 vanished로 센다(#312와 같은 처리)."""
    running = run(['docker', 'ps', '-q', '--no-trunc']).split()
    mine = set(run(['docker', 'ps', '-q', '--no-trunc', '--filter', 'label=com.docker.compose.project='+PROJECT]).split())
    containers, vanished = [], 0
    for cid in running:
        if cid in mine:
            continue
        try:
            d = json.loads(run(['docker', 'inspect', '--format', INSPECT_FORMAT, cid]))
        except Exception:
            if cid in run(['docker', 'ps', '-q', '--no-trunc']).split():
                raise
            vanished += 1
            continue
        labels = d['labels'] or {}
        containers.append({'id': cid[:12], 'name': d['name'].lstrip('/'),
                           'project': labels.get('com.docker.compose.project'),
                           'cpuset': d['cpuset'], 'restart_policy': d['restart_policy'], 'restarts': d['restarts'],
                           'overlaps_integration_cpus': overlaps(d['cpuset'], cpus)})
    return {'containers': containers, 'vanished': vanished}

def overlapping(result):
    return [c for c in result['containers'] if c['overlaps_integration_cpus']]

def table(containers):
    rows = [('NAME', 'PROJECT', 'CPUSET', 'RESTART', 'RESTARTS', 'OVERLAP')]
    rows += [(c['name'], c['project'] or '(not compose)', c['cpuset'] or '(all CPUs)', c['restart_policy'] or '-',
              str(c['restarts']), 'yes' if c['overlaps_integration_cpus'] else 'no') for c in containers]
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    return '\n'.join('  '.join(v.ljust(w) for v, w in zip(r, widths)).rstrip() for r in rows)

def banner(*lines):
    width = max(len(line) for line in lines)+4
    edge = '!'*width
    return '\n'.join([edge, edge, *('! '+line.ljust(width-4)+' !' for line in lines), edge, edge])
