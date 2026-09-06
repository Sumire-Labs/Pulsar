package com.sumirelabs.pulsar.light.engine;

/** Scalar medium rules shared by the packed lookup and sky-column paths. */
public final class LightAttenuation {

    private LightAttenuation() {
    }

    public static boolean usesAutomaticFaces(final boolean fullCube, final boolean liquid,
                                              final int opacity) {
        // An open liquid face does not make the medium inside the cell air.
        return !fullCube && !liquid && opacity > 0;
    }

    public static int absorption(final int opacity, final boolean sided, final boolean solidFace) {
        return sided && !solidFace ? 1 : Math.max(1, Math.min(15, opacity));
    }

    public static boolean canExtrudeDirectSky(final int level, final int aboveOpacity,
                                               final int destinationOpacity) {
        // Attenuated light belongs to BFS, whose ordinary edges always lose >= 1.
        // In particular, do not extrude a weakened value through a NULL section.
        return level == 15 && aboveOpacity == 0 && destinationOpacity == 0;
    }

    public static int skyAfterOpacity(final int level, final int opacity) {
        final int loss = level == 15 ? Math.max(0, opacity) : Math.max(1, opacity);
        return Math.max(0, level - loss);
    }
}
