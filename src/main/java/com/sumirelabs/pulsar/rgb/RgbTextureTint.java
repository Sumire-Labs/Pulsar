package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.mixin.MixinRgbActiveTexture;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import java.nio.FloatBuffer;

/** Scoped fixed-function multiplier. A 1D dummy leaves shader 2D bindings untouched. */
final class RgbTextureTint {
    private static final int[] ENV = {GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE_RGB,GL13.GL_SOURCE0_RGB,
            GL13.GL_SOURCE1_RGB,GL13.GL_OPERAND0_RGB,GL13.GL_OPERAND1_RGB,GL13.GL_COMBINE_ALPHA,
            GL13.GL_SOURCE0_ALPHA,GL13.GL_OPERAND0_ALPHA,GL13.GL_RGB_SCALE,GL11.GL_ALPHA_SCALE};
    private static final int[] TARGETS = {GL11.GL_TEXTURE_1D,GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_3D,GL13.GL_TEXTURE_CUBE_MAP};
    private final int[] saved = new int[ENV.length];
    private final boolean[] enabled = new boolean[TARGETS.length];
    private final FloatBuffer oldColor = BufferUtils.createFloatBuffer(4), color = BufferUtils.createFloatBuffer(4);
    private int sessions, unitIndex=-1, texture, oldBinding, currentTint;
    private boolean configured, oldTextureCache;

    boolean inSession() { return sessions != 0; }
    int unit() { return unitIndex < 0 ? -1 : OpenGlHelper.defaultTexUnit + unitIndex; }
    void open() { ++sessions; }
    private static long active() {
        return (long)GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) << 32 | (MixinRgbActiveTexture.pulsar$activeTextureIndex() & 0xFFFFFFFFL);
    }
    private void select(long previous) {
        if ((int)previous == unitIndex) OpenGlHelper.setActiveTexture(unit());
        else GlStateManager.setActiveTexture(unit());
    }
    private static void restoreActive(long previous) {
        int cachedUnit = OpenGlHelper.defaultTexUnit + (int)previous;
        GlStateManager.setActiveTexture(cachedUnit);
        int actualUnit = (int)(previous >>> 32);
        if (actualUnit != cachedUnit) OpenGlHelper.setActiveTexture(actualUnit);
    }
    private void configure() {
        if (unitIndex < 0) {
            unitIndex = Math.min(32,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS))-1;
            if (unitIndex < 3) throw new IllegalStateException("RGB object lighting requires four fixed-function texture units");
        }
        for (int i=0;i<ENV.length;i++) saved[i]=GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV,ENV[i]);
        oldColor.clear(); GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_COLOR,oldColor);
        for (int i=0;i<TARGETS.length;i++) enabled[i]=GL11.glIsEnabled(TARGETS[i]);
        oldBinding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_1D);
        oldTextureCache=RgbTextureCache.get(unitIndex);
        if (texture==0) {
            texture=GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_1D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_1D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_1D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            var pixel=BufferUtils.createByteBuffer(4);pixel.putInt(-1).flip();
            GL11.glTexImage1D(GL11.GL_TEXTURE_1D,0,GL11.GL_RGBA8,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
        } else GL11.glBindTexture(GL11.GL_TEXTURE_1D,texture);
        for(int i=1;i<TARGETS.length;i++) GL11.glDisable(TARGETS[i]);
        GL11.glEnable(GL11.GL_TEXTURE_1D);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_RGB,GL11.GL_MODULATE);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_RGB,GL13.GL_PREVIOUS);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE1_RGB,GL13.GL_CONSTANT);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_RGB,GL11.GL_SRC_COLOR);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND1_RGB,GL11.GL_SRC_COLOR);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_ALPHA,GL11.GL_REPLACE);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_ALPHA,GL13.GL_PREVIOUS);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_ALPHA,GL11.GL_SRC_ALPHA);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_RGB_SCALE,1);
        GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_ALPHA_SCALE,1);
        configured=true;currentTint=0;
    }
    void update(RgbDrawState state) {
        int tint=state.uniform()?state.tint():0xFFFFFFFF;
        if (!configured && (tint & 0xFFFFFF)==0xFFFFFF) return;
        if (configured && tint==currentTint) return;
        if (GL11.glGetInteger(GL11.GL_LIST_INDEX)!=0) return;
        if (sessions==0) throw new IllegalStateException("RGB multiplier outside a draw session");
        long previous=active();
        try {
            if (!configured) {
                if (unitIndex<0) unitIndex=Math.min(32,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS))-1;
                if (unitIndex<3) throw new IllegalStateException("RGB object lighting requires four fixed-function texture units");
            }
            select(previous);
            if (!configured) configure();
            color.clear();color.put((tint&255)/255f).put((tint>>>8&255)/255f).put((tint>>>16&255)/255f).put(1).flip();
            GlStateManager.glTexEnv(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_COLOR,color);
            currentTint=tint;
        } finally { restoreActive(previous); }
    }
    void close() {
        if (sessions<=0) throw new IllegalStateException("Unbalanced RGB draw session");
        if (--sessions!=0 || !configured) return;
        long previous=active();
        try {
            select(previous);
            for(int i=0;i<ENV.length;i++) GlStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV,ENV[i],saved[i]);
            oldColor.position(0);oldColor.limit(4);GlStateManager.glTexEnv(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_COLOR,oldColor);
            GL11.glBindTexture(GL11.GL_TEXTURE_1D,oldBinding);
            boolean finalCache=RgbTextureCache.get(unitIndex);
            for(int i=0;i<TARGETS.length;i++) {
                boolean value=i==1 && finalCache!=oldTextureCache?finalCache:enabled[i];
                if(value)GL11.glEnable(TARGETS[i]);else GL11.glDisable(TARGETS[i]);
            }
        } finally { configured=false;restoreActive(previous); }
    }
}
