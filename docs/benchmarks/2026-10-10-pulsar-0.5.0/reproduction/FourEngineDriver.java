package com.sumirelabs.fourbench;

import com.google.gson.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;

/** Local disposable-instance automation and passive frame capture. */
@Mod(modid="fourenginebench", name="Four-engine local benchmark driver", version="1")
public class FourEngineDriver {
    private static final Path ROOT=Paths.get(System.getProperty("user.dir"));
    private static final boolean ENABLED=ROOT.toString().replace('\\','/').contains("/Pulsar-SWMR-20261008/minecraft")
            && Files.exists(ROOT.resolve("four-engine-bench.marker"));
    private static final Map<String,List<Long>> FRAMES=new LinkedHashMap<>();
    private static volatile boolean finished;
    private static boolean started;
    private static int startTicks,exitTicks;
    private static int suiteStep,missingResultTicks,observerSettleTicks;
    private static long previousFrame;
    private static String previousCase="";
    private static Method tpsRate,spikeRate,tpsRunning,spikeRunning,tpsMeasuring,spikeMeasuring;

    @Mod.EventHandler public void init(FMLInitializationEvent e) { if(ENABLED) MinecraftForge.EVENT_BUS.register(this); }
    private static Method method(Object obj,String[] names,Class<?>... args) throws Exception {
        Class<?> type=obj instanceof Class<?> ? (Class<?>)obj : obj.getClass();
        for(String name:names) try { return type.getMethod(name,args); } catch(NoSuchMethodException ignored) {}
        throw new NoSuchMethodException(Arrays.toString(names));
    }
    private static Object field(Object obj,String... names) throws Exception {
        for(Class<?> type=obj.getClass();type!=null;type=type.getSuperclass()) for(String name:names) {
            try { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(obj); } catch(NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(Arrays.toString(names));
    }
    private static Object minecraft() throws Exception {
        Class<?> type=Class.forName("net.minecraft.client.Minecraft");
        return method(type,new String[]{"getMinecraft","func_71410_x"}).invoke(null);
    }
    private static void api() throws Exception {
        if(tpsRate!=null) return;
        Class<?> tps=Class.forName("com.sumirelabs.lightbench.TpsTest"),spikes=Class.forName("com.sumirelabs.lightbench.SpikeTest");
        tpsRate=tps.getMethod("activeRate");spikeRate=spikes.getMethod("activeRate");
        tpsRunning=tps.getMethod("isRunning");spikeRunning=spikes.getMethod("isRunning");
        tpsMeasuring=tps.getMethod("isMeasuring");spikeMeasuring=spikes.getMethod("isMeasuring");
    }
    @SubscribeEvent public void client(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END) return;
        try {
            Object mc=minecraft();
            if(!started) {
                Object screen=field(mc,"currentScreen","field_71462_r");
                if(screen==null||!screen.getClass().getName().equals("net.minecraft.client.gui.GuiMainMenu")) return;
                if(++startTicks<40) return;
                started=true;
                method(mc,new String[]{"launchIntegratedServer","func_71371_a"},String.class,String.class,
                        Class.forName("net.minecraft.world.WorldSettings")).invoke(mc,"Bench Updates","Bench Updates",null);
            } else if(finished&&++exitTicks>=40) method(mc,new String[]{"shutdown","func_71400_g"}).invoke(mc);
        } catch(Exception e) { throw new RuntimeException("Four-engine automation failed",e); }
    }
    @SubscribeEvent public void frame(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.START||!started||finished) return;
        try {
            api();int tps=((Number)tpsRate.invoke(null)).intValue(),spikes=((Number)spikeRate.invoke(null)).intValue();
            String key=tps>=0 && (Boolean)tpsMeasuring.invoke(null) ? "tps-"+tps : spikes>=0 && (Boolean)spikeMeasuring.invoke(null) ? "spikes-"+spikes : "";
            long now=System.nanoTime();
            synchronized(FRAMES) {
                if(!key.isEmpty()&&key.equals(previousCase)&&previousFrame!=0) {
                    List<Long> values=FRAMES.computeIfAbsent(key,k->new ArrayList<>());
                    if(values.size()<500000) values.add(now-previousFrame);
                }
                previousCase=key;previousFrame=key.isEmpty()?0:now;
            }
        } catch(Exception e) { throw new RuntimeException("Frame capture failed",e); }
    }
    @SubscribeEvent public void server(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||finished) return;
        try {
            api();
            if(TravelProbe.isRunning()) return;
            if((Boolean)tpsRunning.invoke(null)||(Boolean)spikeRunning.invoke(null)) return;
            Object world=Class.forName("net.minecraftforge.common.DimensionManager").getMethod("getWorld",int.class).invoke(null,0);
            if(world==null) return;
            Object rules=method(world,new String[]{"getGameRules","func_82736_K"}).invoke(world);
            if(!(Boolean)method(rules,new String[]{"getBoolean","func_82766_b"},String.class).invoke(rules,"fourbench_complete")) return;
            Path results=ROOT.resolve("saves/Bench Updates/lightbench-results");
            Files.createDirectories(results);
            boolean anyResult;
            try(var files=Files.list(results)){anyResult=files.anyMatch(f->f.toString().endsWith(".json"));}
            if(!anyResult && ++missingResultTicks<100)return;
            if(!anyResult){JsonObject failure=new JsonObject();failure.addProperty("failure","Command completed without a result; inspect game.log");Files.writeString(results.resolve("failure.json"),failure.toString());}
            if(!anyResult){
                finished=true;JsonObject failure=new JsonObject();failure.addProperty("failure","No benchmark result");
                Files.writeString(results.resolve("observer.json"),failure.toString());return;
            }
            if(Files.readString(ROOT.resolve("four-engine-mode.txt")).trim().equals("suite")&&suiteStep<2) {
                if(suiteStep==0){
                    try(var files=Files.list(results)){for(Path f:files.filter(p->p.toString().endsWith(".json")).toList())Files.move(f,f.resolveSibling("warmup-"+f.getFileName()));}
                    synchronized(FRAMES){FRAMES.clear();previousCase="";previousFrame=0;}
                }
                if(suiteStep==1) {
                    try(var files=Files.list(results)) {
                        if(files.noneMatch(f->f.getFileName().toString().contains("-spikes-"))) throw new IllegalStateException("Suite spike result missing");
                    }
                }
                Object server=method(world,new String[]{"getMinecraftServer","func_73046_m"}).invoke(world);
                Object commands=method(server,new String[]{"getCommandManager","func_71187_D"}).invoke(server);
                Method execute=method(commands,new String[]{"executeCommand","func_71556_a"},
                        Class.forName("net.minecraft.command.ICommandSender"),String.class);
                execute.invoke(commands,server,"fill 19952 254 19952 20047 254 20047 air");
                execute.invoke(commands,server,suiteStep++==0?"lightbench spikes 2 10":"lightbench tps sweep 5");
                return;
            }
            if(++observerSettleTicks<100)return;
            Boolean clientPending=null;
            Object mc=minecraft();
            Object clientWorld=field(mc,"world","field_71441_e");
            if(clientWorld!=null){
                try{
                    Class<?> pulsarWorld=Class.forName("com.sumirelabs.pulsar.world.PulsarWorld");
                    if(pulsarWorld.isInstance(clientWorld)){
                        Object manager=pulsarWorld.getMethod("pulsar$getLightManager").invoke(clientWorld);
                        if(manager!=null)clientPending=(Boolean)manager.getClass().getMethod("hasUpdates").invoke(manager);
                    }
                }catch(ClassNotFoundException absent){}
            }
            if(Boolean.TRUE.equals(clientPending)&&observerSettleTicks<400)return;
            finished=true;
            JsonObject report=new JsonObject();
            report.addProperty("post_measurement_settle_ticks",observerSettleTicks);
            if(clientPending==null)report.add("pulsar_client_work_pending",JsonNull.INSTANCE);
            else report.addProperty("pulsar_client_work_pending",clientPending);
            report.addProperty("memory_snapshot_has_no_known_pending_client_work",!Boolean.TRUE.equals(clientPending));
            report.addProperty("display_width",((Number)field(mc,"displayWidth","field_71443_c")).intValue());
            report.addProperty("display_height",((Number)field(mc,"displayHeight","field_71440_d")).intValue());
            if(clientWorld!=null){
                Object clientProvider=method(clientWorld,new String[]{"getChunkProvider","func_72863_F"}).invoke(clientWorld);
                report.addProperty("client_chunk_debug",String.valueOf(method(clientProvider,new String[]{"makeString","func_73148_d"}).invoke(clientProvider)));
            }
            if(!anyResult)report.addProperty("failure","No benchmark result");
            report.addProperty("process_id",ProcessHandle.current().pid());
            report.addProperty("pulsar_parallel_worker_count",Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("Pulsar-Parallel-")).count());
            report.addProperty("variant",Files.readString(ROOT.resolve("lightbench-variant.txt")).trim());
            Object provider=method(world,new String[]{"getChunkProvider","func_72863_F"}).invoke(world);
            report.addProperty("loaded_chunks",((Map<?,?>)field(provider,"loadedChunks","field_73244_f")).size());
            JsonObject frames=new JsonObject();
            synchronized(FRAMES) {
                for(var entry:FRAMES.entrySet()) {
                    JsonArray data=new JsonArray();for(long n:entry.getValue()) data.add(n);
                    frames.add(entry.getKey(),data);
                }
            }
            report.add("frame_intervals_nanos",frames);
            // A diagnostic GC after every measured workload and its verification.
            System.gc();
            report.addProperty("heap_after_gc_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            report.addProperty("measurement_excludes_gc_and_reporting",true);
            Files.writeString(results.resolve("observer.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println("FOUR_ENGINE_BENCH_COMPLETED");
        } catch(Exception e) { throw new RuntimeException("Post-run observation failed",e); }
    }
}
