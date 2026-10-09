"""Build English publication copy from the completed, qualified dataset."""
import json, shutil
from pathlib import Path
from collect import ROOT, LABELS

if json.loads((ROOT/"progress.json").read_text(encoding="utf-8-sig"))["status"] not in (
        "measurements-complete", "analysis", "complete"):
    raise SystemExit("Publication text requires the completed measurement plan.")
data=json.loads((ROOT/"analysis/results.json").read_text())
protocol=json.loads((ROOT/"protocol.json").read_text(encoding="utf-8-sig"))
out=ROOT/"publication"
out.mkdir(exist_ok=True)
main=["vanilla","alfheim","pulsar"]
all_engines=main+["starlight"]

def score(family,metric,engine,scale=1,qualified=True):
    rows=[x for x in data["summary"] if x["family"]==family and x["metric"]==metric and x["engine"]==engine]
    if not rows:return None
    row=rows[0]
    if not row["complete_four_run_cohort"] or (qualified and not row["equal_output_ranking_eligible"]):return None
    return row["median_of_run_means"]/scale

def value(family,metric,engine,scale=1,qualified=True):
    n=score(family,metric,engine,scale,qualified)
    return "Not validated" if n is None else f"{n:.3g}"

def table(family,metrics,engines=main,qualified=True):
    text="| Engine | "+" | ".join(caption for key,caption,scale in metrics)+" |\n"
    text+="|---|"+"---:|"*len(metrics)+"\n"
    for engine in engines:
        text+="| "+LABELS[engine]+" | "+" | ".join(value(family,key,engine,scale,qualified) for key,caption,scale in metrics)+" |\n"
    return text

