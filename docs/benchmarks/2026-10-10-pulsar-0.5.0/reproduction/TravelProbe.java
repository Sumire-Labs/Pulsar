package com.sumirelabs.fourbench;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.command.ICommand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Local companion to Lightbench: fixed camera route and native chunk readiness. */
@Mod(modid="benchtravel",name="Local Lightbench travel observer",version="1")
public class TravelProbe {
    static final Path ROOT=Paths.get(System.getProperty("user.dir"));
    static volatile boolean active;
    static volatile int phase;
    static int index,stepTicks,readyTicks,cameraY=96;
    static long submitted,tickStarted,previousFrame;
    static Object world,player,provider,probe,sky,block;
    static Class<?> chunkClass,posClass;
    static Method loaded,ready,read,move;
    static Constructor<?> position;
    static final List<Long> ticks=new ArrayList<>(),frames=new ArrayList<>(),latencies=new ArrayList<>();
    static final Map<Long,Object> retained=new TreeMap<>();
    static int lightChecks;
    public static boolean isRunning(){return active;}
    static Method m(Class<?> c,String a,String b,Class<?>...p)throws Exception {try{return c.getMethod(a,p);}catch(NoSuchMethodException e){return c.getMethod(b,p);}}
    static Object field(Object obj,String a,String b)throws Exception{try{return obj.getClass().getField(a).get(obj);}catch(NoSuchFieldException e){return obj.getClass().getField(b).get(obj);}}
    @Mod.EventHandler public void init(FMLInitializationEvent e){if(Files.exists(ROOT.resolve("four-engine-bench.marker")))MinecraftForge.EVENT_BUS.register(this);}
    @Mod.EventHandler public void start(FMLServerStartingEvent e){
        ICommand cmd=(ICommand)Proxy.newProxyInstance(ICommand.class.getClassLoader(),new Class<?>[]{ICommand.class},(p,method,a)->{
            switch(method.getName()){
                case "getName":case "func_71517_b":return "benchtravel";
                case "getUsage":case "func_71518_a":return "benchtravel";
                case "getAliases":case "func_71514_a":case "getTabCompletions":case "func_184883_a":return Collections.emptyList();
                case "checkPermission":case "func_184882_a":return true;
                case "isUsernameIndex":case "func_82358_a":return false;
                case "compareTo":return 0;
                case "execute":case "func_184881_a":begin();return null;
                case "toString":return "benchtravel";
                case "hashCode":return System.identityHashCode(p);
                case "equals":return p==a[0];
                default:throw new UnsupportedOperationException(method.getName());
            }
        });e.registerServerCommand(cmd);
    }
    static void begin()throws Exception{
        world=Class.forName("net.minecraftforge.common.DimensionManager").getMethod("getWorld",int.class).invoke(null,0);
        player=((List<?>)field(world,"playerEntities","field_73010_i")).get(0);
        provider=m(world.getClass(),"getChunkProvider","func_72863_F").invoke(world);
        chunkClass=Class.forName("net.minecraft.world.chunk.Chunk");posClass=Class.forName("net.minecraft.util.math.BlockPos");
        position=posClass.getConstructor(int.class,int.class,int.class);
        Class<?> lp=Class.forName("com.sumirelabs.lightbench.LightProbe"),wc=Class.forName("net.minecraft.world.World"),ec=Class.forName("net.minecraft.world.EnumSkyBlock");
        probe=lp.getMethod("create",wc).invoke(null,world);
        loaded=m(provider.getClass(),"getLoadedChunk","func_186026_b",int.class,int.class);
        ready=lp.getMethod("isChunkLightReady",chunkClass);read=lp.getMethod("readRawLight",chunkClass,ec,posClass);
        sky=Enum.valueOf((Class)ec,"SKY");block=Enum.valueOf((Class)ec,"BLOCK");
        move=m(player.getClass(),"setPositionAndUpdate","func_70634_a",double.class,double.class,double.class);
        if(Files.exists(ROOT.resolve("bench-travel-height.txt")))cameraY=Integer.parseInt(Files.readString(ROOT.resolve("bench-travel-height.txt")).trim());
        active=true;phase=0;index=0;submit();
    }
    static int x(){return (phase==0?-40000:40000)+index*16;}
    static int z(){return phase==0?-40000:40000;}
    static void submit()throws Exception{
        stepTicks=0;readyTicks=-1;submitted=System.nanoTime();
        move.invoke(player,x()+0.5,(double)cameraY,z()+0.5);
    }
    static boolean regionReady()throws Exception{
        int cx=x()>>4,cz=z()>>4;
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
            Object chunk=loaded.invoke(provider,cx+dx,cz+dz);
            if(chunk==null||!(Boolean)ready.invoke(probe,chunk))return false;
        }
        return true;
    }
    static void validateRegion()throws Exception{
        int cx=x()>>4,cz=z()>>4;
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
            int xx=cx+dx,zz=cz+dz;Object chunk=loaded.invoke(provider,xx,zz);
            Object pos=position.newInstance((xx<<4)+8,255,(zz<<4)+8);
            int s=((Number)read.invoke(probe,chunk,sky,pos)).intValue(),b=((Number)read.invoke(probe,chunk,block,pos)).intValue();
            if(s!=15||b!=0)throw new IllegalStateException("Travel top-light mismatch "+xx+","+zz+" sky="+s+" block="+b);
            lightChecks+=2;retained.put(((long)xx<<32)^(zz&0xffffffffL),chunk);
        }
    }
    @SubscribeEvent public void server(TickEvent.ServerTickEvent e){
        if(!active)return;
        if(e.phase==TickEvent.Phase.START){tickStarted=System.nanoTime();return;}
        try{
            if(phase==1&&tickStarted!=0)ticks.add(System.nanoTime()-tickStarted);
            stepTicks++;
            if(readyTicks<0&&regionReady()){
                readyTicks=stepTicks;
                if(phase==1){latencies.add(System.nanoTime()-submitted);validateRegion();}
            }
            if(stepTicks>=400){finish("Readiness timeout at waypoint "+index);return;}
            if(readyTicks>=0&&stepTicks>=20){
                if(++index==(phase==0?8:32)){
                    if(phase==0){phase=1;index=0;previousFrame=0;}
                    else{finish(null);return;}
                }
                submit();
            }
        }catch(Exception ex){try{finish(ex.toString());}catch(Exception nested){throw new RuntimeException(nested);}}
    }
    @SubscribeEvent public void frame(TickEvent.RenderTickEvent e){
        if(e.phase!=TickEvent.Phase.START||!active||phase!=1)return;
        long now=System.nanoTime();
        synchronized(frames){if(previousFrame!=0&&frames.size()<1000000)frames.add(now-previousFrame);previousFrame=now;}
    }
    static JsonArray values(List<Long> input){JsonArray r=new JsonArray();for(long n:input)r.add(n);return r;}
    static String terrainHash()throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        Class<?> state=Class.forName("net.minecraft.block.state.IBlockState"),bc=Class.forName("net.minecraft.block.Block");
        Method get=m(chunkClass,"getBlockState","func_186032_a",int.class,int.class,int.class);
        Method id=m(bc,"getStateId","func_176210_f",state);
        for(var entry:retained.entrySet()){
            long key=entry.getKey();for(int shift=0;shift<64;shift+=8)digest.update((byte)(key>>>shift));
            Object chunk=entry.getValue();
            for(int y=0;y<256;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                int value=((Number)id.invoke(null,get.invoke(chunk,x,y,z))).intValue();
                digest.update((byte)value);digest.update((byte)(value>>>8));digest.update((byte)(value>>>16));digest.update((byte)(value>>>24));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void finish(String failure)throws Exception{
        active=false;
        JsonObject r=new JsonObject();r.addProperty("mode","travel");r.addProperty("valid",failure==null&&latencies.size()==32);
        if(failure!=null)r.addProperty("failure",failure);
        r.addProperty("variant",Files.readString(ROOT.resolve("lightbench-variant.txt")).trim());
        r.addProperty("warmup_waypoints",8);r.addProperty("measured_waypoints",32);
        r.addProperty("camera_y",cameraY);r.addProperty("stride_blocks",16);r.addProperty("minimum_dwell_ticks",20);r.addProperty("readiness_radius_chunks",2);
        r.addProperty("readiness_polled_each_server_tick",true);r.addProperty("client_mesh_completion_not_measured",true);
        r.addProperty("top_light_checks",lightChecks);r.addProperty("terrain_chunks",retained.size());
        r.add("readiness_latency_nanos",values(latencies));r.add("server_tick_nanos",values(ticks));
        synchronized(frames){r.add("frame_intervals_nanos",values(frames));}
        if(failure==null)r.addProperty("terrain_sha256",terrainHash());
        r.addProperty("terrain_audit_excluded_from_timings",true);retained.clear();
        Path results=ROOT.resolve("saves/Bench Updates/lightbench-results");Files.createDirectories(results);
        Files.writeString(results.resolve("travel.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));
    }
}
