from pathlib import Path
import sys,os,json
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,os.environ.get('PLOT_PACKAGE_DIR','E:/Github/pulsar-050-bench-20261010/tools/python-packages'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
D=json.loads((R/'analysis/results.json').read_text(encoding='utf-8'))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
cases=[('pulsar','baseline','Pulsar\nstock','#4C78A8'),('pulsar','mask','Pulsar\ndirection mask','#2A9D67'),('pulsar','late','Pulsar\nlate partner','#D68B28'),('pulsar','both','Pulsar\nboth','#286C55'),('starlight','baseline','Starlight\n1.12','#9365B8')]
fig,axes=plt.subplots(1,2,figsize=(13,5))
for ax,(f,p,title,scale,unit) in zip(axes,[('blocks','block_place','Glowstone placement',1e3,'Completion time (µs)'),('sky','sky_remove','Open tall Sky column',1e6,'Completion time (ms)')]):
    for i,(e,v,label,c) in enumerate(cases):
        x=next(g['metrics']['completion_nanos'] for g in D['groups'] if (g['family'],g['phase'],g['engine'],g['variant'])==(f,p,e,v));y=x['median']/scale
        ax.bar(i,y,color=c);ax.errorbar(i,y,yerr=[[(x['median']-x['min'])/scale],[(x['max']-x['median'])/scale]],fmt='none',capsize=4,color='#333')
        ax.annotate(f'{y:.3g}',(i,x['max']/scale),xytext=(0,6),textcoords='offset points',ha='center',fontsize=9)
    ax.set_xticks(range(5),[x[2] for x in cases],fontsize=9);ax.set_ylim(0,ax.get_ylim()[1]*1.15);ax.set_title(title);ax.set_ylabel(unit);ax.grid(axis='y',alpha=.15)
fig.suptitle('Controlled implementation candidates — default automatic10 shared workers',fontsize=15)
fig.text(.5,-.05,'Median of 4 independent JVM run means; whiskers show run min–max. Native completion, no profiling.\nSame geometry/work/settings; full stored-light qualification. No source-face behavior or fallback notification is removed.',ha='center',fontsize=9)
fig.tight_layout()
for ext in ['png','svg']:
    path=R/'analysis'/('candidate-latency.'+ext);fig.savefig(path,dpi=180,bbox_inches='tight',facecolor='white')
    if ext=='svg':path.write_bytes(('\n'.join(x.rstrip() for x in path.read_text(encoding='utf-8').splitlines())+'\n').encode())
plt.close(fig)
