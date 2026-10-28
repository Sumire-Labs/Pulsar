package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.PulsarConfig;
import java.util.HashMap;
import java.util.Map;

/** Opt-in process-wide diagnostics. Exact counts, 1/64 sampled origins/positions.
 * Bounded windows avoid retaining an unbounded set of coordinates or stack traces.
 */
public final class LightUpdateTracer {
    public enum Kind { LIGHT_ENQUEUE, RENDER_REQUEST, CELERITAS_SCHEDULE, CELERITAS_BUILD }
    private static final long[] COUNTS = new long[Kind.values().length];
    private static final Map<String,Long> ORIGINS = new HashMap<>(), POSITIONS = new HashMap<>();
    private static long buildNanos;
    private static int ticks;
    private LightUpdateTracer() {}
    public static boolean enabled() { return PulsarConfig.debug.traceLightUpdates; }

    public static void record(Kind kind, int dimension, int x, int y, int z) {
        if(!enabled())return;
        synchronized(LightUpdateTracer.class) {
            long count=++COUNTS[kind.ordinal()];
            if((count & 63)!=1)return;
            bounded(POSITIONS,kind+"@"+dimension+":"+x+","+y+","+z,1024);
            String origin=StackWalker.getInstance().walk(frames->frames
                    .filter(f->!f.getClassName().equals(LightUpdateTracer.class.getName()))
                    .filter(f->!f.getMethodName().startsWith("pulsar$") && !f.getMethodName().contains("$pulsar$"))
                    .limit(5).map(f->f.getClassName()+"."+f.getMethodName()).reduce((a,b)->a+" <- "+b).orElse("unknown"));
            bounded(ORIGINS,kind+":"+origin,64);
        }
    }
    public static void completed(long started) {
        if(started==0 || !enabled())return;
        synchronized(LightUpdateTracer.class) { COUNTS[Kind.CELERITAS_BUILD.ordinal()]++; buildNanos+=Math.max(0,System.nanoTime()-started); }
    }
    private static void bounded(Map<String,Long> map,String key,int limit) {
        if(map.size()>=limit && !map.containsKey(key))key="(other)";
        map.merge(key,1L,Long::sum);
    }
    public record Window(long[] counts,long buildNanos,Map<String,Long> origins,Map<String,Long> positions) {}
    public static synchronized Window drain() {
        Window result=new Window(COUNTS.clone(),buildNanos,Map.copyOf(ORIGINS),Map.copyOf(POSITIONS));
        java.util.Arrays.fill(COUNTS,0);buildNanos=0;ORIGINS.clear();POSITIONS.clear();return result;
    }
    public static void tick(boolean worldPresent) {
        if(!enabled() || !worldPresent) { if(ticks!=0){drain();ticks=0;}return; }
        if(++ticks<200)return;
        ticks=0;Window window=drain();
        Pulsar.LOGGER.info("Light trace (200 ticks): enqueues={}, renderRequests={}, celeritasSchedules={}, completedBuilds={}, buildMs={}, sampledOrigins={}, sampledPositions={}",
                window.counts[0],window.counts[1],window.counts[2],window.counts[3],window.buildNanos/1_000_000.0,
                top(window.origins),top(window.positions));
    }
    private static Object top(Map<String,Long> values) {
        return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(5).toList();
    }
}
