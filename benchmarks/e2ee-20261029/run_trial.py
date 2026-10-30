import argparse, ctypes, hashlib, json, os, re, shutil, subprocess, threading, time
from ctypes import wintypes
from pathlib import Path

ROOT=Path(__file__).resolve().parent
BASE=ROOT/'server-template'
JAVA=Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe')
JCMD=JAVA.with_name('jcmd.exe')
PULSAR=ROOT.parents[1]/'build/libs/pulsar-0.5.4-dev.50.local.jar'
HARNESS=ROOT/'tools/lightbench/build/libs/lightbench-1.1.0-bench9-e2ee2.jar'
GAMEPLAY_LIGHT='RGB_MAX'
FLAGS=['-Xms8G','-Xmx8G','-XX:+UseG1GC','-XX:MaxGCPauseMillis=200','-XX:MetaspaceSize=384M',
       '-Dfile.encoding=UTF-8',r'-Djdk.net.unixdomain.tmpdir=D:\DevCache\pulsar-sockets']

class Memory(ctypes.Structure):
    _fields_=[('cb',wintypes.DWORD),('faults',wintypes.DWORD)]+[(name,ctypes.c_size_t) for name in
        ('peakWorking','working','peakPaged','paged','peakNonPaged','nonPaged','pagefile','peakPagefile','private')]

def sha(path):
    with path.open('rb') as file:return hashlib.file_digest(file,'sha256').hexdigest()

def prepare(name,variant,world_source=None):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}',name):raise ValueError('Invalid run name')
    runs_root=(ROOT/'runs').resolve();target=(runs_root/name).resolve()
    if not target.is_relative_to(runs_root):raise ValueError('Run directory escapes benchmark root')
    if target.exists():raise RuntimeError('Run directory already exists; use a fresh run name')
    consent=ROOT/'eula-consent.json'
    if not consent.exists() or json.loads(consent.read_text()) .get('accepted') is not True:
        raise RuntimeError('Explicit user EULA consent has not been recorded; server launch is blocked')
    shutil.copytree(BASE,target,ignore=shutil.ignore_patterns('*.log','logs','crash-reports','benchmark-results','start-server.*','server-setup-config.yaml'))
    if world_source:
        source=Path(world_source).resolve()
        shutil.copytree(source,target/'BBOP-Extended',ignore=shutil.ignore_patterns('session.lock','lightbench-results'))
        (target/'world-source.json').write_text(json.dumps({'source':str(source),'files':{
            str(p.relative_to(source)):sha(p) for p in sorted(source.rglob('*')) if p.is_file() and 'lightbench-results' not in p.parts and p.name!='session.lock'}},indent=2))
    if variant!='alfheim':
        alf=target/'mods/Alfheim-1.6.jar'
        if not alf.exists():raise RuntimeError('Unexpected Alfheim inventory')
        # Move exactly one benchmark-copy jar outside mods; the template is untouched.
        (target/'disabled-engines').mkdir();alf.rename(target/'disabled-engines'/alf.name)
        shutil.copy2(PULSAR,target/'mods'/PULSAR.name)
    shutil.copy2(HARNESS,target/'mods'/HARNESS.name)
    # Retain the same Pulsar config on the Alfheim side, even though it is not loaded.
    mode='RGB' if variant=='rgb' else 'SCALAR'
    cfg=target/'config/pulsar.cfg'
    if cfg.exists():
        value=cfg.read_text(encoding='utf-8');value=re.sub(r'(?m)^(\s*S:lightingMode=).*$',r'\g<1>'+mode,value)
    else:
        value='general {\n I:configVersion=2\n S:lightingMode='+mode+'\n S:rgbGameplayLight=RGB_MAX\n features {\n I:thaumcraftCrystalLightLevel=0\n }\n}\n'
    value=re.sub(r'(?m)^(\s*S:rgbGameplayLight=).*$',r'\g<1>'+GAMEPLAY_LIGHT,value)
    cfg.write_text(value,encoding='utf-8')
    (target/'eula.txt').write_text('eula=true\n',encoding='ascii')
    inventory={file.name:sha(file) for file in sorted((target/'mods').iterdir()) if file.is_file()}
    (target/'benchmark-inventory.json').write_text(json.dumps({'variant':variant,'mods':inventory,'java':str(JAVA),'flags':FLAGS,
       'pack':'v1.87.3','cleanroom':'0.6.13-alpha','seed':'20261029','view_distance':12,'gameplay_light':GAMEPLAY_LIGHT},indent=2),encoding='utf-8')
    return target

