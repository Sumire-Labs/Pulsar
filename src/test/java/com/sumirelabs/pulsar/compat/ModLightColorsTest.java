package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.api.ColoredLightSource;
import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModLightColorsTest {
    private static final PropertyEnum<EnumDyeColor> COLOR=PropertyEnum.create("color",EnumDyeColor.class);
    private static final PropertyEnum<Variant> VARIANT=PropertyEnum.create("variant",Variant.class);
    enum Variant implements IStringSerializable { MANA,NATURA,GAIA,UNKNOWN;public String getName(){return name().toLowerCase(Locale.ROOT);} }
    static class Flower extends Block {
        Flower(){super(Material.PLANTS);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,COLOR);}
    }
    static class Pylon extends Block {
        Pylon(){super(Material.IRON);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,VARIANT);}
    }
    static class CustomFlower extends Flower implements ColoredLightSource {
        public int getColoredLightEmission(IBlockState state){throw new AssertionError("Preset must not call mod APIs");}
    }
    @BeforeAll static void bootstrap(){Bootstrap.register();}
    @Test void optionalMissingModsAndUnknownStatesStayUntouched(){
        Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ModLightColors.registerDefaults(id->null,output::put);assertTrue(output.isEmpty());
        ModLightColors.registerDefaults(id->id.equals(new ResourceLocation("botania","shinyFlower"))?new Pylon():null,output::put);
        assertTrue(output.isEmpty());
        ModLightColors.registerDefaults(id->new CustomFlower(),output::put);assertTrue(output.isEmpty());
    }
    @Test void verifiedStatePropertiesSelectAllSixteenColorsAndKnownPylons(){
        Flower flowers=new Flower();Pylon pylons=new Pylon();
        Map<ResourceLocation,Block> blocks=Map.of(new ResourceLocation("botania","shinyFlower"),flowers,new ResourceLocation("botania","pylon"),pylons);
        Map<IBlockState,Integer> output=new IdentityHashMap<>();ModLightColors.registerDefaults(blocks::get,output::put);
        assertEquals(19,output.size());
        for(var state:flowers.getBlockState().getValidStates())assertEquals(RgbDefaultPalette.glassTransmittance(state.getValue(COLOR)),output.get(state));
        assertEquals(0x08F,output.get(pylons.getDefaultState().withProperty(VARIANT,Variant.MANA)));
        assertEquals(0x0F0,output.get(pylons.getDefaultState().withProperty(VARIANT,Variant.NATURA)));
        assertEquals(0xFAD,output.get(pylons.getDefaultState().withProperty(VARIANT,Variant.GAIA)));
        assertFalse(output.containsKey(pylons.getDefaultState().withProperty(VARIANT,Variant.UNKNOWN)));
    }
}
