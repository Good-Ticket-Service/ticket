"""읽기 전용 통합 관측기. python infra/integration/observe.py [--once]

19100 포트는 Docker Desktop에서 접근하는 로컬 관측 전용이다.
Docker 이벤트와 원시 표본은 script/integration/observation-*에만 보존한다.
비밀 환경 파일/컨테이너 환경변수는 읽지 않는다.
"""
import argparse
import concurrent.futures
import ctypes
import datetime
import http.server
import json
import os
from pathlib import Path
import subprocess
import threading
import time

import foreign

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = b''
LOCK = threading.Lock()

def run(args, timeout=12):
    p = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if p.returncode:
        raise RuntimeError(args[0]+' exited '+str(p.returncode))
    return p.stdout

def metric(lines, name, value, **labels):
    suffix = '{'+','.join(k+'='+json.dumps(str(v),ensure_ascii=True) for k,v in labels.items())+'}' if labels else ''
    lines.append(name+suffix+' '+str(float(value)))

def windows(lines):
    # PerfFormattedData는 OS 표시 언어와 무관한 WMI 필드명을 사용한다.
    ps = "$m=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory; $p=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter \"Name='_Total'\"; $d=Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter \"Name='_Total'\"; [pscustomobject]@{available=$m.AvailableBytes;commit=$m.CommittedBytes;limit=$m.CommitLimit;read=$m.PagesInputPersec;write=$m.PagesOutputPersec;cpu=$p.PercentProcessorTime;diskread=$d.AvgDisksecPerRead;diskwrite=$d.AvgDisksecPerWrite}|ConvertTo-Json -Compress"
    d = json.loads(run(['powershell','-NoProfile','-Command',ps],25))
    for key in ('available','commit','limit'):
        metric(lines,'integration_windows_perf_bytes',d[key],kind=key)
    metric(lines,'integration_windows_memory_bytes',d['available'],kind='available')
    for key in ('read','write'):
        metric(lines,'integration_windows_paging_pages_per_second',d[key],kind=key)
    metric(lines,'integration_windows_cpu_ratio',d['cpu']/100)
    for key in ('diskread','diskwrite'):
        metric(lines,'integration_windows_disk_latency_seconds',d[key],kind=key)

def container(cid):
    lines=[]
    fmt='{"service":{{json (index .Config.Labels "com.docker.compose.service")}},"running":{{.State.Running}},"oom":{{.State.OOMKilled}},"restarts":{{.RestartCount}},"memory":{{.HostConfig.Memory}},"cpuset":{{json .HostConfig.CpusetCpus}},"quota":{{.HostConfig.NanoCpus}}}'
    d=json.loads(run(['docker','inspect','--format',fmt,cid]))
    lab={'service':d['service'],'container':cid[:12]}
    metric(lines,'integration_container_running',int(d['running']),**lab)
    metric(lines,'integration_container_oom_killed',int(d['oom']),**lab)
    metric(lines,'integration_container_restarts_total',d['restarts'],**lab)
    metric(lines,'integration_container_memory_bytes',d['memory'],kind='limit',**lab)
    metric(lines,'integration_container_cpu_quota_cores',d['quota']/1e9,**lab)
    metric(lines,'integration_container_cpu_set_info',1,cpuset=d['cpuset'],**lab)
    if d['running']:
        # 하나의 exec로 수집. cgroup v2를 지원하지 않으면 source 성공값 0으로 알린다.
        script='for f in cpu.stat memory.current memory.swap.current memory.events memory.stat io.stat; do echo FILE:$f; cat /sys/fs/cgroup/$f || exit 1; done; echo FILE:network; cat /proc/net/dev'
        try:
            raw=run(['docker','exec',cid,'sh','-c',script])
            section=''
            for line in raw.splitlines():
                if line.startswith('FILE:'):
                    section=line[5:]; continue
                fields=line.split()
                if section=='cpu.stat' and len(fields)==2:
                    key,val=fields
                    names={'usage_usec':('cpu_usage_seconds_total',1e6),'throttled_usec':('throttled_seconds_total',1e6),'nr_periods':('nr_periods_total',1),'nr_throttled':('nr_throttled_total',1)}
                    if key in names:
                        name,scale=names[key]; metric(lines,'integration_container_'+name,int(val)/scale,**lab)
                elif section in ('memory.current','memory.swap.current'):
                    metric(lines,'integration_container_memory_bytes',int(line),kind=section,**lab)
                elif section=='memory.events' and len(fields)==2:
                    metric(lines,'integration_container_memory_events_total',int(fields[1]),kind=fields[0],**lab)
                elif section=='memory.stat' and len(fields)==2 and fields[0] in ('anon','file'):
                    metric(lines,'integration_container_memory_bytes',int(fields[1]),kind=fields[0],**lab)
                elif section=='io.stat' and len(fields)>1:
                    for pair in fields[1:]:
                        key,value=pair.split('=')
                        if key in ('rbytes','wbytes'):
                            metric(lines,'integration_container_io_bytes_total',int(value),device=fields[0],kind=key,**lab)
                elif section=='network' and ':' in line:
                    interface,values=line.split(':',1); values=values.split()
                    if interface.strip()!='lo' and len(values)>=16:
                        for key,index in [('receive',0),('transmit',8)]:
                            metric(lines,'integration_container_network_bytes_total',int(values[index]),interface=interface.strip(),kind=key,**lab)
            metric(lines,'integration_cgroup_collection_up',1,**lab)
        except Exception:
            metric(lines,'integration_cgroup_collection_up',0,**lab)
    return lines,d

