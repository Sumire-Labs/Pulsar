package com.sumirelabs.lightbench;

import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;

/** Explicit mutation test in a disposable E2EE world, never inside a timing window. */
final class BedrockOreCheck {
    static JsonObject run(MinecraftServer server) throws Exception {
        var world=server.getWorld(0);
        Block host=null;
        for(Block block:Block.REGISTRY)
            if(block.getClass().getName().equals("li.cil.bedrockores.common.block.BlockBedrockOre"))host=block;
        if(host==null)throw new IllegalStateException("Bedrock Ores missing");
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)world.getChunkProvider().provideChunk(x,z);
        var pos=new BlockPos(8,200,8);
        for(int x=4;x<=12;x++)for(int z=4;z<=12;z++)for(int y=196;y<=204;y++)
            world.setBlockState(new BlockPos(x,y,z),Blocks.AIR.getDefaultState(),3);
        world.setBlockState(pos,host.getDefaultState(),3);
        var tile=world.getTileEntity(pos);
        var setter=tile.getClass().getMethod("setOreBlockState",IBlockState.class,int.class);
        var probe=LightProbe.create(world);
        setter.invoke(tile,Blocks.STONE.getDefaultState(),100);
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"initial dark");
        expect((Boolean)tile.getClass().getMethod("pulsar$hasOreLightNotifications").invoke(tile),"notification hook active");
        Object manager=world.getClass().getMethod("pulsar$getLightManager").invoke(world);
        Object contextual=manager.getClass().getMethod("contextualLight").invoke(manager);
        var field=contextual.getClass().getDeclaredField("trackedSources");field.setAccessible(true);
        Object tracked=field.get(contextual);var get=tracked.getClass().getDeclaredMethod("get",Object.class);get.setAccessible(true);
        if(get.invoke(tracked,pos)!=null) {
            Object flags=Class.forName("com.sumirelabs.pulsar.light.engine.LightInfo").getMethod("of",IBlockState.class).invoke(null,world.getBlockState(pos));
            Object policy=Class.forName("com.sumirelabs.pulsar.compat.BedrockOreLighting").getMethod("notified",TileEntity.class,IBlockState.class).invoke(null,tile,world.getBlockState(pos));
            Object fluid=Class.forName("com.sumirelabs.pulsar.compat.FluidLightBridge").getMethod("stateAt",net.minecraft.world.chunk.Chunk.class,int.class,int.class,int.class).invoke(null,world.getChunkProvider().provideChunk(0,0),pos.getX(),pos.getY(),pos.getZ());
            throw new IllegalStateException("bedrock ore still polled; flags="+flags+" policy="+policy+" fluid="+fluid+" host="+world.getBlockState(pos).getBlock().getClass());
        }
        setter.invoke(tile,Blocks.GLOWSTONE.getDefaultState(),100);
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==15,"dark to bright");
        expect(world.getLightFor(EnumSkyBlock.BLOCK,pos.up())==14,"bright neighbour");
        var saved=tile.writeToNBT(new NBTTagCompound());
        setter.invoke(tile,Blocks.STONE.getDefaultState(),100);
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"bright to dark");
        expect(world.getLightFor(EnumSkyBlock.BLOCK,pos.up())==0,"dark neighbour");
        tile.readFromNBT(saved);
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==15,"NBT restores emission");
        setter.invoke(tile,new Object[]{null,0});
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"ore exhaustion");
        world.removeTileEntity(pos);
        TileEntity replacement=tile.getClass().getConstructor().newInstance();
        world.setTileEntity(pos,replacement);
        setter.invoke(replacement,Blocks.GLOWSTONE.getDefaultState(),100);
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==15,"tile replacement");
        world.setBlockToAir(pos);
        probe.drainLight();expect(world.getLightFor(EnumSkyBlock.BLOCK,pos)==0,"host removal");
        JsonObject result=new JsonObject();result.addProperty("passed",true);
        result.addProperty("engine",probe.engine().name());result.addProperty("assertions",11);
        return result;
    }
    private static void expect(boolean value,String what){if(!value)throw new IllegalStateException(what);}
}
