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
    enum Variant implements IStringSerializable { MANA,NATURA,GAIA,UNKNOWN,GLIMMERING;public String getName(){return name().toLowerCase(Locale.ROOT);} }
    static class Flower extends Block {
        Flower(){super(Material.PLANTS);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,COLOR);}
    }
    static class Pylon extends Block {
        Pylon(){super(Material.IRON);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,VARIANT);}
    }
    static class Plain extends Block { Plain(){super(Material.ROCK);} }
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
    @Test void twilightForceFieldsUseNamedColorsInsteadOfAssumingMetadataOrder(){
        Flower field=new Flower();
        Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ModLightColors.registerDefaults(id->id.equals(new ResourceLocation("twilightforest:force_field"))?field:null,output::put);
        assertEquals(5,output.size());
        assertEquals(0x201,output.get(field.getDefaultState().withProperty(COLOR,EnumDyeColor.PURPLE)));
        assertEquals(0x012,output.get(field.getDefaultState().withProperty(COLOR,EnumDyeColor.BLUE)));
        assertFalse(output.containsKey(field.getDefaultState().withProperty(COLOR,EnumDyeColor.WHITE)));
    }
    @Test void onlyGlimmeringLivingwoodGetsTheSourcePreset(){
        Pylon wood=new Pylon();Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ModLightColors.registerDefaults(id->id.equals(new ResourceLocation("botania:livingwood"))?wood:null,output::put);
        assertEquals(Map.of(wood.getDefaultState().withProperty(VARIANT,Variant.GLIMMERING),0x480),output);
    }
    @Test void thaumcraftSixUsesAspectIdsAndLowercasedTranslationKeys(){
        Plain crystal=new Plain(),candle=new Plain(),nitor=new Plain();
        Map<ResourceLocation,Block> blocks=Map.of(new ResourceLocation("thaumcraft:crystal_terra"),crystal,
                new ResourceLocation("thaumcraft:candle_lightblue"),candle,new ResourceLocation("thaumcraft:nitor_red"),nitor);
        Map<IBlockState,Integer> output=new IdentityHashMap<>();ModLightColors.registerDefaults(blocks::get,output::put);
        assertEquals(3,output.size());assertEquals(0x070,output.get(crystal.getDefaultState()));
        assertEquals(0x68C,output.get(candle.getDefaultState()));assertEquals(0xF00,output.get(nitor.getDefaultState()));
    }
}
