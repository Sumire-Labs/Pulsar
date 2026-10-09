"""Offline aggregation. Never treats invalid light or incomplete work as a speed score."""
from pathlib import Path
import csv, hashlib, json, math, re, statistics

ROOT = Path(__file__).resolve().parents[1]
ENGINES = ["vanilla", "alfheim", "pulsar", "starlight"]
LABELS = {"vanilla": "Vanilla", "alfheim": "Alfheim 1.6", "pulsar": "Pulsar 0.5.0", "starlight": "Starlight-1.12"}

def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))

def distribution(values):
    values = sorted(values)
    if not values or any(not math.isfinite(x) or x < 0 for x in values):
        raise ValueError("Missing or invalid observations")
    return {"average": statistics.mean(values), **{
        f"p{p}": values[max(0, math.ceil(len(values) * p / 100) - 1)] for p in (50, 95, 99)},
        "minimum": values[0], "maximum": values[-1]}

def scored_hashes(audit):
    rows = []
    for region in audit["regions"]:
        if region["phase"] != "gen test":
            continue
        for chunk in region["chunks"]:
            if chunk["role"] == "core":
                rows.append((chunk["x"], chunk["z"], chunk["terrain_sha256"], chunk["light_sha256"]))
    rows.sort()
    return {
        "terrain": hashlib.sha256(json.dumps([(x,z,t) for x,z,t,l in rows], separators=(",",":")).encode()).hexdigest(),
        "light": hashlib.sha256(json.dumps([(x,z,l) for x,z,t,l in rows], separators=(",",":")).encode()).hexdigest(),
        "scored_chunks": len(rows)}