def container_or_failure(cid):
    """컨테이너 하나의 수집 실패가 다른 컨테이너 지표를 막지 않게 한다(#312).
    --rm 컨테이너(k6 등)는 docker ps 뒤 docker inspect 전에 지워질 수 있다. 실패한 컨테이너는 inspect_up 0만 남기고 실행 중이 아닌 것으로 본다."""
    try:
        lines,d=container(cid)
        metric(lines,'integration_container_inspect_up',1,service=d['service'],container=cid[:12])
        return lines,d
    except Exception as e:
        print('container '+cid[:12]+': '+type(e).__name__,flush=True)
        lines=[]
        metric(lines,'integration_container_inspect_up',0,container=cid[:12])
        return lines,{'running':False,'service':None}

def vm(lines,cid):
    script='for f in /proc/meminfo /proc/vmstat /proc/pressure/cpu /proc/pressure/memory /proc/pressure/io; do echo FILE:$f; cat $f || exit 1; done; echo FILE:disk; df -Pk /'
    raw=run(['docker','exec',cid,'sh','-c',script]); section=''
    for line in raw.splitlines():
        if line.startswith('FILE:'): section=line[5:];continue
        p=line.split()
        if section=='/proc/meminfo' and p and p[0].rstrip(':') in ('MemTotal','MemAvailable','SwapTotal','SwapFree'):
            metric(lines,'integration_vm_memory_bytes',int(p[1])*1024,kind=p[0].rstrip(':'))
        elif section=='/proc/vmstat' and p and p[0] in ('pswpin','pswpout'):
            metric(lines,'integration_vm_swap_pages_total',int(p[1]),kind=p[0])
        elif '/pressure/' in section and p and p[0] in ('some','full'):
            vals=dict(v.split('=') for v in p[1:])
            metric(lines,'integration_vm_pressure_ratio',float(vals['avg10'])/100,resource=section.rsplit('/',1)[1],kind=p[0])
        elif section=='disk' and len(p)>=6 and p[0]!='Filesystem':
            metric(lines,'integration_vm_disk_available_bytes',int(p[3])*1024)

def psql_json(cid,sql):
    return json.loads(run(['docker','exec',cid,'psql','-U','postgres','-d','ticket','-At','-v','ON_ERROR_STOP=1','-c',sql]))

def postgres(lines,cid):
    """세션·잠금 대기, 차단 세션, 가장 오래된 트랜잭션, WAL. pg_stat_statements는 postgres_statements로 따로 묻는다(TS-12).
    한 문장으로 묶었을 때 확장이 없는 DB에서 문장 전체가 실패해 잠금 대기 지표까지 모두 빠졌다."""
    d=psql_json(cid,"""SELECT json_build_object(
 'sessions',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT usename,coalesce(wait_event_type,'none') wt,coalesce(wait_event,'none') we,state,count(*) n FROM pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid() GROUP BY 1,2,3,4) x),
 'oldest',(SELECT coalesce(max(extract(epoch FROM now()-xact_start)),0) FROM pg_stat_activity WHERE pid<>pg_backend_pid()),
 'blocked',(SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0),
 'wal',(SELECT wal_bytes FROM pg_stat_wal));""")
    for x in d['sessions']:
        metric(lines,'integration_pg_sessions',x['n'],service=x['usename'],wait_type=x['wt'],wait_event=x['we'],state=x['state'])
    for name,key in [('oldest_transaction_seconds','oldest'),('blocked_sessions','blocked'),('wal_bytes_total','wal')]:
        metric(lines,'integration_pg_'+name,d[key])

