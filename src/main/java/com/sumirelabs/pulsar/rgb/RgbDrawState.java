package com.sumirelabs.pulsar.rgb;

import java.util.Arrays;

/** Render-thread metadata; nested frames reuse arrays and never retain closed owners. */
public final class RgbDrawState {
    private int[] colors = new int[16], flags = new int[16];
    private float[] coordinates = new float[32];
    private Object[] owners = new Object[16];
    private int depth, worldDepth, suppression, drawSuppression;
    private Object owner;
    private int tint = 0xFFFFFFFF;
    private boolean uniform;
    boolean lightmap, fullBright, poppedSession;
    float lightU, lightV;

    public int depth() { return depth; }
    public boolean active() { return depth != 0; }
    public boolean worldPass() { return worldDepth != 0; }
    public int enterWorld() { return ++worldDepth; }
    public void leaveWorld(int token) { worldDepth = token - 1; }
    public int tint() { return tint; }
    public boolean uniform() { return active() && uniform && lightmap && !fullBright && drawSuppression == 0 && (tint & 0xFFFFFF) != 0xFFFFFF; }
    public int push(Object nextOwner, int nextTint, boolean nextUniform, boolean ownsSession) {
        if (depth != 0 && owner == nextOwner) return 0;
        if (depth == flags.length) {
            flags = Arrays.copyOf(flags, flags.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
            owners = Arrays.copyOf(owners, owners.length * 2);
            coordinates = Arrays.copyOf(coordinates, coordinates.length * 2);
        }
        colors[depth] = tint;
        flags[depth] = (lightmap ? 1 : 0) | (fullBright ? 2 : 0) | (uniform ? 4 : 0) | (ownsSession ? 8 : 0);
        coordinates[depth * 2] = lightU; coordinates[depth * 2 + 1] = lightV;
        owners[depth] = owner;
        owner = nextOwner; tint = nextTint; uniform = nextUniform;
        return ++depth;
    }
    public void pop(int token) {
        if (token == 0) return;
        if (token != depth) throw new IllegalStateException("RGB draw scopes must close in reverse order");
        int slot = --depth;
        tint = colors[slot];
        lightU = coordinates[slot * 2]; lightV = coordinates[slot * 2 + 1];
        lightmap = (flags[slot] & 1) != 0; fullBright = (flags[slot] & 2) != 0;
        uniform = (flags[slot] & 4) != 0; poppedSession = (flags[slot] & 8) != 0;
        owner = owners[slot]; owners[slot] = null;
    }
    public void lightCoordinates(float u, float v) { lightU = u; lightV = v; fullBright = u >= 240 && v >= 240; }
    public int suppressVertices() { return active() ? ++suppression : 0; }
    public void restoreVertices(int token) { if (token != 0) suppression = token - 1; }
    public int suppressDraw() { return ++drawSuppression; }
    public void restoreDraw(int token) { drawSuppression = token - 1; }
    public boolean tintsVertices(boolean vertexLightmap,boolean vertexFullBright) {
        return active() && lightmap && suppression==0 && !(vertexLightmap?vertexFullBright:fullBright);
    }
    public float factor(int channel) { return (tint >>> (channel*8) & 255)/255f; }
    public int tintVertex(int color, boolean vertexLightmap, boolean vertexFullBright) {
        if (!tintsVertices(vertexLightmap,vertexFullBright)) return color;
        int r = Math.round((color & 255) * ((tint & 255) / 255f));
        int g = Math.round((color >>> 8 & 255) * ((tint >>> 8 & 255) / 255f));
        int b = Math.round((color >>> 16 & 255) * ((tint >>> 16 & 255) / 255f));
        return color & 0xFF000000 | r | g << 8 | b << 16;
    }
}
