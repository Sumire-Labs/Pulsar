package com.sumirelabs.pulsar.compat;

import mrtjp.projectred.illumination.BaseLightPart;
import mrtjp.projectred.illumination.LightButtonPart;
import mrtjp.projectred.illumination.FLightButtonPart;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.EnumDyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectRedPartColorsTest {
    @BeforeAll static void bootstrap(){Bootstrap.register();}
    @Test void realFixturePreservesPowerInversionAndDye() {
        var lamp=new BaseLightPart(null);
        lamp.setShape(EnumDyeColor.RED.getMetadata(),0,false);
        lamp.powered_$eq(true);
        assertEquals(0xF00,MultipartLightBridge.colors(List.of(lamp)));
        lamp.powered_$eq(false);
        assertEquals(0,MultipartLightBridge.colors(List.of(lamp)));
        lamp.setShape(EnumDyeColor.BLUE.getMetadata(),0,true);
        assertEquals(0x00F,MultipartLightBridge.colors(List.of(lamp)));
        assertTrue(MultipartLightBridge.needsPolling(List.of(lamp)));
    }
    @Test void realButtonsKeepTheirFiveLevelEmission() {
        var button=new LightButtonPart();button.colorMeta_$eq((byte)EnumDyeColor.RED.getMetadata());
        button.inverted_$eq(true); // Unpressed inverted button emits.
        assertEquals(0x500,MultipartLightBridge.colors(List.of(button)));
        button.inverted_$eq(false);assertEquals(0,MultipartLightBridge.colors(List.of(button)));
        var feedback=new FLightButtonPart();feedback.colorMeta_$eq((byte)EnumDyeColor.BLUE.getMetadata());
        feedback.powered_$eq(true);
        assertEquals(0x005,MultipartLightBridge.colors(List.of(feedback)));
        feedback.powered_$eq(false);assertEquals(0,MultipartLightBridge.colors(List.of(feedback)));
    }
}
