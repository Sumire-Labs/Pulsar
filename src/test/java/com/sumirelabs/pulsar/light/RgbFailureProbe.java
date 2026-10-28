package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.api.PositionalColoredLightSource;
import com.sumirelabs.pulsar.rgb.RgbContextualLightManager;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

final class RgbFailureProbe {
    private static final class Source extends Block implements PositionalColoredLightSource {
        boolean broken=true; int calls,color=0x900;
        Source(){super(Material.ROCK);setLightLevel(.6f);}
        public int getColoredLightEmission(IBlockAccess world,BlockPos pos,IBlockState state) {
            calls++;if(broken)throw new NullPointerException("missing tile");return color;
        }
    }
    static void verify(MultipartLightProbe.ProbeWorld world) {
        var source=new Source();var state=source.getDefaultState();
        world.chunk.getBlockStorageArray()[4].set(8,0,8,state);
        world.manager.queueBlockChange(8,64,8);world.manager.publishContextualLight();
        check((RgbContextualLightManager.properties(world,state,8,64,8)&0xFFF)==0,"Failed replacement became full white");
        for(int i=0;i<100;i++)world.manager.publishContextualLight();
        check(source.calls<12,"RGB callback was retried every tick");
        source.broken=false;
        for(int i=0;i<33;i++)world.manager.publishContextualLight();
        check((RgbContextualLightManager.properties(world,state,8,64,8)&0xFFF)==0x900,"RGB source did not recover");
        source.broken=true;source.color=0x090;
        world.manager.publishContextualLight();
        check((RgbContextualLightManager.properties(world,state,8,64,8)&0xFFF)==0x900,"Failure replaced last good RGB");
        world.manager.unregisterChunk(0,0);
        for(int i=0;i<40;i++)world.manager.publishContextualLight();
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
