"""Publication figures from qualified independent JVM trials."""
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/"tools/python-packages"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import json, statistics
from collect import LABELS

COLORS = {"vanilla":"#4C78A8","alfheim":"#F58518","pulsar":"#2A9D67","starlight":"#9365B8"}
plt.rcParams.update({"font.family":"DejaVu Sans","font.size":11,"axes.spines.top":False,
                     "axes.spines.right":False,"axes.titleweight":"bold","figure.facecolor":"white",
                     "axes.axisbelow":True,"svg.fonttype":"none","savefig.dpi":180})
DATA = json.loads((ROOT/"analysis/results.json").read_text())
if json.loads((ROOT/"progress.json").read_text(encoding="utf-8-sig"))["status"] not in (
        "measurements-complete", "analysis", "complete"):
    raise SystemExit("Publication figures require the completed measurement plan.")

def rows(family, metric, engine):
    return [r for r in DATA["metrics"] if r["family"]==family and r["metric"]==metric
            and r["engine"]==engine and r["valid"]]

def export(fig, name, private=False):
    folder=ROOT/("personal-comparison" if private else "publication")
    folder.mkdir(exist_ok=True)
    fig.savefig(folder/(name+".png"),bbox_inches="tight",facecolor="white")
    fig.savefig(folder/(name+".svg"),bbox_inches="tight",facecolor="white")
    plt.close(fig)

def bars(name, panels, title, engines, footer, private=False, statistic="average", require_parity=True):
    fig, axes=plt.subplots(1,len(panels),figsize=(6.1*len(panels),5.4),squeeze=False)
    for ax,(family,metric,caption,scale,unit) in zip(axes[0],panels):
        for i,engine in enumerate(engines):
            trial=rows(family,metric,engine)
            good=len(trial)==4 and (not require_parity or all(r["equal_output_ranking_eligible"] for r in trial))
            if not good:
                ax.text(i,0.035,"Not\nvalidated",ha="center",va="bottom",transform=ax.get_xaxis_transform(),color="#666",fontsize=9)
                continue
            values=[r[statistic]/scale for r in trial]
            median=statistics.median(values)
            ax.bar(i,median,color=COLORS[engine],width=.62,zorder=3)
            ax.errorbar(i,median,yerr=[[median-min(values)],[max(values)-median]],fmt="none",ecolor="#333",capsize=5,zorder=4)
            ax.annotate(f"{median:.3g}",(i,max(values)),xytext=(0,7),textcoords="offset points",ha="center",fontsize=10)
        ax.set_xticks(range(len(engines)),[LABELS[e].replace("Pulsar ","Pulsar\n") for e in engines])
        ax.set_xlim(-.6,len(engines)-.4)
        ax.set_title(caption,pad=14)
        ax.set_ylabel(unit)
        ax.grid(axis="y",alpha=.2)
        bottom,top=ax.get_ylim();ax.set_ylim(0,max(top*1.17,1e-6))
    fig.suptitle(title,fontsize=16,y=1.01,fontweight="bold")
    fig.text(.5,-.025,footer,ha="center",va="top",fontsize=9,color="#555")
    fig.tight_layout()
    export(fig,name,private)

def continuous(engines, private=False):
    fig,ax=plt.subplots(figsize=(10,5.5))
    for engine in engines:
        x,y,low,high=[],[],[],[]
        for rate in [64,128,256,512,1024,2048]:
            trial=rows("continuous",f"tps_{rate}_server_tick",engine)
            if len(trial)!=4:continue
            values=[r["average"]/1e6 for r in trial];m=statistics.median(values)
            x.append(rate);y.append(m);low.append(m-min(values));high.append(max(values)-m)
        if x:ax.errorbar(x,y,yerr=[low,high],marker="o",capsize=4,label=LABELS[engine],color=COLORS[engine],linewidth=2)
    ax.axhline(50,color="#888",linestyle="--",linewidth=1,label="50 ms tick budget")
    ax.set_xscale("log",base=2);ax.set_yscale("log")
    ax.set_xticks([64,128,256,512,1024,2048],[64,128,256,512,1024,2048])
    ax.set_xlabel("Sky roof edits per server tick")
    ax.set_ylabel("Mean server tick duration (ms, log scale)")
    ax.set_title("Continuous roof editing: server-thread responsiveness",pad=15)
    ax.grid(alpha=.2,which="both");ax.legend(loc="upper left")
    fig.text(.5,-.01,"Median of 4 independent JVM run means; whiskers show run min–max.\n"
             "Main-thread time excludes async worker CPU. Post-run drain and light verification required; invalid cases omitted.",
             ha="center",va="top",fontsize=9,color="#555")
    fig.tight_layout();export(fig,"continuous-server-ticks",private)

