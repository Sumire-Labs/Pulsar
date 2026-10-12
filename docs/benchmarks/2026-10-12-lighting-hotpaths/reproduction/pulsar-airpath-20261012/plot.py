from pathlib import Path
import json,sys
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,'E:/Github/pulsar-050-bench-20261010/tools/python-packages')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
D=json.loads((R/'analysis/summary.json').read_text(encoding='utf-8'))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
cases=[('pulsar','base','Prefilter base','#4C78A8'),('pulsar','block','Selected Block air path','#2A9D67'),('starlight','base','Starlight-1.12','#9365B8')]
phases=[('block_place','Block placement',1,'Completion (µs)'),('block_remove','Block removal',1,'Completion (µs)'),('sky_remove','Sky opening',1000,'Completion (ms)'),('sky_place','Sky closing',1000,'Completion (ms)')]
fig,axes=plt.subplots(2,2,figsize=(11,8))
for ax,(phase,title,scale,unit) in zip(axes.flat,phases):
 for i,(engine,variant,label,color) in enumerate(cases):
  g=next(x for x in D['groups'] if x['engine']==engine and x['variant']==variant and x['phase']==phase)['completion_us'];y=g['median']/scale
  ax.bar(i,y,color=color);ax.errorbar(i,y,yerr=[[(g['median']-g['min'])/scale],[(g['max']-g['median'])/scale]],fmt='none',capsize=4,color='#333')
  ax.annotate(f'{y:.3g}',(i,g['max']/scale),xytext=(0,5),textcoords='offset points',ha='center')
 ax.set_xticks(range(3),[x[2] for x in cases],fontsize=9);ax.set_title(title);ax.set_ylabel(unit);ax.set_ylim(0,ax.get_ylim()[1]*1.12);ax.grid(axis='y',alpha=.15)
fig.suptitle('Selected lighting hot paths — current comparison',fontsize=15)
fig.text(.5,-.015,'Median of 4 fresh-JVM run means; whiskers: run min–max. Probes disabled; every edit and volume qualified.\nSame 10,000 warmup + 10,000 measured pairs per phase, auto10 workers and 5µs recent-worker wait. Base already includes Sky prefilter.',ha='center',fontsize=9)
fig.tight_layout()
for ext in ['png','svg']:
 p=R/'analysis'/f'selected-comparison.{ext}';fig.savefig(p,dpi=180,bbox_inches='tight',facecolor='white')
 if ext=='svg':p.write_bytes(('\n'.join(x.rstrip() for x in p.read_text(encoding='utf-8').splitlines())+'\n').encode())
plt.close(fig)
