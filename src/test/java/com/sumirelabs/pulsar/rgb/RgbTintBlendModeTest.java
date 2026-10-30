package com.sumirelabs.pulsar.rgb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbTintBlendModeTest {
    @Test void absorbedPrimariesStayAbsentAcrossLightLevelsAndMaterialColors() {
        for (var mode : RgbTintBlendMode.values()) {
            if (mode == RgbTintBlendMode.LEGACY) continue;
            for (int level = 1; level <= 15; level++) for (int lane = 0; lane < 3; lane++) {
                int[] rgb = new int[3]; rgb[lane] = level;
                int actual = mode.tintAbgr(0x7FABCDEF, rgb[0], rgb[1], rgb[2], level, 0, 0, 0, 0, 0);
                assertEquals(0x7F, actual >>> 24);
                for (int c = 0; c < 3; c++) if (c != lane)
                    assertEquals(0, actual >>> (8*c) & 255, mode.name()+" level="+level);
            }
        }
    }

    @Test void oklabDoesNotIntroduceOtherHuesIntoAnIsolatedPrimary() {
        for(int channel=0;channel<3;channel++) {
            float[] source=new float[3],actual=new float[3];source[channel]=15;
            RgbTintBlendMode.OKLAB.computeTint(source[0],source[1],source[2],0,0,0,actual);
            for(int c=0;c<3;c++)assertEquals(c==channel?1f:0f,actual[c],.00001f);
        }
    }
    @Test void dimFilteredLightNeverReintroducesAbsorbedChannels() {
        for(var mode:RgbTintBlendMode.values()) {
            if(mode==RgbTintBlendMode.LEGACY)continue;
            float[] tint=new float[3];
            mode.computeTint(7,0,0,0,0,0,tint);
            assertTrue(tint[0]>.99f,mode.name());
            assertEquals(0f,tint[1],.00001f,mode.name());
            assertEquals(0f,tint[2],.00001f,mode.name());
            mode.computeTint(12,0,0,0,0,0,tint);
            assertTrue(tint[1]<.25f && tint[2]<.25f,mode.name()+java.util.Arrays.toString(tint));
            int dark=mode.tintAbgr(-1,0,0,0,15,0,0,0,0,0);
            assertTrue((dark&255)<16,mode.name());
        }
        float previous=0;
        for(int step=0;step<=150;step++) {
            float weight=RgbTintBlendMode.hueConfidence(step/10f);
            assertTrue(weight>=previous && weight<=1);previous=weight;
        }
    }
    @Test void maximumKeepsDaylightNeutralAndRevealsColorAtNight() {
        var mode=RgbTintBlendMode.MAXIMUM;
        assertEquals(0x7F123456,mode.tintAbgr(0x7F123456,15,2,1,15,15,15,15,15,1));
        int night=mode.tintAbgr(-1,15,0,0,15,15,15,15,15,0);
        assertEquals(255,night&255);assertEquals(0,night>>>8&255);assertEquals(0,night>>>16&255);
        float[] tint=new float[3];
        mode.computeTint(15,0,0,0,0,7.5f,tint);
        assertArrayEquals(new float[]{1,0,.5f},tint,.00001f);
    }
    @Test void projectedMixedHuesReceiveOnlyTheReferenceTintNotAnotherBrightnessPenalty() {
        float[] tint=new float[3];
        for(var mode:RgbTintBlendMode.values()) {
            if(mode==RgbTintBlendMode.LEGACY)continue;
            for(int block=0;block<16;block++)for(int sky=0;sky<16;sky++)for(float scale:new float[]{0,.5f,1}) {
                // RGB_MAX canonical values already equal each lane's maximum.
                mode.computeTint(block,0,0,0,0,Math.max(0, sky-(15-15*scale)),tint);
                int actual=mode.tintAbgr(-1,block,0,0,block,0,0,sky,sky,scale);
                for(int c=0;c<3;c++)assertEquals(Math.round(tint[c]*255),actual>>>(c*8)&255,mode.name());
            }
        }
    }

    @Test void filteredSkyBelowNightDarkeningDoesNotRemainColored() {
        for(var mode:RgbTintBlendMode.values()) {
            if(mode==RgbTintBlendMode.LEGACY)continue;
            assertEquals(0x7F123456,mode.tintAbgr(0x7F123456,0,0,0,0,6,0,0,6,4/15f),mode.name());
            int filtered=mode.tintAbgr(-1,0,0,0,0,6,0,0,15,4/15f);
            assertEquals(filtered&255,filtered>>>8&255,mode.name());
            assertEquals(filtered&255,filtered>>>16&255,mode.name());
            assertTrue((filtered&255)<64,mode.name());
        }
    }

    @Test void weightedModesHaveDistinctKnownMixedLightResults() {
        float[] tint = new float[3];
        RgbTintBlendMode.ADDITIVE.computeTint(15, 0, 0, 0, 0, 5, tint);
        assertArrayEquals(new float[]{1, 0, 1f / 3}, tint, .0001f);
        RgbTintBlendMode.LINEAR_WEIGHT.computeTint(15, 0, 0, 0, 0, 5, tint);
        assertArrayEquals(new float[]{.75f, 0, .25f}, tint, .0001f);
        RgbTintBlendMode.SQUARED_WEIGHT.computeTint(15, 0, 0, 0, 0, 5, tint);
        assertArrayEquals(new float[]{.9f, 0, .1f}, tint, .0001f);
    }

    @Test void everyModePreservesMaterialAlphaAndNeutralWhiteLight() {
        for (RgbTintBlendMode mode : RgbTintBlendMode.values()) {
            assertEquals(0x7F123456, mode.tintAbgr(0x7F123456, 15, 15, 15, 15, 0, 0, 0, 0, 1), mode.name());
            assertEquals(0x7F, mode.tintAbgr(0x7F123456, 15, 0, 0, 15, 0, 0, 5, 5, 1) >>> 24, mode.name());
        }
    }

    @Test void canonicalGameplayLightCannotTurnFullyAbsorbedLightWhite() {
        for (RgbTintBlendMode mode : RgbTintBlendMode.values()) {
            int dark = mode.tintAbgr(-1, 0, 0, 0, 15, 0, 0, 0, 0, 1);
            assertTrue((dark & 255) < 16, mode.name());
            assertEquals(dark & 255, dark >>> 8 & 255, mode.name());
            assertEquals(dark & 255, dark >>> 16 & 255, mode.name());
            assertEquals(-1, mode.tintAbgr(-1, 0, 0, 0, 0, 0, 0, 0, 0, 1), mode.name());
        }
    }

    @Test void legacyRemainsBitExactAgainstPrototypeAcrossLightCombinations() {
        for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int sky = 0; sky < 16; sky++) {
            assertEquals(RgbClient.modulateSky(0x73124598, r, g, 7, 15, sky, 4, 9, 15, .6f),
                    RgbTintBlendMode.LEGACY.tintAbgr(0x73124598, r, g, 7, 15, sky, 4, 9, 15, .6f));
        }
    }

    @Test void allModesRemainFiniteAndBoundedForNibbleInputs() {
        float[] out = new float[3];
        for (RgbTintBlendMode mode : RgbTintBlendMode.values())
            for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int b = 0; b < 16; b++) {
                mode.computeTint(r, g, b, 7.25f, 0, 2.5f, out);
                for (float c : out) assertTrue(Float.isFinite(c) && c >= 0 && c <= 1.00001f, mode.name());
            }
    }
}
