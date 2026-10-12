package com.sumirelabs.hotpathaudit;

import com.google.gson.GsonBuilder;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.world.PulsarWorld;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.*;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/** External regression fixture; never included in production candidates. */
@Mod(modid="hotpathaudit",name="Hotpath regression audit",version="1",dependencies="required-after:pulsar")
public final class HotpathAudit {
    @Mod.EventHandler
    public void start(FMLServerStartingEvent event) { event.registerServerCommand(new AuditCommand()); }

    private static final class AuditCommand extends CommandBase {
        @Override public String getName() { return "hotpathaudit"; }
        @Override public String getUsage(ICommandSender sender) { return "hotpathaudit"; }
        @Override public int getRequiredPermissionLevel() { return 2; }
        @Override public void execute(MinecraftServer server,ICommandSender sender,String[] args) throws CommandException {
            try { if(args.length>0 && args[0].equals("inspect")) inspect(server,sender); else run(server.getWorld(0)); }
            catch(Exception error) { throw new CommandException("Hotpath audit failed: "+error); }
        }
    }

    private static void drain(WorldServer world) throws Exception {
        WorldLightManager manager=((PulsarWorld)world).pulsar$getLightManager();
        long deadline=System.nanoTime()+60_000_000_000L;
        while(manager.hasUpdates()) {
            if(System.nanoTime()>deadline) throw new IllegalStateException("Audit drain timeout");
            Thread.yield();
        }
    }