def main_report(engines):
    text="# Pulsar 0.5.0: controlled Lightbench comparison\n\n"
    text+="These are measurements on one machine and one fixed setup, not a promise for every world or modpack. "
    text+="Values are medians of **four independent JVM run means**; figure whiskers show the minimum and maximum run mean, not confidence intervals.\n\n"
    text+="## Validated glowstone updates under open sky\n\n"
    text+=table("blocks",[("block_place","Place (µs)",1e3),("block_remove","Remove (µs)",1e3)],engines)
    text+="\nCompletion time includes the server block edit and the installed engine's light-completion barrier. "
    text+="The fixture is a uniform low Superflat floor with no roof. Each JVM uses 5,000 warmup pairs and 10,000 measured pairs. "
    text+="Sparse checks run after every edit, and independent complete Sky/Block volume checks run before and after measurement. "
    text+="Update flags are 16; this is not player input, network latency or arbitrary neighbor-event throughput.\n\n"
    text+="![Validated block updates](block-latency.png)\n\n"
    text+="## Sky-column stress test\n\n"
    text+=table("sky",[("sky_remove","Open (ms)",1e6),("sky_place","Close (ms)",1e6)],engines)
    text+="\nThe 64×64 roof is at Y254 over a floor at Y3. This is a deliberately tall lighting stress test. "
    text+="Vanilla did not satisfy the full stored-light validation protocol and has no comparable score. "
    text+="Normal public chunk maintenance was included for Vanilla; synthetic per-cell repairs were not used to manufacture a passing result.\n\n"
    text+="![Sky column stress test](sky-latency.png)\n\n"
    text+="## Equal-output flat generation\n\n"
    text+=table("flatgen",[("generation_until_lit","Until lit (s)",1e9)],engines)
    text+="\nEach measured run submits 5,292 chunks across 12 disjoint regions, including support halos. "
    text+="The 3,468 scored core chunks must have matching terrain and light hashes across qualifying runs. "
    text+="Provisioning, engine completion and explicit finalization are timed; hashing and report writing are outside the timer. "
    text+="This simple flat workload does not represent cave or forest generation.\n\n"
    text+="![Flat generation](flat-generation.png)\n\n"
    text+="## Continuous roof editing\n\n"
    text+="| Edits/tick | "+" | ".join(LABELS[e] for e in engines)+" |\n"
    text+="|---:"+"|---:"*len(engines)+"|\n"
    for rate in [64,128,256,512,1024,2048]:
        text+=f"| {rate} | "+" | ".join(value("continuous",f"tps_{rate}_server_tick",e,1e6) for e in engines)+" |\n"
    text+="\nValues are main-thread MSPT over 100 requested ticks per rate. A separate warmup precedes scored spikes and the sweep; "
    text+="initial and final stored light are validated. Capped or incorrect cases are excluded. "
    text+="Async worker CPU is not part of main-thread MSPT, and post-run light-drain time is recorded separately. "
    text+="Lower MSPT alone does not prove less total CPU work or faster visual light convergence.\n\n"
    text+="![Continuous editing](continuous-server-ticks.png)\n\n"
    text+="## Travel, frame observations and memory\n\n"
    text+=table("flattravel",[("readiness_latency","Flat waypoint readiness (ms)",1e6)],engines)
    text+="\nThe route has eight warmup and 32 measured waypoints, one chunk apart, with at least 20 ticks of dwell. "
    text+="Readiness covers the loaded 5×5 server neighborhood and is polled each server tick. "
    text+="It is not client mesh or presentation completion. Complete terrain hashes are recorded outside timing.\n\n"
    text+="![Server waypoint readiness](flat-travel-readiness.png)\n\n"
    text+="Frame observations use render-loop callback intervals, **not presented FPS**. "
    text+="Client loaded-chunk counts differ between default engines; these data are therefore not an equal-rendered-scene FPS ranking. "
    text+="The supplemental four-engine dataset retains them for inspection.\n\n"
    text+=table("blocks",[("post_gc_heap","Observed heap after updates (MiB)",1024**2)],engines,False)
    text+="\nHeap snapshots follow at least 100 post-workload server ticks and an explicit GC. They include the entire game, world and client data, "
    text+="rather than engine-only allocations. Client cache sizes can differ. Sampled process working sets are also retained, "
    text+="with two-second polling; neither metric is a general memory-efficiency claim.\n\n"
    text+="![Observed whole-game heap](post-gc-heap.png)\n\n"
    text+="## Normal generation: descriptive only\n\n"
    text+=table("naturalgen",[("generation_until_lit","Observed time (s)",1e9)],engines,False)
    text+="\nNormal-terrain/light hashes differ, so these timings are not promoted as equal-output speed ratios. "
    text+="In the 81-chunk pilot, Pulsar's terrain differed by one brown mushroom; the light hashes also differed. "
    text+="That observation is consistent with light-dependent population, but its causal mechanism was not isolated. "
    text+="Formal cohort parity is listed in the raw summary independently of that pilot.\n\n"
    text+="![Normal generation, not an equal-output ranking](normal-generation-descriptive.png)\n\n"
    text+="## Reproduction and scope\n\n"
    text+="- Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Adoptium Java 25.0.4.1; G1, fixed 8 GiB heap and compact object headers.\n"
    text+="- GMKtec EVO-X2 / Ryzen AI MAX+ 395, 32 logical processors, approximately 95.2 GiB OS-visible RAM, Radeon 8060S.\n"
    text+="- 1280×720, render distance 4, VSync off, frame limit 260 (unlimited in 1.12.2), no shaders, Celeritas or OptiFine.\n"
    text+="- Common dependencies and configuration are frozen; every variant receives the same default Version2 Pulsar config file. "
    text+="Non-Pulsar variants ignore that file. Pulsar uses automatic `-1` worker sizing, observed as 10 shared workers, and the default 2 ms client budget.\n"
    text+="- Four counterbalanced orders: Vanilla→Alfheim→Pulsar→Starlight; Alfheim→Starlight→Vanilla→Pulsar; "
    text+="Pulsar→Vanilla→Starlight→Alfheim; Starlight→Pulsar→Alfheim→Vanilla. Unsupported cases are omitted, not replaced with zero.\n"
    text+="- Every JVM receives a fresh world copy. Fresh generation coordinates, completed work amounts and stored-light validation are checked. "
    text+="Within-run timing drift remains possible despite long warmup; run ranges and raw sequences are retained. Drift alone is not proof of JIT compilation.\n"
    text+="- The chart label is **Pulsar 0.5.0**, the intended release. The measured source is `75b051d`; its pre-tag artifact reports `0.4.1-dev.24`. "
    text+="Exact engine/runtime JAR hashes are in the manifest. No release tag or publication was created by this benchmark task.\n"
    text+="- Vanilla means the builtin lighting implementation under the same Cleanroom/common-mod setup, not a separate unmodified Java8 Mojang installation.\n\n"
    text+="Alfheim is the official [1.6 release](https://github.com/Red-Studio-Ragnarok/Alfheim/releases/tag/1.6). "
    text+="The optional [Starlight-1.12 port](https://github.com/geyesborg/starlight-1.12/tree/0a7d94e7e3fd7333f0e31e896e8a5ff57fb57e4e) "
    text+="was locally built from that commit. Its 33 Java class files are unchanged; only incorrect Pulsar core-plugin manifest entries were removed. "
    text+="It is not an author-supplied release binary. No GPL reference code was copied into Pulsar; Pulsar remains LGPL-3.0.\n"
    return text

(out/"README.md").write_text(main_report(main),encoding="utf-8")
personal=ROOT/"personal-comparison"
personal.mkdir(exist_ok=True)
(personal/"README.md").write_text(main_report(all_engines),encoding="utf-8")
copy="# Suggested release-page performance copy\n\n"
copy+="Pulsar 0.5.0 was compared with Vanilla lighting and Alfheim 1.6 using controlled Lightbench workloads on Minecraft1.12.2/Cleanroom. "
copy+="Results include four independent JVM runs per supported condition, with matched settings, completed-work checks and stored-light validation. "
copy+="The figures show run ranges; they are not guarantees for every world or modpack.\n\n"
copy+="![Validated glowstone updates](block-latency.png)\n\n"
copy+="![Equal-output flat generation](flat-generation.png)\n\n"
copy+="See the full methods and raw data. Vanilla's tall-roof Sky workload failed light validation; normal terrain outputs differed; "
copy+="render callbacks are not presented FPS. Those conditions are not used for unqualified speedup claims.\n"
for site in ["github","curseforge","modrinth"]:
    (out/(site+"-copy.md")).write_text(copy,encoding="utf-8")
(out/"summary.json").write_text(json.dumps({"summary":data["summary"],"parity":data["parity"],
                                           "scene_parity":data["scene_parity"],"failures":data["failures"]},indent=2),encoding="utf-8")
shutil.copyfile(ROOT/"analysis/summary.csv",out/"summary.csv")
shutil.copyfile(ROOT/"protocol.json",out/"protocol.json")
shutil.copyfile(ROOT/"manifest-final.json",out/"manifest.json")
