"""공개 시험 설정과 develop worktree만 사용하는 Compose 진입점.

python infra/integration/compose.py config
python infra/integration/compose.py up -d
python infra/integration/compose.py stop
실제 .env를 읽지 않는다. down/rm/볼륨 삭제는 지원하지 않는다.
"""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
ASSETS=Path(__file__).resolve().parent
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
        INTEGRATION_SOURCE_SHA=sha[:7],SLACK_WEBHOOK_URL_FILE=(source/'infra/alertmanager/mock-slack-url').as_posix())
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
