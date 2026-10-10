package com.sumirelabs.benchprobe;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** External diagnostic recorder shared by both engines; no allocation or logging in captured edits. */
public final class BenchTiming {
    public static final boolean ENABLED=Boolean.getBoolean("benchprobe.enabled");
    private static final String[] NAMES={"queue_wait","task_body","setup","sections","propagation","publish","cleanup","prepare","flood","tasks","positions","increase_entries","decrease_entries","first_engine_start","last_engine_end","first_task_start","last_task_end","engines"};
    private static final int N=NAMES.length;
    private static final Row[] ROWS=ENABLED?new Row[4096]:null;
    private static final AtomicInteger enginesActive=new AtomicInteger(),tasksActive=new AtomicInteger();
    private static volatile Row active;
    private static int count;
    private static int warmupCount;
    private static final class Row {
        final long[] data=new long[2*N];
        String phase;int index;long submission,barrier,completion,origin;boolean overlap;
        Row(){data[13]=data[15]=data[N+13]=data[N+15]=Long.MAX_VALUE;}
    }
    private BenchTiming(){}
    public static void prepare(){if(ENABLED)for(int i=0;i<ROWS.length;i++)ROWS[i]=new Row();}
    public static void discardWarmup(){
        if(!ENABLED)return;
        if(active!=null||enginesActive.get()!=0||tasksActive.get()!=0)throw new IllegalStateException("Warmup not drained");
        warmupCount=count;
        for(int i=0;i<count;i++){
            Row row=ROWS[i];Arrays.fill(row.data,0);row.data[13]=row.data[15]=row.data[N+13]=row.data[N+15]=Long.MAX_VALUE;
            row.phase=null;row.overlap=false;row.index=0;row.submission=row.barrier=row.completion=row.origin=0;
        }
        count=0;
    }
    public static boolean selected(String phase,int index){
        int h=index^phase.hashCode();h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;h^=h>>>16;
        return (h&15)==0;
    }
    public static boolean begin(String phase,int index){
        if(!ENABLED||phase==null||!selected(phase,index))return false;
        if(active!=null||count==ROWS.length)throw new IllegalStateException("Probe capacity/overlap");
        Row row=ROWS[count++];row.phase=phase;row.index=index;active=row;return true;
    }
    public static long engineStart(boolean server){
        if(!ENABLED||!server||active==null)return 0;
        if(enginesActive.incrementAndGet()!=1)active.overlap=true;
        return System.nanoTime();
    }
    public static void engine(boolean sky,long a,long b,long c,long d,long e,long f){
        if(a==0)return;
        Row row=active;int o=sky?0:N;
        row.data[o+2]+=b-a;row.data[o+3]+=c-b;row.data[o+4]+=d-c;row.data[o+5]+=e-d;row.data[o+6]+=f-e;
        row.data[o+13]=Math.min(row.data[o+13],a);row.data[o+14]=Math.max(row.data[o+14],f);row.data[o+17]++;
        enginesActive.decrementAndGet();
    }
    public static void propagation(boolean sky,long a,long b,long c){
        if(a==0)return;Row row=active;int o=sky?0:N;row.data[o+7]+=b-a;row.data[o+8]+=c-b;
    }
    public static long clock(boolean server){return ENABLED&&server&&active!=null?System.nanoTime():0;}
    public static void work(boolean sky,int positions,int increase,int decrease){
        if(!ENABLED||active==null)return;int o=sky?0:N;Row row=active;
        row.data[o+10]+=positions;row.data[o+11]+=increase;row.data[o+12]+=decrease;
    }
    public static void entries(boolean server,boolean sky,boolean increase,int count){
        if(!ENABLED||!server||active==null)return;active.data[(sky?0:N)+(increase?11:12)]+=count;
    }
    public static long taskStart(boolean sky,long enqueued){
        if(!ENABLED||active==null)return 0;
        Row row=active;if(tasksActive.incrementAndGet()!=1)row.overlap=true;
        long now=System.nanoTime();int o=sky?0:N;row.data[o]+=now-enqueued;row.data[o+9]++;
        row.data[o+15]=Math.min(row.data[o+15],now);return now;
    }
    public static void taskEnd(boolean sky,long started){
        if(started==0)return;long now=System.nanoTime();Row row=active;int o=sky?0:N;
        row.data[o+1]+=now-started;row.data[o+16]=Math.max(row.data[o+16],now);tasksActive.decrementAndGet();
    }
    public static void end(long submission,long barrier,long completion,long origin){
        Row row=active;if(row==null)throw new IllegalStateException("No active sample");
        if(enginesActive.get()!=0||tasksActive.get()!=0)row.overlap=true;
        row.submission=submission;row.barrier=barrier;row.completion=completion;row.origin=origin;active=null;
    }
    public static void write(Path output) throws Exception{
        if(!ENABLED)return;
        List<Map<String,Object>> list=new ArrayList<>(count);
        for(int j=0;j<count;j++){
            Row row=ROWS[j];Map<String,Object> m=new LinkedHashMap<>();m.put("phase",row.phase);m.put("index",row.index);m.put("overlap",row.overlap);
            m.put("submission_nanos",row.submission);m.put("barrier_nanos",row.barrier);m.put("completion_nanos",row.completion);
            for(int lane=0;lane<2;lane++)for(int k=0;k<N;k++){
                long value=row.data[lane*N+k];boolean stamp=k>=13&&k<=16;
                if(stamp)value=(value==Long.MAX_VALUE||value==0)?-1:value-row.origin;
                m.put((lane==0?"sky_":"block_")+NAMES[k]+(k<9||stamp?"_nanos":""),value);
            }
            list.add(m);
        }
        Map<String,Object> document=new LinkedHashMap<>();document.put("schema",3);document.put("warmup_captured_edits",warmupCount);document.put("sampling","deterministic phase/index hash, mask15; same selected indices across engines; active probe warmed and discarded before measurement");document.put("samples",list);
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(document));
    }
}
