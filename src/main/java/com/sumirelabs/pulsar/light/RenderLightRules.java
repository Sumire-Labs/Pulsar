package com.sumirelabs.pulsar.light;

/** Shared thresholds for vanilla smooth-lighting decisions. */
public final class RenderLightRules {

    private RenderLightRules() {
    }

    /**
     * Light level one is treated as non-emissive for ambient occlusion.
     * Stronger emitters remain exempt so they do not cast AO on themselves.
     */
    public static int ambientOcclusionEmission(final int lightValue) {
        return Math.max(Math.min(lightValue - 1, 15), 0);
    }
}
