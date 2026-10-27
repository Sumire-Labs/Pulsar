/* SPDX-License-Identifier: LGPL-3.0-only
 * Hues follow SuperNova BotaniaColors/TwilightForestColors/ThaumcraftColors,
 * commit 22d8559c3064d77d115e58523c58cde824f4e214.
 * Names/properties verified against VazkiiMods/Botania 1.12-final, commit
 * 8a56a4a60c97be368b45c97da9d9da0ab6490962, and TeamTwilight/twilightforest
 * 1.12.x, commit 87574e3df3b5e0ccf9e0b0d414e8a231a6bdb0e4. No mod classes are loaded.
 * TC6 names verified from ConfigBlocks in 6.1.BETA26 (CurseForge file 2629023).
 */
package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.api.ColoredLightSource;
import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Conservative, optional 1.12.2 presets; keep contextual brightness and API overrides. */
public final class ModLightColors {
    private ModLightColors() {}

    public static void registerDefaults(BiConsumer<IBlockState, Integer> register) {
        registerDefaults(id -> Block.REGISTRY.containsKey(id) ? Block.REGISTRY.getObject(id) : null, register);
    }

    static void registerDefaults(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> register) {
        for(String name:new String[]{"shinyFlower","miniIsland","buriedPetals","mushroom"})
            register(lookup,register,name,state->{
                EnumDyeColor dye=dye(state);
                return dye==null?null:RgbDefaultPalette.glassTransmittance(dye);
            });
        for(String name:new String[]{"manaFlame","manaGlass","manaGlassPane","elfGlass","elfGlassPane",
                "enchanter","bifrost","bifrostPerm","bifrostPermPane","prism"})
            register(lookup,register,name,state->0xFFF);
        register(lookup,register,"livingwood",state->{
            for(var entry:state.getProperties().entrySet())
                if(entry.getKey().getName().equals("variant")&&entry.getValue() instanceof IStringSerializable variant
                        &&variant.getName().equals("glimmering"))return 0x480;
            return null;
        });
        register(lookup, register, "blazeBlock", state -> 0xFF0);
        register(lookup, register, "pylon", state -> {
            for (var entry : state.getProperties().entrySet()) {
                if (entry.getKey().getName().equals("variant") && entry.getValue() instanceof IStringSerializable variant)
                    return switch (variant.getName()) {
                        case "mana" -> 0x08F;
                        case "natura" -> 0x0F0;
                        case "gaia" -> 0xFAD;
                        default -> null;
                    };
            }
            return null;
        });
        register(lookup,register,"twilightforest:firefly",state->0xFCA);
        register(lookup,register,"twilightforest:firefly_jar",state->0xFCA);
        register(lookup,register,"twilightforest:twilight_portal",state->0xA0F);
        register(lookup,register,"twilightforest:cinder_furnace_lit",state->0xDCA);
        register(lookup,register,"twilightforest:trollber",state->0xFEC);
        register(lookup,register,"twilightforest:huge_mushgloom",state->0x420);
        register(lookup,register,"twilightforest:force_field",state->{
            EnumDyeColor dye=dye(state);
            if(dye==null)return null;
            return switch(dye){
                case PURPLE -> 0x201;
                case PINK -> 0x212;
                case ORANGE -> 0x210;
                case GREEN -> 0x020;
                case BLUE -> 0x012;
                default -> null;
            };
        });
        String[] crystals={"aer","ignis","aqua","terra","ordo","perditio"};
        int[] crystalHues={0x770,0x700,0x007,0x070,0x777,0x444};
        for(int i=0;i<crystals.length;i++) {
            int hue=crystalHues[i];register(lookup,register,"thaumcraft:crystal_"+crystals[i],state->hue);
        }
        int[] candleHues={0xEEE,0xE84,0xB5C,0x68C,0xDC2,0x4C3,0xD89,0x444,
                0x999,0x279,0x73B,0x239,0x532,0x352,0xB33,0x222};
        for(EnumDyeColor color:EnumDyeColor.values()) {
            // TC6 lowercases translation keys: lightBlue -> lightblue, not light_blue.
            String suffix=color.getName().replace("_","");
            int candle=candleHues[color.getMetadata()];
            register(lookup,register,"thaumcraft:candle_"+suffix,state->candle);
            register(lookup,register,"thaumcraft:nitor_"+suffix,state->RgbDefaultPalette.glassTransmittance(color));
        }
    }

    private static EnumDyeColor dye(IBlockState state) {
        for(var entry:state.getProperties().entrySet())
            if(entry.getKey().getName().equals("color")&&entry.getValue() instanceof EnumDyeColor color)return color;
        return null;
    }

    private static void register(Function<ResourceLocation, Block> lookup, BiConsumer<IBlockState, Integer> register,
                                 String name, Function<IBlockState, Integer> color) {
        Block block = lookup.apply(new ResourceLocation(name.indexOf(':')<0?"botania:"+name:name));
        // An integration's own static/positional API always wins over our presets.
        if (block == null || block instanceof ColoredLightSource) return;
        for (IBlockState state : block.getBlockState().getValidStates()) {
            Integer value = color.apply(state);
            if (value != null) register.accept(state, value);
        }
    }
}
