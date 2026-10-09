from pathlib import Path
import sys,json
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(Path('E:/Github/pulsar-050-bench-20261010/tools/python-packages')))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
d=json.loads((R/'analysis/results.json').read_text())
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
def metric(f,p,v,k):return next(g['metrics'][k] for g in d['groups'] if g['family']==f and g['phase']==p and g['variant']==v)
def save(fig,name):
    for ext in ['png','svg']:
        path=R/'analysis'/(name+'.'+ext);fig.savefig(path,dpi=180,bbox_inches='tight',facecolor='white')
        if ext=='svg':path.write_text('\n'.join(x.rstrip() for x in path.read_text().splitlines())+'\n')
    plt.close(fig)
fig,axes=plt.subplots(1,2,figsize=(12,5))
for ax,(f,p,title,scale,unit) in zip(axes,[('blocks','block_place','Glowstone placement',1e3,'Completion time (µs)'),('sky','sky_remove','Open tall Sky column',1e6,'Completion time (ms)')]):
    for i,(v,c) in enumerate([('baseline','#4C78A8'),('off','#888888'),('on','#E59E3E')]):
        m=metric(f,p,v,'completion_nanos');y=m['median_run_mean']/scale
        ax.bar(i,y,color=c);ax.errorbar(i,y,yerr=[[(m['median_run_mean']-m['min_run_mean'])/scale],[(m['max_run_mean']-m['median_run_mean'])/scale]],fmt='none',capsize=5,color='#333')
        ax.annotate(f'{y:.3g}',(i,m['max_run_mean']/scale),xytext=(0,8),textcoords='offset points',ha='center')
    ax.set_xticks([0,1,2],['Original','Probe build\ndisabled','Probe\nenabled']);ax.set_ylim(0,ax.get_ylim()[1]*1.2);ax.set_title(title);ax.set_ylabel(unit);ax.grid(axis='y',alpha=.15)
fig.suptitle('Diagnostic probe overhead controls',fontsize=16)
fig.text(.5,-.05,'Median of 4 independent JVM run means; whiskers show run min–max. Same workloads and frozen settings.\nEnabled measurements are diagnostic wall times, not replacement release benchmark scores.',ha='center',fontsize=10)
fig.tight_layout();save(fig,'probe-overhead')
fig,axes=plt.subplots(1,2,figsize=(12,5))
stages=[('setup','Cache setup','#4C78A8'),('sections','Section reconciliation','#A9B5C5'),('propagation','Propagation','#2A9D67'),('publish','Publish results','#F58518'),('cleanup','Cache cleanup','#9365B8')]
for ax,(f,p,title) in zip(axes,[('blocks','block_place','Glowstone placement'),('sky','sky_remove','Open tall Sky column')]):
    left=np.zeros(2)
    for key,label,color in stages:
        values=np.array([metric(f,p,'on',lane+'_engine_'+key+'_nanos')['median_run_mean']/1e3 for lane in ['sky','block']])
        ax.barh([0,1],values,left=left,label=label,color=color);left+=values
    ax.set_yticks([0,1],['Sky lane','Block lane']);ax.invert_yaxis();ax.set_xlabel('Summed engine stage wall time per edit (µs)');ax.set_title(title);ax.grid(axis='x',alpha=.15)
axes[0].legend(loc='upper left',bbox_to_anchor=(0,-.17),ncol=2,fontsize=9)
fig.suptitle('Pulsar worker engine stages — probe enabled',fontsize=16)
fig.text(.5,-.16,'Stage values: median of 4 per-run means. Queue delay and task bookkeeping are excluded here.\nSky/Block lanes can overlap submission and each other; stacked bars are not end-to-end latency.',ha='center',fontsize=10)
fig.tight_layout();save(fig,'engine-stages')
