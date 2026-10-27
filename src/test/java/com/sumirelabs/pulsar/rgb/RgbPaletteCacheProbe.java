package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.api.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.BlockStateContainer;
import java.util.Map;

/** Actual transformed state/container caches, in a fresh JVM with frozen base registries. */
public final class RgbPaletteCacheProbe {
    private static final class DynamicBlock extends Block implements PositionalColoredLightSource {
        DynamicBlock(){super(Material.ROCK);}
        public int getColoredLightEmission(IBlockAccess world,BlockPos pos,IBlockState state){throw new AssertionError("Static metadata must not call the world");}
    }
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        IBlockState state=Blocks.STONE.getDefaultState();
        IBlockState dynamic=new DynamicBlock().getDefaultState();
        Block.BLOCK_STATE_IDS.put(dynamic,100000);
        BlockLightColors.registerEmission(state,4,8,2);
        BlockLightFilters.register(state,0,15,15);
        BlockLightColors.freeze();
        var section=new BlockStateContainer();section.set(0,0,0,state);
        var flags=(RgbFilterPalette)section;
        require(state instanceof RgbCachedState,"Missing state mixin");
        require(flags.pulsar$mayEmitAbsoluteRgb()&&flags.pulsar$mayFilterSky(),"Initial section classification");
        require(RgbStateProperties.color(RgbStateProperties.of(state))==0x482,"Initial state value");
        require(RgbStateProperties.hasDynamicStates(),"Dynamic base detected");
        String initialProfile=BlockLightColors.profile();
        var initial=RgbPaletteOverrides.current();
        var stateCache=state.getClass().getDeclaredField("pulsar$rgbProperties");stateCache.setAccessible(true);
        long oldStateCache=stateCache.getLong(state);
        var sectionCache=section.getClass().getDeclaredField("pulsar$filterCache");sectionCache.setAccessible(true);
        Object oldSectionCache=sectionCache.get(section);
        var overrides=new RgbPaletteOverrides.Values(Map.of(state,0x00F,dynamic,0),Map.of(state,0));
        RgbPaletteOverrides.install(overrides);
        require(RgbStateProperties.color(RgbStateProperties.of(state))==0x00F,"State cache must refresh");
        require(!flags.pulsar$mayEmitAbsoluteRgb()&&!flags.pulsar$mayFilterSky(),"Section cache must refresh without block edits");
        stateCache.setLong(state,oldStateCache);sectionCache.set(section,oldSectionCache);
        require(RgbStateProperties.color(RgbStateProperties.of(state))==0x00F,"Late old-epoch state write must be ignored");
        require(!flags.pulsar$mayEmitAbsoluteRgb()&&!flags.pulsar$mayFilterSky(),"Late old-epoch section write must be ignored");
        require(!RgbStateProperties.hasDynamicStates(),"Override masks dynamic API");
        require(!initialProfile.equals(BlockLightColors.profile()),"Persistence profile changed");
        require(RgbStateProperties.color(RgbStateProperties.compute(state,initial))==0x482,"Captured snapshot remains coherent");
        RgbPaletteOverrides.install(new RgbPaletteOverrides.Values(Map.of(),Map.of()));
        require(RgbStateProperties.color(RgbStateProperties.of(state))==0x482,"Deleted row restores API strength");
        require(flags.pulsar$mayEmitAbsoluteRgb()&&flags.pulsar$mayFilterSky(),"Deleted row restores section flags");
        require(RgbStateProperties.hasDynamicStates(),"Deleted row restores dynamic API");
        require(initialProfile.equals(BlockLightColors.profile()),"Same effective palette has same persistence profile");
        RgbPalettePersistenceProbe.verify();
        System.out.println("RGB_PALETTE_CACHE_PASS");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
