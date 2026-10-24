package com.sumirelabs.pulsar.rgb;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.renderer.GlStateManager;

/** Reads only the reserved unit's boolean cache; no Minecraft field names are assumed. */
final class RgbTextureCache {
    private static final Field TEXTURES, ENABLE, VALUE;
    static {
        try {
            Field array = null, enabled = null, value = null;
            for (Field f : GlStateManager.class.getDeclaredFields())
                if (Modifier.isStatic(f.getModifiers()) && f.getType().isArray()
                        && f.getType().getComponentType().getName().endsWith("GlStateManager$TextureState")) array = f;
            if (array == null) throw new IllegalStateException("Texture state array unavailable");
            for (Field f : array.getType().getComponentType().getDeclaredFields())
                if (f.getType().getName().endsWith("GlStateManager$BooleanState")) enabled = f;
            if (enabled == null) throw new IllegalStateException("Texture enable cache unavailable");
            for (Field f : enabled.getType().getDeclaredFields()) if (f.getType() == boolean.class) value = f;
            if (value == null) throw new IllegalStateException("Boolean cache unavailable");
            array.setAccessible(true); enabled.setAccessible(true); value.setAccessible(true);
            TEXTURES = array; ENABLE = enabled; VALUE = value;
        } catch (RuntimeException e) { throw new ExceptionInInitializerError(e); }
    }
    static boolean get(int index) {
        try { return VALUE.getBoolean(ENABLE.get(((Object[])TEXTURES.get(null))[index])); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
