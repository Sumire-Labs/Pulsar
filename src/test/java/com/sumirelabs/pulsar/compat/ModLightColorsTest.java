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
    enum Variant implements IStringSerializable { MANA,NATURA,GAIA,UNKNOWN,GLIMMERING,GLOWSHROOM;public String getName(){return name().toLowerCase(Locale.ROOT);} }
    enum MushroomType implements IStringSerializable {
        GREEN,BLUE,PURPLE,AMBER,UNKNOWN;
        public String getName(){return name().toLowerCase(Locale.ROOT);}
    }
    private static final PropertyEnum<MushroomType> TYPE=PropertyEnum.create("type",MushroomType.class);
    static class Mushroom extends Block {
        Mushroom(){super(Material.PLANTS);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,TYPE);}
    }
    static class Flower extends Block {
        Flower(){super(Material.PLANTS);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,COLOR);}
    }
    static class Pylon extends Block {
        Pylon(){super(Material.IRON);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,VARIANT);}
    }
    static class Plain extends Block { Plain(){super(Material.ROCK);} }
    private static final net.minecraft.block.properties.PropertyBool LIT=net.minecraft.block.properties.PropertyBool.create("lit");
    static class Campfire extends Block {
        Campfire(){super(Material.WOOD);}
        protected BlockStateContainer createBlockState(){return new BlockStateContainer(this,LIT);}
        public int getLightValue(IBlockState state){return state.getValue(LIT)?15:0;}
    }
    static class CustomFlower extends Flower implements ColoredLightSource {
        public int getColoredLightEmission(IBlockState state){throw new AssertionError("Preset must not call mod APIs");}
    }
    @BeforeAll static void bootstrap(){Bootstrap.register();}
    @Test void futureMcHuesKeepSoulBrightnessAndExtinguishedCampfiresDark(){
        Plain lantern=new Plain(),soul=new Plain();Campfire fire=new Campfire();
        lantern.setLightLevel(1f);soul.setLightLevel(10f/15f);
        var blocks=Map.of(new ResourceLocation("futuremc:lantern"),(Block)lantern,
                new ResourceLocation("futuremc:soul_fire_lantern"),soul,
                new ResourceLocation("futuremc:campfire"),fire);
        Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ModLightColors.registerDefaults(blocks::get,output::put);
        assertEquals(4,output.size());
        for(boolean projected:new boolean[]{false,true}) {
            int warm=com.sumirelabs.pulsar.rgb.RgbLightAccess.effectiveEmission(output.get(lantern.getDefaultState()),15,projected);
            int cool=com.sumirelabs.pulsar.rgb.RgbLightAccess.effectiveEmission(output.get(soul.getDefaultState()),10,projected);
            assertEquals(15,warm>>>8);assertEquals(10,cool&15);assertTrue((cool&15)>(cool>>>8));
            for(boolean lit:new boolean[]{false,true}) {
                var state=fire.getDefaultState().withProperty(LIT,lit);
                int value=com.sumirelabs.pulsar.rgb.RgbLightAccess.effectiveEmission(output.get(state),state.getLightValue(),projected);
                assertEquals(lit?0xFA6:0,value);
            }
        }
    }
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
    @Test void naturaUsesNamesNotTheOldGreenPurpleBlueMetadataOrder(){
        Mushroom mushroom=new Mushroom();Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ModLightColors.registerDefaults(id->id.equals(new ResourceLocation("natura:nether_glowshroom"))?mushroom:null,output::put);
        assertEquals(4,output.size());
        assertEquals(0x091,output.get(mushroom.getDefaultState().withProperty(TYPE,MushroomType.GREEN)));
        assertEquals(0x039,output.get(mushroom.getDefaultState().withProperty(TYPE,MushroomType.BLUE)));
        assertEquals(0x708,output.get(mushroom.getDefaultState().withProperty(TYPE,MushroomType.PURPLE)));
        assertEquals(0xFA3,output.get(mushroom.getDefaultState().withProperty(TYPE,MushroomType.AMBER)));
        assertFalse(output.containsKey(mushroom.getDefaultState().withProperty(TYPE,MushroomType.UNKNOWN)));
    }
    @Test void bopColorsOnlyGlowshroomAndSkipsMissingOrWrongProperties(){
        Pylon variants=new Pylon();Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ResourceLocation id=new ResourceLocation("biomesoplenty:mushroom");
        ModLightColors.registerDefaults(key->key.equals(id)?variants:null,output::put);
        assertEquals(Map.of(variants.getDefaultState().withProperty(VARIANT,Variant.GLOWSHROOM),0x940),output);
        output.clear();
        ModLightColors.registerDefaults(key->key.equals(id)?new Mushroom():null,output::put);
        assertTrue(output.isEmpty());
        ModLightColors.registerDefaults(key->key.equals(new ResourceLocation("natura:nether_glowshroom"))?variants:null,output::put);
        assertTrue(output.isEmpty());
    }
    @Test void renamedSourcesAndSplitChiselBlocksUseExplicitIdsWithoutChangingBrightness(){
        Map<String,Integer> expected=Map.ofEntries(
                Map.entry("tconstruct:stone_torch",0xDA8), Map.entry("biomesoplenty:crystal",0x0BF),
                Map.entry("appliedenergistics2:quartz_vibrant_glass",0xFFF), Map.entry("appliedenergistics2:quartz_fixture",0xEEE),
                Map.entry("natura:nether_green_large_glowshroom",0x091), Map.entry("natura:nether_blue_large_glowshroom",0x039),
                Map.entry("natura:nether_purple_large_glowshroom",0x708),
                Map.entry("chisel:glowstone",0xFCA), Map.entry("chisel:glowstone1",0xFCA), Map.entry("chisel:glowstone2",0xFCA));
        Map<ResourceLocation,Block> blocks=new HashMap<>();
        expected.keySet().forEach(id->blocks.put(new ResourceLocation(id),new Plain()));
        blocks.put(new ResourceLocation("chisel:lavastone"),new Plain());
        Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ModLightColors.registerDefaults(blocks::get,output::put);
        assertEquals(expected.size(),output.size());
        expected.forEach((id,hue)->assertEquals(hue,output.get(blocks.get(new ResourceLocation(id)).getDefaultState())));
        blocks.values().forEach(block->assertEquals(0,block.getDefaultState().getLightValue(),"Registering a hue must not turn a block into a light source"));
        assertFalse(output.containsKey(blocks.get(new ResourceLocation("chisel:lavastone")).getDefaultState()));
    }
    @Test void rekindledMageLightFollowsAllDyeStatesAndSkipsUnknownLayouts(){
        Flower light=new Flower();Map<IBlockState,Integer> output=new IdentityHashMap<>();
        ResourceLocation id=new ResourceLocation("arsmagica2:block_mage_light");
        ModLightColors.registerDefaults(key->key.equals(id)?light:null,output::put);
        assertEquals(16,output.size());
        for(var state:light.getBlockState().getValidStates())
            assertEquals(RgbDefaultPalette.glassTransmittance(state.getValue(COLOR)),output.get(state));
        output.clear();
        ModLightColors.registerDefaults(key->key.equals(id)?new Plain():null,output::put);
        assertTrue(output.isEmpty());
        ModLightColors.registerDefaults(key->key.equals(id)?new CustomFlower():null,output::put);
        assertTrue(output.isEmpty());
    }
    @Test void legacyAmberAndRekindledFixedSourcesPreserveOriginalLightLevels(){
        Map<String,Integer> expected=Map.of(
                "natura:nether_amber_large_glowshroom",0xFA3,
                "arsmagica2:vinteum_torch",0xADF,"arsmagica2:warding_candle",0xDA8,
                "arsmagica2:otherworld_aura",0xF0B,"arsmagica2:celestial_prism",0xFFF);
        Map<ResourceLocation,Block> blocks=new HashMap<>();
        expected.keySet().forEach(id->blocks.put(new ResourceLocation(id),new Plain().setLightLevel(0.6f)));
        Map<IBlockState,Integer> output=new IdentityHashMap<>();ModLightColors.registerDefaults(blocks::get,output::put);
        assertEquals(expected.size(),output.size());
        expected.forEach((id,hue)->{
            IBlockState state=blocks.get(new ResourceLocation(id)).getDefaultState();
            assertEquals(hue,output.get(state));assertEquals(9,state.getLightValue());
        });
    }
}