def run(name,variant,pilot=False,world_source=None,profile=False,check_bedrock=False,compat_only=False):
    directory=prepare(name,variant,world_source)
    logpath=directory/'console.log'
    result={'name':name,'variant':variant,'pilot':pilot,'world_source':world_source,'started_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
    result.update(profile=profile,check_bedrock=check_bedrock,compat_only=compat_only)
    launch_started=time.monotonic()
    with logpath.open('wb') as log:
        process=subprocess.Popen([str(JAVA),*FLAGS,'-jar','cleanroom-0.6.13-alpha.jar','nogui'],cwd=directory,
            stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    result['pid']=process.pid
    (directory/'process.json').write_text(json.dumps(result,indent=2))
    kernel=ctypes.WinDLL('kernel32',use_last_error=True);psapi=ctypes.WinDLL('psapi',use_last_error=True)
    kernel.OpenProcess.restype=wintypes.HANDLE;kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    psapi.GetProcessMemoryInfo.argtypes=[wintypes.HANDLE,ctypes.POINTER(Memory),wintypes.DWORD]
    handle=kernel.OpenProcess(0x0400|0x0010,False,process.pid)
    stop=threading.Event();phase=['startup']
    def monitor():
        with (directory/'process-memory.csv').open('w',encoding='utf-8') as out:
            out.write('elapsed_s,phase,working_set,private_bytes,peak_working_set\n');start=time.monotonic()
            while not stop.wait(.5):
                mem=Memory();mem.cb=ctypes.sizeof(mem)
                if psapi.GetProcessMemoryInfo(handle,ctypes.byref(mem),mem.cb):
                    out.write(f'{time.monotonic()-start:.3f},{phase[0]},{mem.working},{mem.private},{mem.peakWorking}\n');out.flush()
    watcher=threading.Thread(target=monitor,daemon=True);watcher.start()
    offset=[0]
    def send(command):
        print(name,'COMMAND',command,flush=True);process.stdin.write((command+'\n').encode());process.stdin.flush()
    def wait_for(pattern,timeout):
        deadline=time.monotonic()+timeout;buffer='';next_progress=time.monotonic()+30
        with logpath.open('r',encoding='utf-8',errors='replace') as source:
            source.seek(offset[0])
            while time.monotonic()<deadline:
                data=source.read()
                if data:
                    buffer=(buffer+data)[-300000:]
                    if re.search(pattern,buffer):offset[0]=source.tell();return buffer
                if process.poll() is not None:raise RuntimeError('Server exited '+str(process.returncode)+'; see '+str(logpath))
                if time.monotonic()>next_progress:
                    lines=[line for line in buffer.splitlines() if line.strip()]
                    print(name,'WAIT',phase[0],(lines[-1] if lines else 'no new output')[-220:],flush=True);next_progress=time.monotonic()+30
                time.sleep(.5)
        raise TimeoutError('Timed out waiting for '+pattern)
    def diagnostic(label):
        phase[0]='gc-snapshot'
        send('benchmetrics snapshot '+label+'-fence');wait_for('E2EE_METRICS_DONE '+label+'-fence',300)
        for command,suffix in [('GC.class_histogram','histogram'),('GC.heap_info','heap')]:
            with (directory/(label+'-'+suffix+'.txt')).open('wb') as out:
                subprocess.run([str(JCMD),str(process.pid),command],stdout=out,stderr=subprocess.STDOUT,timeout=180,check=True,creationflags=subprocess.CREATE_NO_WINDOW)
        send('benchmetrics snapshot '+label);wait_for('E2EE_METRICS_DONE '+label,300)
    try:
        wait_for(r'Done \([0-9.,]+s\)!',1200)
        result['startup_s']=time.monotonic()-launch_started
        print(name,'READY',flush=True)
        send('gamerule doMobSpawning false');send('gamerule randomTickSpeed 0');send('gamerule doDaylightCycle false');send('gamerule doWeatherCycle false')
        send('time set 6000');send('kill @e')
        phase[0]='settle';time.sleep(15)
        if compat_only:
            phase[0]='compatibility-check';send('benchmetrics bedrock bedrock-check')
            checked=wait_for('E2EE_BEDROCK_(PASS|FAIL)',300)
            if 'E2EE_BEDROCK_FAIL' in checked:raise RuntimeError('Bedrock Ores light transition check failed')
            result['completed']=True
            return
        diagnostic('idle')
        send('benchmetrics audit idle-audit');wait_for('E2EE_METRICS_DONE idle-audit',300)
        if profile:
            subprocess.run([str(JCMD),str(process.pid),'JFR.start','name=pulsar-idle','settings=profile','jdk.ExecutionSample#period=1ms'],check=True)
        phase[0]='idle-ticks';send('benchmetrics ticks idle-ticks 20');wait_for('E2EE_METRICS_DONE idle-ticks',180)
        if profile:
            subprocess.run([str(JCMD),str(process.pid),'JFR.stop','name=pulsar-idle','filename='+str(directory/'idle.jfr')],check=True)
        phase[0]='generation'
        send('lightbench gen 2 1 2' if pilot else 'lightbench gen 4 3 4')
        wait_for('raw result saved:',1200)
        phase[0]='settle-after-generation';send('kill @e');time.sleep(15)
        diagnostic('post-generation')
        phase[0]='loaded-ticks';send('benchmetrics ticks loaded-ticks 20');wait_for('E2EE_METRICS_DONE loaded-ticks',180)
        if check_bedrock:
            phase[0]='compatibility-check';send('benchmetrics bedrock bedrock-check')
            checked=wait_for('E2EE_BEDROCK_(PASS|FAIL)',300)
            if 'E2EE_BEDROCK_FAIL' in checked:raise RuntimeError('Bedrock Ores light transition check failed')
        phase[0]='save';send('save-all flush');time.sleep(5)
        result['completed']=True
    except Exception as error:
        result['completed']=False;result['error']=str(error);print(name,'FAILED',error,flush=True)
    finally:
        phase[0]='shutdown'
        if process.poll() is None:
            send('stop')
            try:process.wait(timeout=120)
            except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=30)
        stop.set();watcher.join(timeout=5);kernel.CloseHandle(handle)
        result['exit_code']=process.returncode;result['finished_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
        if(process.returncode!=0):result['completed']=False;result.setdefault('error','Nonzero server exit')
        (directory/'trial-status.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        if not result['completed']:raise SystemExit(1)
    print(name,'COMPLETE',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('name');parser.add_argument('variant',choices=['alfheim','scalar','rgb']);parser.add_argument('--pilot',action='store_true')
    parser.add_argument('--world-source')
    parser.add_argument('--profile',action='store_true')
    parser.add_argument('--pulsar-jar',type=Path)
    parser.add_argument('--harness-jar',type=Path)
    parser.add_argument('--check-bedrock',action='store_true')
    parser.add_argument('--compat-only',action='store_true')
    parser.add_argument('--gameplay-light',choices=['RGB_MAX','VANILLA'],default='RGB_MAX')
    args=parser.parse_args()
    if args.pulsar_jar:PULSAR=args.pulsar_jar.resolve()
    if args.harness_jar:HARNESS=args.harness_jar.resolve()
    GAMEPLAY_LIGHT=args.gameplay_light
    run(args.name,args.variant,args.pilot,args.world_source,args.profile,args.check_bedrock,args.compat_only)