def collect():
    metrics, trials, failures = [], [], []
    pattern = re.compile(r"formal-v9-(.+)-r([1-4])-(vanilla|alfheim|pulsar|starlight)(?:-retry\d+)?$")
    seen = set()
    for directory in sorted((ROOT/"runs").iterdir()):
        match = pattern.fullmatch(directory.name)
        if not match or not (directory/"status.json").exists():
            continue
        family, rep, engine = match.groups()
        key = (family, int(rep), engine)
        if key in seen:
            raise ValueError(f"Duplicate completed attempts require explicit review: {key}")
        seen.add(key)
        status, stage = load(directory/"status.json"), load(directory/"stage.json")
        observer = load(directory/"results/observer.json") if (directory/"results/observer.json").exists() else {}
        trial = dict(family=family, repetition=int(rep), engine=engine, run=directory.name,
                     valid=status["valid"], stage=stage, observer=observer, hashes=None, environments=[])
        trials.append(trial)

        def add(metric, values, valid, unit="ns", **extra):
            row = dict(family=family, metric=metric, repetition=int(rep), engine=engine,
                       run=directory.name, valid=bool(valid), unit=unit, **extra)
            if valid:
                row.update(distribution(values))
            metrics.append(row)

        for path in sorted((directory/"results").glob("*.json")):
            if path.name.startswith("warmup-") or path.name == "observer.json":
                continue
            data = load(path)
            if path.name == "travel.json":
                good = status["valid"] and data["valid"] and len(data["readiness_latency_nanos"]) == 32 and data["top_light_checks"] == 1600
                trial["hashes"] = {"terrain": data.get("terrain_sha256"), "scored_chunks": data["terrain_chunks"]}
                add("readiness_latency", data["readiness_latency_nanos"], good)
                add("travel_server_tick", data["server_tick_nanos"], good)
                add("travel_frame_interval", data["frame_intervals_nanos"], good and bool(data["frame_intervals_nanos"]))
                continue
            if "benchmark" not in data:
                continue
            benchmark = data["benchmark"]
            trial["environments"].append(data.get("environment", {}))
            mode = benchmark["mode"]
            if mode == "updates":
                good = (status["valid"] and benchmark["preflight"].get("post_measurement_volume_verified") is True
                        and benchmark["preflight"]["initial_light_verified"])
                for phase in benchmark["phases"]:
                    values = [sample["completion_nanos"] for sample in phase["samples"]]
                    verified = (good and phase["all_samples_verified"]
                                and len(values) == benchmark["plan"]["measured_pairs"]
                                and all(sample["light_verified"] for sample in phase["samples"]))
                    add(phase["name"], values, verified,
                        warmup_pairs=benchmark["plan"]["warmup_pairs"], samples=len(values))
            elif mode in ("tps", "spikes"):
                good = (not observer.get("failure") and benchmark["roof_and_light_verified"] and not benchmark["wall_capped"]
                        and benchmark.get("initial_fixture_light_verified") is True
                        and benchmark["measured_ticks"] == benchmark["requested_ticks"])
                add(f"{mode}_{benchmark['rate']}_server_tick", benchmark["ticks_nanos"], good,
                    edits=benchmark["edits"], ticks=benchmark["measured_ticks"])
                add(f"{mode}_{benchmark['rate']}_post_drain", [benchmark["post_run_drain_nanos"]], good)
                frames = observer.get("frame_intervals_nanos", {}).get(f"{mode}-{benchmark['rate']}", [])
                add(f"{mode}_{benchmark['rate']}_frame_interval", frames, good and bool(frames))
                if not good:
                    failures.append(dict(run=directory.name, source=path.name,
                                         validation=benchmark.get("first_light_mismatches"),
                                         capped=benchmark["wall_capped"]))
            elif mode == "gen":
                audits = list((directory/"results").glob("*-gen-audit-*.json"))
                audit = load(audits[0]) if len(audits) == 1 else {}
                good = status["valid"] and audit.get("valid") is True
                if good:
                    trial["hashes"] = scored_hashes(audit)
                for phase in benchmark["phases"]:
                    if phase["name"] == "gen test":
                        add("generation_until_lit", [phase["total_until_lit_nanos"]], good,
                            submitted_chunks=phase["chunk_count"])
        if not status["valid"]:
            failures.append(dict(run=directory.name, observer_failure=observer.get("failure"),
                                 note="Inspect retained game.log; no invalid overall score is promoted."))
        if status["valid"] and "heap_after_gc_bytes" in observer:
            add("post_gc_heap", [observer["heap_after_gc_bytes"]],
                observer.get("memory_snapshot_has_no_known_pending_client_work", True), unit="bytes",
                server_chunks=observer["loaded_chunks"], client_chunks=observer.get("client_chunk_debug"))
            if (directory/"process-samples.json").exists() and "process_id" in observer:
                samples = load(directory/"process-samples.json")
                samples = [samples] if isinstance(samples, dict) else samples
                rss = [x["working_set_bytes"] for x in samples if x["pid"] == observer["process_id"]]
                if rss: add("peak_process_working_set", [max(rss)], True, unit="bytes")

    parity = {}
    for family in ("flatgen", "naturalgen", "flattravel", "naturaltravel"):
        cohort = [t for t in trials if t["family"] == family and t["valid"] and t["hashes"]]
        terrain = {t["hashes"]["terrain"] for t in cohort}
        light = {t["hashes"].get("light") for t in cohort}
        counts = {t["hashes"]["scored_chunks"] for t in cohort}
        parity[family] = dict(trials=len(cohort), distinct_terrain_hashes=len(terrain),
                              distinct_light_hashes=len(light), distinct_chunk_counts=len(counts),
                              matched=bool(cohort) and len(terrain)==len(light)==len(counts)==1)
    scene_parity = {}
    for family in {t["family"] for t in trials}:
        cohort = [t for t in trials if t["family"] == family and t["valid"]]
        counts = {t["observer"].get("client_chunk_debug") for t in cohort}
        server_counts = {t["observer"].get("loaded_chunks") for t in cohort}
        sizes = {(t["observer"].get("display_width"),t["observer"].get("display_height")) for t in cohort}
        scene_parity[family] = dict(client_chunk_counts=sorted(str(x) for x in counts),
                                    server_chunk_counts=sorted(x for x in server_counts if x is not None),
                                    loaded_scene_matched=bool(cohort) and len(counts)==len(server_counts)==len(sizes)==1)
    for row in metrics:
        if row["metric"] == "generation_until_lit" or row["family"] in ("flattravel","naturaltravel"):
            row["equal_output_ranking_eligible"] = parity[row["family"]]["matched"]
        else:
            row["equal_output_ranking_eligible"] = True
        if "frame_interval" in row["metric"] or row["unit"] == "bytes":
            row["equal_output_ranking_eligible"] &= scene_parity[row["family"]]["loaded_scene_matched"]

    groups = {}
    for row in metrics:
        if row["valid"]:
            groups.setdefault((row["family"],row["metric"],row["engine"]), []).append(row)
    summary = []
    for (family, metric, engine), rows in sorted(groups.items()):
        means = [r["average"] for r in rows]
        summary.append(dict(family=family,metric=metric,engine=engine,label=LABELS[engine],
                            independent_jvm_runs=len(rows),unit=rows[0]["unit"],
                            median_of_run_means=statistics.median(means),minimum_run_mean=min(means),
                            maximum_run_mean=max(means),median_run_p95=statistics.median(r["p95"] for r in rows),
                            median_run_p99=statistics.median(r["p99"] for r in rows),
                            equal_output_ranking_eligible=all(r["equal_output_ranking_eligible"] for r in rows),
                            complete_four_run_cohort=len(rows)==4))
    result = dict(metrics=metrics,summary=summary,parity=parity,scene_parity=scene_parity,failures=failures,trials=trials)
    out = ROOT/"analysis"
    out.mkdir(exist_ok=True)
    (out/"results.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
    if summary:
        with (out/"summary.csv").open("w",newline="",encoding="utf-8") as stream:
            writer=csv.DictWriter(stream,fieldnames=list(summary[0]))
            writer.writeheader();writer.writerows(summary)
    return result

if __name__ == "__main__":
    result=collect()
    print(json.dumps({"formal_trials":len(result["trials"]),"metric_groups":len(result["summary"]),
                      "parity":result["parity"]},indent=2))
