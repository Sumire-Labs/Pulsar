package com.sumirelabs.pulsar.rgb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;

/** Client-only scoped bridge; material colors are never rewritten. */
public final class RgbDrawContext {
    private static final class Context {
        final RgbDrawState state = new RgbDrawState();
        RgbTextureTint texture;
        RgbTextureTint texture() { if (texture == null) texture = new RgbTextureTint(); return texture; }
    }
    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);
    private static final Object UNLIT = new Object();
    private static int textureUnits=-1;
    public static boolean supported(){
        if (RgbShaderRuntime.present()) return true;
        if(textureUnits<0){textureUnits=GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_MAX_TEXTURE_UNITS);
            if(textureUnits<4)com.sumirelabs.pulsar.Pulsar.LOGGER.warn("RGB object rendering needs four fixed-function texture units; using scalar client lighting on this GPU.");}
        return textureUnits>=4;
    }
    private RgbDrawContext() {}
    public static RgbDrawState state() { return CONTEXT.get().state; }
    public static boolean available() { return (RgbClient.active() || RgbClient.skyActive()) && Minecraft.getMinecraft().world != null; }
    public static void openSession() { CONTEXT.get().texture().open(); }
    public static void closeSession() { CONTEXT.get().texture().close(); }
    public static int enter(Object owner,double x,double y,double z) { return enter(owner,x,y,z,true); }
    public static int enterVertices(Object owner,double x,double y,double z) { return enter(owner,x,y,z,false); }
    private static int enter(Object owner,double x,double y,double z,boolean uniform) {
        if (RgbShaderRuntime.active()) return 0;
        if (!available()) return 0;
        return enterTint(owner,RgbClient.tintAbgr(0xFFFFFFFF,x,y,z),uniform);
    }
    public static int enterUnlit() { return state().active() ? enterTint(UNLIT,0xFFFFFFFF,true) : 0; }
    private static int enterTint(Object owner,int tint,boolean uniform) {
        Context context=CONTEXT.get();var texture=context.texture();
        boolean owns=!texture.inSession();if(owns)texture.open();
        int token=context.state.push(owner,tint,uniform,owns);
        if(token==0){if(owns)texture.close();return 0;}
        try { texture.update(context.state); }
        catch(Throwable failure){close(token);throw failure;}
        return token;
    }
    public static void close(int token) {
        if(token==0)return;
        Context context=CONTEXT.get();var state=context.state;
        boolean oldLightmap=state.lightmap;float oldU=state.lightU,oldV=state.lightV;
        state.pop(token);boolean owns=state.poppedSession;
        if(oldLightmap!=state.lightmap){if(state.lightmap)Minecraft.getMinecraft().entityRenderer.enableLightmap();else Minecraft.getMinecraft().entityRenderer.disableLightmap();}
        if(oldU!=state.lightU || oldV!=state.lightV)
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,state.lightU,state.lightV);
        context.texture().update(state);
        if(owns)context.texture().close();
    }
    public static void lightmap(boolean enabled) {
        Context context=CONTEXT.get();var state=context.state;
        if(state.lightmap==enabled)return;state.lightmap=enabled;
        if(context.texture!=null && context.texture.inSession())context.texture.update(state);
    }
    public static void coordinates(int unit,float u,float v) {
        if(unit!=OpenGlHelper.lightmapTexUnit)return;
        Context context=CONTEXT.get();var state=context.state;
        if(state.active() && RgbLegacyTexture.recording())return;
        boolean old=state.fullBright;
        state.lightCoordinates(u,v);
        if(old!=state.fullBright && context.texture!=null && context.texture.inSession())context.texture.update(state);
    }
    public static int suppressVertices(){return state().suppressVertices();}
    public static void restoreVertices(int token){state().restoreVertices(token);}
    public static int suppressDraw(){Context c=CONTEXT.get();int token=c.state.suppressDraw();if(c.texture!=null&&c.texture.inSession())c.texture.update(c.state);return token;}
    public static void restoreDraw(int token){Context c=CONTEXT.get();c.state.restoreDraw(token);if(c.texture!=null&&c.texture.inSession())c.texture.update(c.state);}
}