def postgres_statements(lines,cid):
    """역할별 SQL 호출 수·실행 시간. pg_stat_statements 확장이 없으면 이 source만 0이 되고 위 진단 지표는 남는다(TS-12)."""
    rows=psql_json(cid,"SELECT coalesce(json_agg(x),'[]') FROM (SELECT r.rolname,sum(s.calls) calls,sum(s.total_exec_time)/1000 seconds FROM pg_stat_statements s JOIN pg_roles r ON r.oid=s.userid GROUP BY r.rolname) x;")
    for x in rows:
        metric(lines,'integration_pg_sql_calls_total',x['calls'],service=x['rolname'])
        metric(lines,'integration_pg_sql_exec_seconds_total',x['seconds'],service=x['rolname'])

def collect():
    lines=[]; start=time.time()
    def source(name,fn):
        try:
            fn(); metric(lines,'integration_source_up',1,source=name)
            metric(lines,'integration_collection_success_timestamp_seconds',time.time(),source=name)
        except Exception as e:
            metric(lines,'integration_source_up',0,source=name)
            print(name+': '+type(e).__name__,flush=True)
    if os.name=='nt': source('windows',lambda:windows(lines))
    def docker():
        ids=run(['docker','ps','-aq','--filter','label=com.docker.compose.project=ticket-integration']).split()
        running=[]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for cid,(metrics,d) in zip(ids,pool.map(container_or_failure,ids)):
                lines.extend(metrics)
                if d['running']: running.append((cid,d))
        if running:
            # postgres에 /bin/sh와 /proc가 있으므로 distroless exporter를 선택하지 않는다.
            pg=next((cid for cid,d in running if d['service']=='postgres'),None)
            if pg:
                source('vm',lambda:vm(lines,pg))
                source('postgres-diagnostics',lambda:postgres(lines,pg))
                source('postgres-statements',lambda:postgres_statements(lines,pg))
        metric(lines,'integration_discovered_containers',len(ids))
    source('docker',docker)
    def foreign_containers():
        # 측정 도중 생긴 외부 컨테이너도 보이게 한다(#336). 통합시험 CPU를 모르므로 겹침과 관계없이 모두 센다.
        result=foreign.scan(None,run=run)
        metric(lines,'integration_foreign_running_containers',len(result['containers']))
        for c in result['containers']:
            metric(lines,'integration_foreign_container_info',1,container_name=c['name'],project=c['project'] or '',cpuset=c['cpuset'])
    source('foreign',foreign_containers)
    for journey_file in sorted((ROOT/'script'/'integration').glob('scenario[0-9]*-metrics.prom')):
        try:
            lines.extend(journey_file.read_text(encoding='utf-8').splitlines())
        except OSError:
            metric(lines,'integration_source_up',0,source=journey_file.stem+'-file')
    metric(lines,'integration_collection_duration_seconds',time.time()-start)
    return ('\n'.join(lines)+'\n').encode()

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path!='/metrics': self.send_error(404);return
        with LOCK: data=SAMPLE
        self.send_response(200);self.send_header('Content-Type','text/plain; version=0.0.4');self.end_headers();self.wfile.write(data)
    def log_message(self,*args): pass

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--once',action='store_true')
    parser.add_argument('--port',type=int,default=19100)
    args=parser.parse_args()
    if args.once:
        print(collect().decode());raise SystemExit()
    out=ROOT/'script'/'integration'/('observation-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    out.mkdir(parents=True,exist_ok=True)
    events=(out/'docker-events.jsonl').open('w',encoding='utf-8')
    event_proc=subprocess.Popen(['docker','events','--filter','label=com.docker.compose.project=ticket-integration','--format','{{json .}}'],stdout=events,stderr=subprocess.DEVNULL)
    def loop():
        global SAMPLE
        while True:
            start=time.monotonic();data=collect()
            with LOCK: SAMPLE=data
            with (out/'samples.jsonl').open('a',encoding='utf-8') as f:
                f.write(json.dumps({'timestamp':time.time(),'metrics':data.decode()})+'\n')
            time.sleep(max(1,15-(time.monotonic()-start)))
    threading.Thread(target=loop,daemon=True).start()
    print('Metrics :'+str(args.port)+'/metrics; raw '+str(out),flush=True)
    try: http.server.ThreadingHTTPServer(('0.0.0.0',args.port),Handler).serve_forever()
    finally: event_proc.terminate();events.close()
