"""Plot current summary.csv and a separate historical Vanilla CSV reference."""
import csv
import statistics
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FixedLocator, FuncFormatter, NullLocator

D=Path(__file__).resolve().parent
rows=list(csv.DictReader((D/'summary.csv').open(encoding='utf-8')))
historical=list(csv.DictReader((D.parent/'2026-08-06-light-updates.csv').open(encoding='utf-8-sig')))
phases=['sky_remove','sky_place','block_place','block_remove']
labels=['Open roof column\nSKY increases','Close roof column\nSKY decreases','Place glowstone\nBLOCK increases','Remove glowstone\nBLOCK decreases']
colors={'alfheim':'#777381','pulsar':'#4262e5'}
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'svg.fonttype':'none','axes.titleweight':'bold'})
fig=plt.figure(figsize=(12,10.3))
axes=[fig.add_axes([.195,.535,.35,.29]),fig.add_axes([.62,.535,.35,.29])]
for ax,key,title in zip(axes,['p50_ms','p95_ms'],['Typical latency · p50','Tail latency · p95']):
    for i,phase in enumerate(phases):
        for engine,offset in [('alfheim',-.14),('pulsar',.14)]:
            r=next(r for r in rows if r['phase']==phase and r['engine']==engine)
            v=float(r[key]);y=i+offset
            if key=='p50_ms':
                err=[[v-float(r['min_p50_ms'])],[float(r['max_p50_ms'])-v]]
                ax.errorbar(v,y,xerr=err,fmt='o',color=colors[engine],capsize=4,ms=7,lw=1.6,zorder=3)
            else:ax.plot(v,y,'o',color=colors[engine],ms=7,zorder=3)
            ax.annotate(f'{v:.3f}',(v,y),xytext=(8,0),textcoords='offset points',va='center',fontsize=10,color=colors[engine])
    ax.set_xscale('log');ax.set_xlim(.04,36);ax.set_ylim(3.55,-.6)
    ax.xaxis.set_major_locator(FixedLocator([.05,.1,.2,.5,1,2,5,10,20]))
    ax.xaxis.set_major_formatter(FuncFormatter(lambda x,p:f'{x:g}'));ax.xaxis.set_minor_locator(NullLocator())
    ax.grid(axis='x',color='#e8e9ee',lw=.8);ax.set_axisbelow(True)
    ax.set_title(title,loc='left',pad=15,color='#232638',fontsize=12)
    ax.set_xlabel('Completion time (ms, log scale) · lower is better',labelpad=12,fontsize=9)
    ax.set_yticks(range(4),labels);ax.tick_params(axis='y',length=0,pad=14,labelsize=10)
    if key=='p95_ms':ax.tick_params(axis='y',labelleft=False)
    for spine in ax.spines.values():spine.set_visible(False)
fig.text(.04,.965,'Pulsar 0.3.0 · Light-update completion',fontsize=21,weight='bold',color='#202338')
fig.text(.04,.93,'Current results · 7 September 2026 (JST) · Cleanroom 0.6.12-alpha / Lightbench 1.0.6-completion',fontsize=10.5,color='#575c6b')
fig.legend(handles=[Line2D([0],[0],marker='o',color=colors[e],lw=0,label=l,ms=7) for e,l in [('alfheim','Alfheim 1.6'),('pulsar','Pulsar 0.3.0')]],loc='upper left',bbox_to_anchor=(.034,.91),frameon=False,ncol=2)
fig.text(.04,.465,'Current runs: 200 edits per phase per run; all six JVM runs passed full-volume checks outside timing.',fontsize=10,color='#343849')
fig.text(.04,.443,'Ryzen AI MAX+ 395 · Java 25.0.4.1 · 8 GiB heap. Repeated hot edits; not FPS or TPS measurements.',fontsize=9.5,color='#575c6b')

fig.text(.04,.395,'Historical Vanilla · p50 reference',fontsize=16,weight='bold',color='#915511')
fig.text(.04,.368,'6 August 2026 (JST) · Cleanroom 0.6.8-alpha / Lightbench 1.0.0 / Azul Java 25.0.3 / seed 20260805',fontsize=10,color='#575c6b')
fig.text(.04,.344,'Different conditions and sparse probes: not directly comparable to current results; no speedup ratio.',fontsize=10,weight='bold',color='#915511')
ax=fig.add_axes([.195,.105,.775,.195],facecolor='#fffaf3')
for i,phase in enumerate(phases):
    rr=[r for r in historical if r['engine']=='vanilla' and r['phase']==phase]
    assert len(rr)==3
    vals=[int(r['completion_p50_nanos'])/1e6 for r in rr]
    v=statistics.median(vals)
    ax.errorbar(v,i,xerr=[[v-min(vals)],[max(vals)-v]],fmt='D',color='#b5792b',capsize=4,ms=7,lw=1.6,zorder=3)
    ax.annotate(f'{v:.3f} ms',(max(vals),i),xytext=(9,0),textcoords='offset points',va='center',fontsize=10,color='#915511')
ax.set_xscale('log');ax.set_xlim(1,1800);ax.set_ylim(3.5,-.5)
ax.xaxis.set_major_locator(FixedLocator([1,2,5,10,20,50,100,200,500,1000]))
ax.xaxis.set_major_formatter(FuncFormatter(lambda x,p:f'{x:g}'));ax.xaxis.set_minor_locator(NullLocator())
ax.set_yticks(range(4),['Open roof column','Close roof column','Place glowstone','Remove glowstone'])
ax.tick_params(axis='y',length=0,pad=14,labelsize=10)
ax.grid(axis='x',color='#eee3d5',lw=.8);ax.set_axisbelow(True)
ax.set_xlabel('Historical completion time (ms, log scale) · separate axis range',labelpad=9,fontsize=9)
for spine in ax.spines.values():spine.set_visible(False)
fig.text(.04,.038,'All markers: median of 3 JVM-run percentiles. Whiskers: full range of run p50s, not confidence intervals.',fontsize=9.5,color='#575c6b')
fig.text(.04,.016,'Historical Vanilla was not remeasured for 0.3.0 and did not undergo the current full-volume validation.',fontsize=9.5,color='#915511')
fig.savefig(D/'light-updates.svg',facecolor='white')
fig.savefig(D/'light-updates.png',dpi=160,facecolor='white')
