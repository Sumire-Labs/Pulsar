package com.sumirelabs.pulsar.light;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/** Local benchmark-only probe. Immutable JVM opt-in; no logging in timed regions. */
public final class PhaseTiming {
    public static final boolean ENABLED = Boolean.getBoolean("pulsar.phaseTiming");
    private static volatile Sample active;
    private static final List<Map<String,Object>> samples = new ArrayList<>();
    private static final String[] METRICS = {"queue_to_task_start", "task_body", "engine_setup", "engine_sections",
            "engine_propagation", "engine_publish", "engine_cleanup", "propagation_prepare", "propagation_flood"};
    private PhaseTiming() {}
    public static final class Sample {
        final String phase;
        final AtomicLongArray nanos = new AtomicLongArray(METRICS.length * 2);
        final AtomicLongArray tasks = new AtomicLongArray(2);
        final AtomicLongArray firstStart = new AtomicLongArray(new long[]{Long.MAX_VALUE,Long.MAX_VALUE});
        final AtomicLongArray lastEnd = new AtomicLongArray(2);
        final AtomicLongArray workCounts = new AtomicLongArray(6);
        Sample(String phase) { this.phase=phase; }
    }
    public static Sample current() { return ENABLED ? active : null; }
    public static long clock(boolean server) { return ENABLED && server && active != null ? System.nanoTime() : 0; }
    public static void begin(String phase) {
        if (!ENABLED) return;
        if (active != null) throw new IllegalStateException("Overlapping timing sample");
        active = phase == null ? null : new Sample(phase);
    }
    private static void add(Sample sample, boolean sky, int metric, long elapsed) {
        if (sample != null) sample.nanos.addAndGet((sky?0:METRICS.length)+metric, elapsed);
    }
    public static void task(Sample sample, boolean sky, long enqueued, long started, long ended) {
        if (sample == null) return;
        add(sample,sky,0,started-enqueued); add(sample,sky,1,ended-started);
        sample.tasks.incrementAndGet(sky?0:1);
        sample.firstStart.accumulateAndGet(sky?0:1,started,Math::min);
        sample.lastEnd.accumulateAndGet(sky?0:1,ended,Math::max);
    }
    public static void engine(boolean sky,long a,long b,long c,long d,long e,long f) {
        if(a==0)return;
        Sample sample=active;
        add(sample,sky,2,b-a);add(sample,sky,3,c-b);add(sample,sky,4,d-c);
        add(sample,sky,5,e-d);add(sample,sky,6,f-e);
    }
    public static void propagation(boolean sky,long a,long b,long c) {
        if(a==0)return;
        Sample sample=active; add(sample,sky,7,b-a);add(sample,sky,8,c-b);
    }
    public static void work(boolean sky,int positions,int increases,int decreases) {
        Sample sample=active;if(sample==null)return;
        int lane=sky?0:3;
        sample.workCounts.addAndGet(lane,positions);sample.workCounts.addAndGet(lane+1,increases);sample.workCounts.addAndGet(lane+2,decreases);
    }
    public static void end(long submission,long barrier,long completion,long editStarted) {
        if(!ENABLED)return;
        Sample sample=active;
        if(sample==null)throw new IllegalStateException("No timing sample");
        active=null;
        Map<String,Object> row=new LinkedHashMap<>();row.put("phase",sample.phase);
        row.put("submission_nanos",submission);row.put("barrier_nanos",barrier);row.put("completion_nanos",completion);
        for(int lane=0;lane<2;lane++) {
            String prefix=lane==0?"sky_":"block_";
            row.put(prefix+"tasks",sample.tasks.get(lane));
            row.put(prefix+"positions",sample.workCounts.get(lane*3));
            row.put(prefix+"increase_entries",sample.workCounts.get(lane*3+1));
            row.put(prefix+"decrease_entries",sample.workCounts.get(lane*3+2));
            if(sample.tasks.get(lane)>0) {
                row.put(prefix+"first_task_start_offset_nanos",sample.firstStart.get(lane)-editStarted);
                row.put(prefix+"last_task_end_offset_nanos",sample.lastEnd.get(lane)-editStarted);
            }
            for(int i=0;i<METRICS.length;i++)row.put(prefix+METRICS[i]+"_nanos",sample.nanos.get(lane*METRICS.length+i));
        }
        samples.add(row);
    }
    public static void write(Path output) throws Exception {
        if(!ENABLED)return;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("scope","Diagnostic wall time; per-lane sums overlap submission/barrier and are not additive across lanes. Propagation prepare/flood are children of engine_propagation. queue_to_task_start includes partner-lane serialization, acquisition excludes neither scheduler nor wakeup delays. task_body ends before future completion.");
        result.put("samples",samples);
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
