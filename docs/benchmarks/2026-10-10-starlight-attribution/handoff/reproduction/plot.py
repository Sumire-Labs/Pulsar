from pathlib import Path
import json,sys,os,statistics as st
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,os.environ.get('PLOT_PACKAGE_DIR','E:/Github/pulsar-050-bench-20261010/tools/python-packages'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
D=json.loads((R/'analysis/summary.json').read_text(encoding='utf-8'))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
cases=[('pulsar','legacy','Pulsar\nlegacy','#4C78A8'),('pulsar','future','Pulsar\ndirect future','#E49736'),('pulsar','gated','Held + worker\nfuture','#D35D6E'),('pulsar','gatedpoll','Held + worker\npoll','#B08E3E'),('pulsar','caller','Held + caller','#2A9D67'),('starlight','legacy','Starlight\n1.12','#9365B8')]
phases=[('block_place','Glowstone placement',1,'Completion (µs)'),('block_remove','Glowstone removal',1,'Completion (µs)'),('sky_remove','Open tall Sky column',1000,'Completion (ms)'),('sky_place','Close tall Sky column',1000,'Completion (ms)')]
def save(fig,name):
 for ext in ['png','svg']:
  p=R/'analysis'/f'{name}.{ext}';fig.savefig(p,dpi=180,bbox_inches='tight',facecolor='white')
  if ext=='svg':p.write_bytes(('\n'.join(line.rstrip() for line in p.read_text(encoding='utf-8').splitlines())+'\n').encode('utf-8'))
 plt.close(fig)
fig,axes=plt.subplots(2,2,figsize=(13,9))
for ax,(phase,title,scale,unit) in zip(axes.flat,phases):
 for i,(engine,mode,label,color) in enumerate(cases):
  d=D['groups'][f'{phase}/{engine}/{mode}']['completion_us'];y=d['median']/scale
  ax.bar(i,y,color=color)
  ax.errorbar(i,y,yerr=[[(d['median']-d['min'])/scale],[(d['max']-d['median'])/scale]],fmt='none',capsize=4,color='#333')
  ax.annotate(f'{y:.3g}',(i,d['max']/scale),xytext=(0,5),textcoords='offset points',ha='center',fontsize=9)
 ax.set_xticks(range(6),[x[2] for x in cases],fontsize=8);ax.set_ylabel(unit);ax.set_title(title);ax.set_ylim(0,ax.get_ylim()[1]*1.15);ax.grid(axis='y',alpha=.15)
fig.suptitle('Async dispatch and completion waiting — current Pulsar scheduler',fontsize=15)
fig.text(.5,-.015,'Median of 4 fresh-JVM run means; whiskers: run min–max. Primary trials have timing probes disabled.\nHeld + worker and held + caller enqueue the complete edit before execution. This is an edit-latency experiment, not throughput.',ha='center',fontsize=9)
fig.tight_layout();save(fig,'handoff-latency')
if D['sampled_runs']==12:
 fig,axes=plt.subplots(2,2,figsize=(12,8))
 parts=[('submit_us','Submission','#80B1D3'),('dispatch_us','Dispatch interval','#FDB462'),('body_us','Task bodies','#8DD3C7'),('between_tasks_us','Between tasks','#BEBADA'),('tail_us','Completion observation','#FB8072')]
 for ax,(phase,title,scale,unit) in zip(axes.flat,phases):
  for i,mode in enumerate(['gated','gatedpoll','caller']):
   rows=[x for x in D['sampled'] if x['phase']==phase and x['mode']==mode];bottom=0
   for field,label,color in parts:
    y=st.mean(x[field] for x in rows)/scale
    ax.bar(i,y,bottom=bottom,label=label if i==0 else None,color=color);bottom+=y
   ax.annotate(f'{bottom:.3g}',(i,bottom),xytext=(0,5),textcoords='offset points',ha='center')
  ax.set_xticks([0,1,2],['Held + worker\nfuture','Held + worker\npoll','Held + caller']);ax.set_ylabel(unit);ax.set_title(title);ax.set_ylim(0,ax.get_ylim()[1]*1.12)
 handles,labels=axes[0,0].get_legend_handles_labels();fig.legend(handles,labels,loc='upper center',bbox_to_anchor=(.5,.96),ncol=5,frameon=False,fontsize=9)
 fig.suptitle('Sampled boundary decomposition — diagnostic follow-up',fontsize=15)
 fig.text(.5,-.015,'Arithmetic mean of 2 captured run means per condition; same deterministic 1/16 samples. Probes warmed before measurement.\nTask bodies do not overlap. Includes timing overhead; do not subtract these bars from uninstrumented primary medians.',ha='center',fontsize=9)
 fig.tight_layout(rect=[0,0,1,.92]);save(fig,'handoff-boundaries')
