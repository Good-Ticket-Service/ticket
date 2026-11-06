"""공개 시험 설정과 develop worktree만 사용하는 Compose 진입점.

python infra/integration/compose.py config
python infra/integration/compose.py up -d
python infra/integration/compose.py stop
COMPOSE_PROFILES=observability로 켜면 trace·로그·프로파일 수집기와 Java 에이전트를 함께 띄운다.
실제 .env를 읽지 않는다. down/rm/볼륨 삭제는 지원하지 않는다.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
ASSETS=Path(__file__).resolve().parent
AGENTS=ROOT/'script'/'integration'/'agents'
MAVEN='https://repo1.maven.org/maven2/'
AGENT_JARS={
    'opentelemetry-javaagent-2.32.0.jar':('io/opentelemetry/javaagent/opentelemetry-javaagent/2.32.0/',
        'f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82'),
    'agent-2.9.2.jar':('io/pyroscope/agent/2.9.2/','ad738199a90734f7b8392bae1ab892027d411acb953ef1f00cd6e8e7c669ff8f'),
}

AGENT_OPTS=' '.join('-javaagent:/agents/'+name for name in AGENT_JARS)
# jar가 필요한 것은 컨테이너를 띄우는 명령뿐이다. 나머지 명령은 받지 않아야 다운로드가 실패해도 멈추고 조회할 수 있다.
STARTS_CONTAINERS=('up','run')

def ensure_agents():
    AGENTS.mkdir(parents=True,exist_ok=True)
    for name,(path,sha256) in AGENT_JARS.items():
        jar=AGENTS/name
        if not jar.exists():
            subprocess.check_call(['curl','-sSfL','-o',str(jar),MAVEN+path+name])
        if hashlib.sha256(jar.read_bytes()).hexdigest()!=sha256:
            jar.unlink();raise SystemExit('Checksum mismatch: '+name)

def observability_enabled(args):
    profiles=os.environ.get('COMPOSE_PROFILES','').split(',')
    profiles+=[args[i+1] for i,a in enumerate(args[:-1]) if a=='--profile']
    return 'observability' in profiles

def command(args):
    if not args or args[0] not in ('config','up','stop','ps','logs','build','pull','run'):
        raise SystemExit('Allowed: config/up/stop/ps/logs/build/pull/run')
    if any(a in ('--remove-orphans','--volumes','-v') for a in args):
        raise SystemExit('Destructive options are not supported')
    candidates=list((ROOT/'script'/'integration').glob('source-*'))
    if len(candidates)!=1:
        raise SystemExit('Expected one prepared develop worktree; resolve source explicitly before running')
    source=candidates[0]
    sha=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    if subprocess.check_output(['git','-C',str(source),'status','--porcelain'],text=True).strip():
        raise SystemExit('Source worktree must be clean')
    env=os.environ.copy()
    for line in (ASSETS/'compose-inputs.example').read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            key,value=line.split('=',1);env[key]=value
    results=ROOT/'script'/'integration'/'results';results.mkdir(parents=True,exist_ok=True)
    env.update(COMPOSE_DISABLE_ENV_FILE='1',INTEGRATION_SOURCE=source.as_posix(),
        INTEGRATION_ASSETS=ASSETS.as_posix(),INTEGRATION_RESULTS=results.as_posix(),
        INTEGRATION_SOURCE_SHA=sha[:7],SLACK_WEBHOOK_URL_FILE=(source/'infra/alertmanager/mock-slack-url').as_posix(),
        INTEGRATION_AGENTS=AGENTS.as_posix())
    if observability_enabled(args):
        if args[0] in STARTS_CONTAINERS:
            ensure_agents()
        # 옵션 값은 명령과 관계없이 같게 둔다. 명령마다 달라지면 up이 아닌 명령에서도 컨테이너를 다시 만들 수 있다.
        # 수집기는 7번 CPU를 쓰고 k6는 6번만 쓴다. DB 코어(4-5)에 수집 부담이 섞이지 않게 한다.
        # JFR과 Hikari 누수 탐지도 이 프로필에서만 켠다. 기본 실행은 이전 측정과 같은 조건을 유지한다.
        env.update(INTEGRATION_LOAD_CPUS='6',INTEGRATION_OBSERVABILITY_CPUS='7',
            INTEGRATION_OBSERVABILITY_JAVA_OPTS=AGENT_OPTS+' -Dlogging.structured.format.console=ecs',
            INTEGRATION_JFR='on',INTEGRATION_LEAK_DETECTION_MS='2000')
    cmd=['docker','compose','--project-name','ticket-integration','--env-file',str(ASSETS/'compose-inputs.example'),
         '-f',str(source/'docker-compose.yml'),'-f',str(ROOT/'compose.integration.yml')]
    return cmd,env,sha

if __name__=='__main__':
    args=sys.argv[1:];cmd,env,sha=command(args)
    if args==['config']:
        p=subprocess.run(cmd+['--profile','load','--profile','tools','config','--format','json'],env=env,capture_output=True,text=True)
        if p.returncode:
            print(p.stderr);raise SystemExit(p.returncode)
        d=json.loads(p.stdout)
        for name in ('grafana','prometheus','redis-exporter','kafka-exporter'):
            assert name in d['services']
        mounts=d['services']['grafana']['volumes']
        assert any(m['target']=='/var/lib/grafana/integration-dashboards' for m in mounts)
        provision=[m for m in mounts if m['target']=='/etc/grafana/provisioning']
        assert len(provision)==1 and '/integration/grafana/provisioning' in provision[0]['source'].replace('\\','/')
        assert not any(m['target'].startswith('/etc/grafana/provisioning/') for m in mounts)
        print(json.dumps({'source':sha,'services':len(d['services']),'grafana':'http://localhost:13000','dashboard_mount':True},indent=2))
    else:
        raise SystemExit(subprocess.call(cmd+args,env=env))
