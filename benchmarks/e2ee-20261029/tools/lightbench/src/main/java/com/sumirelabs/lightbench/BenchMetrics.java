package com.sumirelabs.lightbench;

import com.google.gson.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

/** Benchmark-only observer. It changes no engine algorithms and runs identically in all variants. */
public final class BenchMetrics extends CommandBase {
    private static long[] ticks;
    private static int index;
    private static long started, wallStarted, cpuStarted;
    private static String label;
    public String getName(){return "benchmetrics";}
    public String getUsage(ICommandSender sender){return "benchmetrics snapshot <label> | ticks <label> <seconds>";}
    public int getRequiredPermissionLevel(){return 4;}
    public static void prepareWorld(World world) {
        for(String rule:new String[]{"doMobSpawning","doDaylightCycle","doWeatherCycle","doMobLoot","doEntityDrops"})world.getGameRules().setOrCreateGameRule(rule,"false");
        world.getGameRules().setOrCreateGameRule("randomTickSpeed","0");
        world.setWorldTime(6000);
    }
    public void execute(MinecraftServer server,ICommandSender sender,String[] args)throws CommandException {
        if(args.length<2 || !args[1].matches("[A-Za-z0-9_.-]{1,64}"))throw new WrongUsageException(getUsage(sender));
        if(args[0].equals("bedrock")) {
            try { write(server,args[1],BedrockOreCheck.run(server));System.out.println("E2EE_BEDROCK_PASS"); }
            catch(Exception | LinkageError e){e.printStackTrace();System.out.println("E2EE_BEDROCK_FAIL "+e);throw new CommandException(e.toString());}
        } else if(args[0].equals("audit")) {
            JsonObject result=snapshot(server);JsonArray chunks=new JsonArray();
            for(var world:server.worlds)for(var chunk:world.getChunkProvider().getLoadedChunks()) {
                JsonObject item=new JsonObject();item.addProperty("dimension",world.provider.getDimension());
                item.addProperty("x",chunk.x);item.addProperty("z",chunk.z);
                item.addProperty("terrain_sha256",GenerationAudit.terrainHash(chunk));chunks.add(item);
            }
            result.add("chunks",chunks);
            try{write(server,args[1],result);}catch(Exception e){throw new CommandException(e.toString());}
        } else if(args[0].equals("snapshot")) {
            try { LightProbe.create(sender.getEntityWorld()).drainLight(); write(server,args[1],snapshot(server)); }
            catch(Exception e){throw new CommandException(e.toString());}
        } else if(args[0].equals("ticks") && args.length==3) {
            if(ticks!=null)throw new CommandException("measurement already active");
            int seconds=parseInt(args[2],5,120);label=args[1];ticks=new long[seconds*20];index=0;wallStarted=System.nanoTime();cpuStarted=cpu();
            System.out.println("E2EE_METRICS_START "+label);
        } else throw new WrongUsageException(getUsage(sender));
    }
    public static void tickStart(){if(ticks!=null)started=System.nanoTime();}
    public static void tickEnd(MinecraftServer server) {
        if(ticks==null || started==0)return;
        ticks[index++]=System.nanoTime()-started;started=0;
        if(index<ticks.length)return;
        long[] samples=ticks;ticks=null;
        JsonObject result=snapshot(server);JsonArray raw=new JsonArray();for(long value:samples)raw.add(value);
        result.add("tick_work_ns",raw);result.addProperty("elapsed_wall_ns",System.nanoTime()-wallStarted);result.addProperty("process_cpu_ns",cpu()-cpuStarted);
        long[] sorted=samples.clone();Arrays.sort(sorted);
        result.addProperty("tick_p50_ns",sorted[(int)Math.ceil(sorted.length*.5)-1]);
        result.addProperty("tick_p95_ns",sorted[(int)Math.ceil(sorted.length*.95)-1]);
        result.addProperty("tick_p99_ns",sorted[(int)Math.ceil(sorted.length*.99)-1]);
        try{write(server,label,result);}catch(Exception e){throw new RuntimeException(e);}
    }
    private static long cpu(){return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();}
    private static JsonObject snapshot(MinecraftServer server) {
        JsonObject result=new JsonObject();MemoryUsage heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        result.addProperty("heap_used",heap.getUsed());result.addProperty("heap_committed",heap.getCommitted());result.addProperty("heap_max",heap.getMax());
        result.addProperty("nonheap_used",ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        result.addProperty("java",System.getProperty("java.runtime.version"));result.addProperty("processors",Runtime.getRuntime().availableProcessors());
        result.addProperty("mods",net.minecraftforge.fml.common.Loader.instance().getActiveModList().size());
        JsonArray worlds=new JsonArray();for(var world:server.worlds) {
            JsonObject item=new JsonObject();item.addProperty("dimension",world.provider.getDimension());item.addProperty("seed",Long.toString(world.getSeed()));
            item.addProperty("chunks",world.getChunkProvider().getLoadedChunks().size());item.addProperty("entities",world.loadedEntityList.size());
            item.addProperty("tiles",world.loadedTileEntityList.size());item.addProperty("world_time",world.getTotalWorldTime());worlds.add(item);
        }
        result.add("worlds",worlds);JsonArray gc=new JsonArray();for(var collector:ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject item=new JsonObject();item.addProperty("name",collector.getName());item.addProperty("count",collector.getCollectionCount());item.addProperty("time_ms",collector.getCollectionTime());gc.add(item);
        }
        result.add("gc",gc);return result;
    }
    private static void write(MinecraftServer server,String name,JsonObject result)throws Exception {
        Path directory=server.getFile("benchmark-results").toPath();Files.createDirectories(directory);
        Path path=directory.resolve(System.currentTimeMillis()+"-"+name+".json");
        Files.write(path,new GsonBuilder().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("E2EE_METRICS_DONE "+name+" "+path.toAbsolutePath());
    }
}
