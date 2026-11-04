"""JSON 구조와 PromQL 문법 검증. 격리된 일회성 promtool 컨테이너를 사용한다."""
import json
from pathlib import Path
import subprocess
import sys

files=list((Path(__file__).parent/'grafana/dashboards').glob('*.json'))
assert len(files)==4
rules=[]
for file in files:
    d=json.loads(file.read_text(encoding='utf-8'))
    ids=[p['id'] for p in d['panels']]
    assert len(ids)==len(set(ids))
    for panel in d['panels']:
        for target in panel.get('targets',[]):
            expr=target['expr'].replace('$__rate_interval','1m').replace('$service','.*').replace('$test_id','.*').replace('$comparison_id','.*')
            rules.append({'record':'integration_check_'+str(len(rules)), 'expr':expr})
payload=json.dumps({'groups':[{'name':'integration-dashboard-validation','rules':rules}]})
p=subprocess.run(['docker','run','--rm','-i','--network','none','--entrypoint','promtool','prom/prometheus:v3.15.1','check','rules','/dev/stdin'],input=payload,text=True,capture_output=True)
print(p.stdout);print(p.stderr)
if p.returncode: raise SystemExit(p.returncode)
print(str(len(files))+' dashboards; '+str(len(rules))+' PromQL expressions validated')
