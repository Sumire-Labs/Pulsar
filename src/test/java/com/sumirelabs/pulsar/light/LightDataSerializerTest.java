package com.sumirelabs.pulsar.light;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LightDataSerializerTest {
    private static NBTTagCompound saved(final boolean projected) {
        var root = new NBTTagCompound();
        root.setInteger("version", LightDataSerializer.LIGHT_VERSION);
        root.setBoolean("rgbMaxGameplayLight", projected);
        return root;
    }

    @Test void switchingFromRgbMaxToScalarRequiresRelighting() {
        var root = saved(true);
        assertTrue(LightDataSerializer.hasCompatibleGameplayLight(root, true));
        assertFalse(LightDataSerializer.hasCompatibleGameplayLight(root, false));
    }

    @Test void vanillaGameplayLightCannotInitializeRgbMax() {
        var root = saved(false);
        assertTrue(LightDataSerializer.hasCompatibleGameplayLight(root, false));
        assertFalse(LightDataSerializer.hasCompatibleGameplayLight(root, true));
    }

    @Test void legacyAndUnmarkedCachesCannotBypassThePolicyCheck() {
        var root = saved(true);
        root.setInteger("version", 13);
        assertFalse(LightDataSerializer.hasCompatibleGameplayLight(root, true));
        assertFalse(LightDataSerializer.hasCompatibleGameplayLight(root, false));
        root.setInteger("version", LightDataSerializer.LIGHT_VERSION);
        root.removeTag("rgbMaxGameplayLight");
        assertFalse(LightDataSerializer.hasCompatibleGameplayLight(root, false));
        root.setString("rgbMaxGameplayLight", "false");
        assertFalse(LightDataSerializer.hasCompatibleGameplayLight(root, false));
    }
}
