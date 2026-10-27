package com.sumirelabs.pulsar.compat;

import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProjectRedLampColorsTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap(){net.minecraft.init.Bootstrap.register();}
    public static class Lamp extends TileEntity {
        int color,calls;
        public int getColor(){calls++;return color;}
    }
    @Test void colorChangesAndPowerOffDoNotRequireAStateChange() {
        Lamp lamp=new Lamp();lamp.color=EnumDyeColor.RED.getMetadata();
        AtomicInteger light=new AtomicInteger(15);
        var state=(IBlockState)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IBlockState.class},
                (p,m,a)->{if(m.getName().equals("getLightValue"))return light.get();throw new AssertionError(m);});
        var world=(IBlockAccess)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IBlockAccess.class},
                (p,m,a)->{if(m.getName().equals("getTileEntity"))return lamp;throw new AssertionError(m);});
        assertEquals(0xF00,ProjectRedLampColors.emission(state,world,BlockPos.ORIGIN));
        lamp.color=EnumDyeColor.BLUE.getMetadata();assertEquals(0x00F,ProjectRedLampColors.emission(state,world,BlockPos.ORIGIN));
        light.set(7);assertEquals(0x007,ProjectRedLampColors.emission(state,world,BlockPos.ORIGIN));
        light.set(0);int calls=lamp.calls;assertEquals(0,ProjectRedLampColors.emission(state,world,BlockPos.ORIGIN));assertEquals(calls,lamp.calls);
        light.set(15);lamp.color=16;assertEquals(0xFFF,ProjectRedLampColors.emission(state,world,BlockPos.ORIGIN));
    }
}
