from pathlib import Path
import json,sys,os,statistics
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,os.environ.get('PLOT_PACKAGE_DIR','E:/Github/pulsar-050-bench-20261010/tools/python-packages'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
D=json.loads((R/'analysis/results.json').read_text(encoding='utf-8'))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
def m(f,p,e,v,k):return next(g['metrics'][k] for g in D['groups'] if (g['family'],g['phase'],g['engine'],g['variant'])==(f,p,e,v))
def save(fig,name):
    for ext in ['png','svg']:
        path=R/'analysis'/(name+'.'+ext);fig.savefig(path,dpi=180,bbox_inches='tight',facecolor='white')
        if ext=='svg':path.write_bytes(('\n'.join(x.rstrip() for x in path.read_text(encoding='utf-8').splitlines())+'\n').encode())
    plt.close(fig)
fig,axes=plt.subplots(1,2,figsize=(12,5))
for ax,(f,p,title,scale,unit) in zip(axes,[('blocks','block_place','Glowstone placement',1e3,'Native completion (µs)'),('sky','sky_remove','Open tall Sky column',1e6,'Native completion (ms)')]):
    for i,e in enumerate(['pulsar','starlight']):
        for j,(v,color) in enumerate([('original','#4C78A8'),('off','#999999'),('on','#E59E3E')]):
            s=m(f,p,e,v,'completion_nanos');y=s['median']/scale;x=i+(j-1)*.25
            ax.bar(x,y,width=.23,color=color,label=v if i==0 else None)
            ax.errorbar(x,y,yerr=[[(s['median']-s['min'])/scale],[(s['max']-s['median'])/scale]],fmt='none',capsize=3,color='#333')
            ax.annotate(f'{y:.3g}',(x,s['max']/scale),xytext=(0,5),textcoords='offset points',ha='center',fontsize=9)
    ax.set_xticks([0,1],['Pulsar 0.5.0','Starlight-1.12']);ax.set_title(title);ax.set_ylabel(unit);ax.grid(axis='y',alpha=.15);ax.set_ylim(0,ax.get_ylim()[1]*1.15)
axes[0].legend(loc='upper center',ncol=3,fontsize=9);fig.suptitle('Matched cross-engine study: native latency and probe controls',fontsize=15)
fig.text(.5,-.04,'Median of 4 independent JVM run means; whiskers show run min–max. About1/16 edits captured in on condition.\nWhole-run overhead is diluted by sampling; captured-edit perturbation is reported separately.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'native-controls')
stages=[('setup_nanos','Cache setup','#4C78A8'),('sections_nanos','Section handling','#A9B5C5'),('propagation_nanos','Propagation','#2A9D67'),('publish_nanos','Publication','#F58518'),('cleanup_nanos','Cleanup','#9365B8')]
fig,axes=plt.subplots(1,2,figsize=(12,4.6))
for ax,(f,p,lane,title) in zip(axes,[('blocks','block_place','block_','Placement: Block lane'),('sky','sky_remove','sky_','Sky opening: Sky lane')]):
    left=np.zeros(2)
    for key,label,c in stages:
        y=np.array([m(f,p,e,'on',lane+key)['median']/1e3 for e in ['pulsar','starlight']]);ax.barh([0,1],y,left=left,label=label,color=c);left+=y
    ax.set_yticks([0,1],['Pulsar','Starlight-1.12']);ax.invert_yaxis();ax.set_xlabel('Engine stage wall time (µs/captured edit)');ax.set_title(title);ax.grid(axis='x',alpha=.15)
axes[0].legend(ncol=2,loc='upper left',bbox_to_anchor=(0,-.2),fontsize=9)
fig.suptitle('Matched engine boundaries — sampled diagnostic edits',fontsize=16)
fig.text(.5,-.18,'Median of 4 run means for the same selected edit indices. These bars cover the dominant lane only.\nQueue/coordination and the other lane are excluded; stage timers may include preemption and probe perturbation.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'matched-stages')
fig,axes=plt.subplots(1,2,figsize=(12,4.6))
for ax,(f,p,title) in zip(axes,[('blocks','block_place','Glowstone placement'),('sky','sky_remove','Open tall Sky column')]):
    left=np.zeros(2)
    for k,label,c in [('before_first_engine_nanos','Before first engine','#4C78A8'),('engine_span_nanos','First engine → last engine end','#2A9D67'),('after_last_engine_nanos','After last engine','#F58518')]:
        y=np.array([m(f,p,e,'on',k)['median']/1e3 for e in ['pulsar','starlight']]);ax.barh([0,1],y,left=left,label=label,color=c);left+=y
    ax.set_yticks([0,1],['Pulsar','Starlight-1.12']);ax.invert_yaxis();ax.set_xlabel('Wall-clock envelope (µs/captured edit)');ax.set_title(title);ax.grid(axis='x',alpha=.15)
axes[0].legend(loc='upper left',bbox_to_anchor=(0,-.2),fontsize=9)
fig.suptitle('Caller completion envelope — non-overlapping intervals',fontsize=16)
fig.text(.5,-.17,'Intervals are disjoint per edit; separate medians need not add exactly. Engine span includes both lanes and gaps.\nThis is not total worker CPU, client mesh completion or presented FPS.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'completion-envelope')
fig,axes=plt.subplots(1,2,figsize=(11,4.6))
for ax,(f,p,title) in zip(axes,[('blocks','block_place','Glowstone placement'),('sky','sky_remove','Open tall Sky column')]):
    for i,e in enumerate(['pulsar','starlight']):
        values=[c['captured_probe_excess_nanos']/1000 for c in D['probe_contrasts'] if (c['family'],c['phase'],c['engine'])==(f,p,e)];y=statistics.median(values)
        ax.bar(i,y,color=['#2A9D67','#9365B8'][i]);ax.errorbar(i,y,yerr=[[y-min(values)],[max(values)-y]],fmt='none',capsize=5,color='#333')
    ax.axhline(0,color='#777',linewidth=1);ax.set_xticks([0,1],['Pulsar','Starlight-1.12']);ax.set_title(title);ax.set_ylabel('Estimated capture excess (µs)');ax.grid(axis='y',alpha=.15)
fig.suptitle('Captured-edit probe perturbation: selected vs other edits',fontsize=15)
fig.text(.5,-.05,'(On selected − on others) − (disabled selected − disabled others), by repetition; median and run min–max.\nNegative values can arise from noise/order effects. This is not an exact per-stage correction factor.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'capture-perturbation')
