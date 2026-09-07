"""Compact p50 chart in the original layout; requires Matplotlib."""
import csv
import statistics
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter, NullLocator

D=Path(__file__).resolve().parent
rows=list(csv.DictReader((D/'summary.csv').open(encoding='utf-8')))
historical=list(csv.DictReader((D.parent/'2026-08-06-light-updates.csv').open(encoding='utf-8-sig')))
phases=['sky_remove','sky_place','block_place','block_remove']
labels=[('Skylight increase','remove roof block'),('Skylight decrease','place roof block'),('Block-light increase','place glowstone'),('Block-light decrease','remove glowstone')]
colors={'vanilla':'#6e7781','alfheim':'#d97706','pulsar':'#7c3aed'}
markers={'vanilla':'o','alfheim':'D','pulsar':'s'}
names={'vanilla':'Vanilla*','alfheim':'Alfheim 1.6','pulsar':'Pulsar 0.3.0'}
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'svg.fonttype':'none'})
fig=plt.figure(figsize=(11.2,7))
ax=fig.add_axes([.304,.13,.607,.66])
ax.set_xscale('log');ax.set_xlim(.05,1000);ax.set_ylim(13.4,-.65)
ax.xaxis.set_major_locator(FixedLocator([.05,.1,1,10,100,1000]))
ax.xaxis.set_major_formatter(FuncFormatter(lambda x,p:f'{x:g}'));ax.xaxis.set_minor_locator(NullLocator())
ax.grid(axis='x',color='#d0d7de',lw=.7);ax.set_axisbelow(True)
ax.set_yticks([])
for spine in ['top','left','right']:ax.spines[spine].set_visible(False)
ax.spines['bottom'].set_color('#8c959f')
ax.set_xlabel('Completion latency (ms, logarithmic scale) · lower is better',labelpad=12)
for i,(phase,(label,action)) in enumerate(zip(phases,labels)):
    for j,engine in enumerate(['vanilla','alfheim','pulsar']):
        y=i*3.6+j
        if engine=='vanilla':
            rr=[r for r in historical if r['engine']==engine and r['phase']==phase]
            assert len(rr)==3
            vals=[int(r['completion_p50_nanos'])/1e6 for r in rr]
            v=statistics.median(vals);lo=min(vals);hi=max(vals)
        else:
            r=next(r for r in rows if r['phase']==phase and r['engine']==engine)
            v=float(r['p50_ms']);lo=float(r['min_p50_ms']);hi=float(r['max_p50_ms'])
        ax.errorbar(v,y,xerr=[[v-lo],[hi-v]],fmt=markers[engine],color=colors[engine],capsize=4,ms=6,lw=1.5,zorder=3)
        weight='bold' if engine=='pulsar' else 'normal'
        ax.annotate(f'{v:.3f} ms',(hi,y),xytext=(9,0),textcoords='offset points',va='center',fontsize=9.5,weight=weight,color='#24292f',annotation_clip=False)
        ax.text(-.027,y,names[engine],transform=ax.get_yaxis_transform(),ha='right',va='center',fontsize=10,weight=weight,color='#24292f')
    fy=fig.transFigure.inverted().transform(ax.transData.transform((1,i*3.6+1)))[1]
    fig.text(.018,fy+.009,label,fontsize=10.5,weight='bold',color='#24292f')
    fig.text(.018,fy-.018,action,fontsize=9.5,color='#57606a')
    if i<3:
        sy=fig.transFigure.inverted().transform(ax.transData.transform((1,i*3.6+2.8)))[1]
        fig.add_artist(plt.Line2D([.018,.972],[sy,sy],transform=fig.transFigure,color='#d8dee4',lw=.7))
fig.text(.5,.96,'Light-update completion latency · Pulsar 0.3.0',ha='center',fontsize=17,weight='bold',color='#24292f')
fig.text(.5,.918,"Median of each run's p50; whiskers show the range across 3 restarts",ha='center',fontsize=10.5,color='#57606a')
fig.text(.5,.875,'Alfheim / Pulsar: 7 Sep 2026 · Vanilla*: 6 Aug 2026 (historical reference)',ha='center',fontsize=10,color='#57606a')
fig.text(.5,.84,'* Vanilla used a different protocol and environment; not directly comparable to the current results.',ha='center',fontsize=10,color='#915511')
fig.text(.5,.028,'Minecraft 1.12.2 · Lighting-completion measurements, not FPS or TPS. Full conditions and p95/p99 are in the data report.',ha='center',fontsize=9,color='#57606a')
fig.savefig(D/'light-updates.svg',facecolor='white')
fig.savefig(D/'light-updates.png',dpi=160,facecolor='white')
svg=D/'light-updates.svg'
svg.write_text('\n'.join(line.rstrip() for line in svg.read_text(encoding='utf-8').splitlines())+'\n',encoding='utf-8')