MAIN=["vanilla","alfheim","pulsar"]
ALL=MAIN+["starlight"]
NOTE="Same machine, world and work per case. Median of 4 independent JVM run means; whiskers show run min–max.\nLower is better. See methods and validation tables; no confidence intervals are implied."
for engines,private in [(MAIN,False),(ALL,True)]:
    bars("block-latency",[("blocks","block_place","Glowstone placement",1e3,"Completion time (µs)"),
                         ("blocks","block_remove","Glowstone removal",1e3,"Completion time (µs)")],
         "Lightbench: validated open-sky block updates",engines,NOTE,private)
    bars("sky-latency",[("sky","sky_remove","Open a Sky column",1e6,"Completion time (ms)"),
                       ("sky","sky_place","Close a Sky column",1e6,"Completion time (ms)")],
         "Lightbench: 250-block-tall Sky-column stress test",engines,NOTE+"\nVanilla did not pass this workload's stored-light validation.",private)
    bars("flat-generation",[("flatgen","generation_until_lit","Flat generation + light completion",1e9,"End-to-end time (s)")],
         "Equal-output flat chunk generation",engines,NOTE,private)
    bars("flat-travel-readiness",[("flattravel","readiness_latency","First detected light-ready neighborhood",1e6,"Waypoint readiness latency (ms)")],
         "Fixed-route flat-world travel: server readiness",engines,
         NOTE+"\n32 waypoints; readiness polled each server tick. Client mesh/presentation completion is not measured.",private)
    bars("normal-generation-descriptive",[("naturalgen","generation_until_lit","Normal terrain; outputs differ",1e9,"Observed time (s)")],
         "Same-seed normal generation — descriptive only",engines,
         NOTE+"\nTerrain/light hashes differ. These values are not an equal-output speed ranking.",private,require_parity=False)
    bars("post-gc-heap",[("blocks","post_gc_heap","After block updates",1024**2,"Observed Java heap (MiB)"),
                         ("flatgen","post_gc_heap","After flat generation",1024**2,"Observed Java heap (MiB)")],
         "Observed post-GC heap: whole game JVM",engines,
         NOTE+"\nAt least 100 post-workload server ticks before GC. Includes world/client data; client chunk counts may differ.\n"
         "Not engine-only memory or equal-scene memory efficiency.",private,require_parity=False)
    continuous(engines,private)
    bars("burst-server-ticks",[("continuous","spikes_2_server_tick","2 edits per 20 ticks, 200 measured ticks",1e6,"Mean server tick duration (ms)")],
         "Paced roof edits: server-thread responsiveness",engines,
         NOTE+"\nWorker CPU is excluded; final light drain and verification required. Vanilla not validated.",private)
    bars("normal-travel-descriptive",[("naturaltravel","readiness_latency","Normal route; terrain outputs differ",1e6,"Observed readiness latency (ms)")],
         "Normal-world travel — descriptive only",engines,
         NOTE+"\nTerrain hashes differ. Server readiness is polled each tick; this is not client presentation latency.",private,require_parity=False)
bars("frame-callbacks-descriptive",[("flattravel","travel_frame_interval","Flat route",1e6,"p99 render callback interval (ms)"),
                                    ("naturaltravel","travel_frame_interval","Normal route",1e6,"p99 render callback interval (ms)")],
     "Render-loop observations — not presentation FPS",ALL,
     "Median of 4 run p99 values; whiskers show run min–max. Default client chunk counts differ.\n"
     "Descriptive responsiveness data only, not an equal-rendered-scene FPS speedup ranking.",
     True,statistic="p99",require_parity=False)
