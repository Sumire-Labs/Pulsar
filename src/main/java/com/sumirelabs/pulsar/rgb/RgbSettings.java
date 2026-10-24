package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.core.RgbMixinSelection;

/** Restart-only server policy, captured after configuration is loaded and before worlds exist. */
public final class RgbSettings {
    public enum GameplayLight { VANILLA, RGB_MAX }
    private static GameplayLight gameplay = GameplayLight.VANILLA;
    private RgbSettings() {}
    public static void initialize() { gameplay = PulsarConfig.rgbGameplayLight; }
    public static boolean projectsGameplayLight() {
        return RgbMixinSelection.rgbEnabled() && gameplay == GameplayLight.RGB_MAX;
    }
    public static String profile() { return gameplay.name(); }
}
