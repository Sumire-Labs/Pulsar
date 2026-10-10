from pathlib import Path
import json,sys,os
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,os.environ.get('PLOT_PACKAGE_DIR','E:/Github/pulsar-050-bench-20261010/tools/python-packages'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
D=json.loads((R/'analysis/results.json').read_text(encoding='utf-8'))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
def m(f,p,e,pool,v,k):return next(g['metrics'][k] for g in D['groups'] if (g['family'],g['phase'],g['engine'],g['pool'],g['variant'])==(f,p,e,pool,v))
def save(fig,name):
    for ext in ['png','svg']:
        path=R/'analysis'/(name+'.'+ext);fig.savefig(path,dpi=180,bbox_inches='tight',facecolor='white')
        if ext=='svg':path.write_bytes(('\n'.join(x.rstrip() for x in path.read_text(encoding='utf-8').splitlines())+'\n').encode())
    plt.close(fig)
fig,axes=plt.subplots(1,2,figsize=(12,5))
cases=[('pulsar','auto','Pulsar 0.5.0\nauto / 10 workers','#2A9D67'),('pulsar','1','Pulsar 0.5.0\n1 shared worker','#286C55'),('starlight','auto','Starlight-1.12\nsynchronous','#9365B8')]
for ax,(f,p,title,scale,unit) in zip(axes,[('blocks','block_place','Glowstone placement',1e3,'Completion time (µs)'),('sky','sky_remove','Open tall Sky column',1e6,'Completion time (ms)')]):
    for i,(e,pool,label,c) in enumerate(cases):
        x=m(f,p,e,pool,'original','completion_nanos');y=x['median']/scale
        ax.bar(i,y,color=c);ax.errorbar(i,y,yerr=[[(x['median']-x['min'])/scale],[(x['max']-x['median'])/scale]],fmt='none',capsize=5,color='#333')
        ax.annotate(f'{y:.3g}',(i,x['max']/scale),xytext=(0,7),textcoords='offset points',ha='center')
    ax.set_xticks(range(3),[x[2] for x in cases],fontsize=10);ax.set_ylim(0,ax.get_ylim()[1]*1.15);ax.set_title(title);ax.set_ylabel(unit);ax.grid(axis='y',alpha=.15)
fig.suptitle('Worker-count control — original engine binaries, no profiling',fontsize=15)
fig.text(.5,-.05,'Median of 4 independent JVM run means; whiskers show run min–max. Same workloads and frozen settings.\nOnly Pulsar shared-worker count changes. Single-location latency does not establish the best bulk/multi-player setting.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'worker-count-latency')
fig,axes=plt.subplots(1,2,figsize=(11,4.5))
for ax,(f,p,lane,title) in zip(axes,[('blocks','block_place','block_','Placement: Block flood'),('sky','sky_remove','sky_','Sky opening: Sky flood')]):
    for i,pool in enumerate(['auto','1']):
        x=m(f,p,'pulsar',pool,'on',lane+'flood_nanos');y=x['median']/1e3
        ax.bar(i,y,color=['#2A9D67','#286C55'][i]);ax.errorbar(i,y,yerr=[[(x['median']-x['min'])/1e3],[(x['max']-x['median'])/1e3]],fmt='none',capsize=5,color='#333')
    ax.set_xticks([0,1],['Automatic / 10','1 shared worker']);ax.set_ylabel('Flood wall time (µs/captured edit)');ax.set_title(title);ax.grid(axis='y',alpha=.15)
fig.suptitle('Exploratory stage supplement — identical Pulsar probe binary',fontsize=15)
fig.text(.5,-.05,'Only 2 independent JVM runs per setting; median and run min–max. Same sampled edit indices.\nWall time includes preemption and probe effects; use the uninstrumented n4 comparison for latency conclusions.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'worker-stage-supplement')