    private static String hash(WorldServer world) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int y=4;y<64;y++) for(int z=16;z<48;z++) for(int x=16;x<48;x++) {
            pos.setPos(x,y,z);
            digest.update((byte)world.getChunk(pos).getLightFor(EnumSkyBlock.SKY,pos));
            digest.update((byte)world.getChunk(pos).getLightFor(EnumSkyBlock.BLOCK,pos));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void inspect(MinecraftServer server,ICommandSender sender) throws Exception {
        server.getCommandManager().executeCommand(sender,"lightbench updates 2000 1000");
        WorldServer world=server.getWorld(0);drain(world);
        IBlockState air=Blocks.AIR.getDefaultState();int info=LightInfo.of(air);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("air_info",info);report.put("air_context_flags",info&LightInfo.CONTEXT_MASK);
        report.put("air_state_count",Blocks.AIR.getBlockState().getValidStates().size());
        report.put("air_is_base_state",air==Blocks.AIR.getBlockState().getBaseState());
        report.put("fluid_bridge_loaded",com.sumirelabs.pulsar.compat.FluidLightBridge.LOADED);
        List<Map<String,Object>> chunks=new ArrayList<>();Map<String,Integer> classes=new TreeMap<>();
        int empty=0,nonempty=0,absent=0;
        for(var chunk:world.getChunkProvider().getLoadedChunks()) {
            int ce=0,cn=0,ca=0;
            for(var section:chunk.getBlockStorageArray()) {
                if(section==null){ca++;continue;}
                classes.merge(section.getClass().getName(),1,Integer::sum);
                if(section.isEmpty())ce++;else cn++;
            }
            empty+=ce;nonempty+=cn;absent+=ca;
            Map<String,Object> row=new LinkedHashMap<>();row.put("x",chunk.x);row.put("z",chunk.z);row.put("empty",ce);row.put("nonempty",cn);row.put("absent",ca);chunks.add(row);
        }
        report.put("empty_sections",empty);report.put("nonempty_sections",nonempty);report.put("absent_sections",absent);report.put("section_classes",classes);report.put("chunks",chunks);
        List<Map<String,Object>> engines=new ArrayList<>();
        Object manager=((PulsarWorld)world).pulsar$getLightManager();
        for(String laneName:new String[]{"skyWorker","blockWorker"}) {
            var laneField=manager.getClass().getDeclaredField(laneName);laneField.setAccessible(true);Object lane=laneField.get(manager);
            if(lane==null)continue;
            var poolField=lane.getClass().getDeclaredField("enginePool");poolField.setAccessible(true);
            for(Object engine:(java.util.concurrent.ConcurrentLinkedDeque<?>)poolField.get(lane)) {
                Map<String,Object> row=new LinkedHashMap<>();row.put("lane",laneName);
                Class<?> type=engine.getClass();while(type!=null&&!type.getSimpleName().equals("LightEngineCache"))type=type.getSuperclass();
                for(String name:new String[]{"plainAir","cacheEmptyAir"}) {
                    try {var field=type.getDeclaredField(name);field.setAccessible(true);row.put(name,field.get(engine));}
                    catch(NoSuchFieldException missing){row.put(name,"absent");}
                }
                engines.add(row);
            }
        }
        report.put("cached_engine_flags",engines);
        Path out=world.getSaveHandler().getWorldDirectory().toPath().resolve("lightbench-results/cache-inspection.json");Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static void run(WorldServer world) throws Exception {
        for(int z=-1;z<=4;z++) for(int x=-1;x<=4;x++) world.getChunk(x,z);
        drain(world);
        for(int z=16;z<48;z++) for(int x=16;x<48;x++) world.setBlockState(new BlockPos(x,48,z),Blocks.STONE.getDefaultState(),2);
        BlockPos roof=new BlockPos(32,48,32),lamp=new BlockPos(32,46,32);
        world.setBlockState(lamp,Blocks.GLOWSTONE.getDefaultState(),2);drain(world);
        List<IBlockState> states=new ArrayList<>();
        states.add(Blocks.AIR.getDefaultState());states.add(Blocks.STONE.getDefaultState());states.add(Blocks.GLASS.getDefaultState());
        states.add(Blocks.STONE_SLAB.getStateFromMeta(0));states.add(Blocks.STONE_SLAB.getStateFromMeta(8));
        for(int meta:new int[]{0,1,2,3,4,5,6,7}) states.add(Blocks.OAK_STAIRS.getStateFromMeta(meta));
        states.add(Blocks.WATER.getStateFromMeta(0));states.add(Blocks.WATER.getStateFromMeta(7));
        List<Map<String,Object>> cases=new ArrayList<>();int sidedLit=0;
        for(IBlockState state:states) {
            world.setBlockState(roof,state,2);drain(world);
            String on=hash(world);int info=LightInfo.of(state);
            int sky=world.getChunk(roof).getLightFor(EnumSkyBlock.SKY,roof),block=world.getChunk(roof).getLightFor(EnumSkyBlock.BLOCK,roof);
            if((info&LightInfo.REGISTRY)!=0 && LightInfo.faceBits(info)!=0 && (sky>1||block>1)) sidedLit++;
            world.setBlockState(lamp,Blocks.AIR.getDefaultState(),2);drain(world);String off=hash(world);
            world.setBlockState(lamp,Blocks.GLOWSTONE.getDefaultState(),2);drain(world);String restored=hash(world);
            if(!on.equals(restored)) throw new IllegalStateException("Lamp round trip changed stored light: "+state);
            Map<String,Object> row=new LinkedHashMap<>();row.put("state",state.getBlock().getRegistryName().toString());row.put("meta",state.getBlock().getMetaFromState(state));row.put("face_bits",LightInfo.faceBits(info));row.put("registry",(info&LightInfo.REGISTRY)!=0);row.put("roof_sky",sky);row.put("roof_block",block);row.put("lamp_on_hash",on);row.put("lamp_off_hash",off);row.put("lamp_restored_hash",restored);cases.add(row);
        }
        if(sidedLit==0) throw new IllegalStateException("Fixture did not light any sided state");
        Map<String,Object> report=new LinkedHashMap<>();report.put("valid",true);report.put("cases",cases);report.put("lit_sided_cases",sidedLit);report.put("cells_per_snapshot",32*32*60);report.put("channels",2);
        Path out=world.getSaveHandler().getWorldDirectory().toPath().resolve("lightbench-results/hotpath-audit.json");Files.createDirectories(out.getParent());Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
}
