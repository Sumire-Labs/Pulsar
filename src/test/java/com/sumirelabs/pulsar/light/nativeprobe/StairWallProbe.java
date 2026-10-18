package com.sumirelabs.pulsar.light.nativeprobe;

import com.sumirelabs.pulsar.api.FaceLightInfo;
import com.sumirelabs.pulsar.api.RawLightAccess;
import com.sumirelabs.pulsar.light.engine.FaceOcclusion;
import com.sumirelabs.pulsar.light.engine.StairRoomLight;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import java.util.*;

public final class StairWallProbe {
    public static void run() throws Exception {
        FaceOcclusion.registerDefaults();
        boolean legacy = Boolean.getBoolean("issue20.wallLegacy");
        int rooms = 0;
        for (var half : BlockStairs.EnumHalf.values()) for (EnumFacing outward : EnumFacing.HORIZONTALS) {
            for (boolean backOutside : new boolean[]{true, false}) {
                Room world = new Room(outward, half, backOutside);
                int inside = 0;
                for (int x=5;x<=9;x++) for(int y=5;y<=9;y++) for(int z=5;z<=9;z++) {
                    inside = Math.max(inside, world.light.sky(new BlockPos(x,y,z)));
                }
                if (inside != 0) throw new AssertionError("Sealed room acquired gameplay skylight");
                for(int renderer=0;renderer<(Boolean.getBoolean("issue20.celeritas")?3:2);renderer++) {
                    int stairMax=0, wallMax=0, sideMax=0;
                    EnumFacing u=outward.getAxis()==EnumFacing.Axis.X?EnumFacing.SOUTH:EnumFacing.EAST;
                    for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++) {
                        BlockPos pos=world.stair.offset(u,a).up(b);
                        IBlockState state=world.getBlockState(pos);
                        // Use the native cube template for neighboring wall blocks.
                        var quads=pos.equals(world.stair)?PartialSurfaceProbe.bake(state):cube();
                        for(var quad:quads) {
                            EnumFacing towardStair = a == -1 && b == 0 ? u : a == 1 && b == 0 ? u.getOpposite()
                                    : a == 0 && b == -1 ? EnumFacing.UP : a == 0 && b == 1 ? EnumFacing.DOWN : null;
                            if(quad.getFace()!=outward.getOpposite() && quad.getFace()!=towardStair)continue;
                            for(int packed:PartialSurfaceProbe.renderAt(renderer,world,state,quad,pos)) {
                                int value=packed>>>16;
                                if(pos.equals(world.stair))stairMax=Math.max(stairMax,value);
                                else if(quad.getFace()==outward.getOpposite())wallMax=Math.max(wallMax,value);
                                else sideMax=Math.max(sideMax,value);
                            }
                        }
                    }
                    System.out.println("STAIR_WALL renderer="+renderer+" facing="+outward+" half="+half+" backOutside="+backOutside
                            +" rawStair="+world.light.sky(world.stair)+" rawInside="+inside+" stairUV="+stairMax+" wallUV="+wallMax+" sideUV="+sideMax);
                    if (stairMax != 0 || wallMax != 0) throw new AssertionError("Room-facing surface is lit");
                    if (backOutside && ((sideMax > 0) != legacy)) {
                        throw new AssertionError("Wall-side regression control failed: renderer=" + renderer + " legacy=" + legacy + " side=" + sideMax);
                    }
                }
                rooms++;
            }
            // A real opening must continue to admit light; do not fix the leak by darkening the room.
            Room open = new Room(outward, half, true, true);
            if (open.light.sky(open.stair.offset(outward.getOpposite())) == 0) throw new AssertionError("Open aperture did not propagate light");
            BlockPos above = open.stair.up();
            for (int renderer = 0; renderer < (Boolean.getBoolean("issue20.celeritas") ? 3 : 2); renderer++) {
                int maximum = 0;
                for (var quad : cube()) if (quad.getFace() == outward.getOpposite()) {
                    for (int packed : PartialSurfaceProbe.renderAt(renderer, open, open.getBlockState(above), quad, above)) maximum = Math.max(maximum, packed >>> 16);
                }
                if (maximum == 0) throw new AssertionError("Open aperture's illuminated wall was darkened");
            }
        }
        System.out.println("STAIR_WALL_PASS rooms=" + rooms + " legacy=" + legacy);
    }

    private static java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> cube() throws Exception {
        return PartialSurfaceProbe.bake(Blocks.STONE.getDefaultState());
    }

    private static final class Room implements IBlockAccess, RawLightAccess {
        final Map<BlockPos,IBlockState> blocks=new HashMap<>();
        final BlockPos stair;
        final StairRoomLight light;
        Room(EnumFacing outward,BlockStairs.EnumHalf half,boolean backOutside)throws Exception {
            this(outward, half, backOutside, false);
        }
        Room(EnumFacing outward,BlockStairs.EnumHalf half,boolean backOutside,boolean open)throws Exception {
            for(int x=4;x<=10;x++)for(int y=4;y<=10;y++)for(int z=4;z<=10;z++) {
                if(x==4||x==10||y==4||y==10||z==4||z==10)blocks.put(new BlockPos(x,y,z),Blocks.STONE.getDefaultState());
            }
            stair=new BlockPos(7+outward.getXOffset()*3,6,7+outward.getZOffset()*3);
            blocks.put(stair,Blocks.STONE_BRICK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING,backOutside?outward:outward.getOpposite())
                    .withProperty(BlockStairs.HALF,half));
            if (open) blocks.put(stair, Blocks.AIR.getDefaultState());
            light=new StairRoomLight(blocks);
        }
        public IBlockState getBlockState(BlockPos pos){return blocks.getOrDefault(pos,Blocks.AIR.getDefaultState());}
        public int pulsar$getRawLight(EnumSkyBlock type,BlockPos pos){return type==EnumSkyBlock.SKY?light.sky(pos):0;}
        public int getCombinedLight(BlockPos pos,int emission){return ((FaceLightInfo)getBlockState(pos)).pulsar$getLightFor(this,EnumSkyBlock.SKY,pos)<<20|emission<<4;}
        public TileEntity getTileEntity(BlockPos pos){return null;}
        public boolean isAirBlock(BlockPos pos){return getBlockState(pos).getBlock()==Blocks.AIR;}
        public Biome getBiome(BlockPos pos){return null;}
        public int getStrongPower(BlockPos pos,EnumFacing face){return 0;}
        public WorldType getWorldType(){return WorldType.DEFAULT;}
        public boolean isSideSolid(BlockPos pos,EnumFacing face,boolean fallback){return getBlockState(pos).isSideSolid(this,pos,face);}
    }
}
