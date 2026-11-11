package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.dynamic.DynamicLightClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.world.World;

/** One continuous light sample for an object's brightness, tint and all of its nested draw layers. */
public final class DynamicObjectLight {

    public static final class Sample {

        final Entity owner;
        final World world;
        final float[] light = new float[4];
        Sample(Entity owner) {
            this.owner = owner;
            this.world = owner.world;
        }
        public float red() {
            return light[0];
        }
        public float green() {
            return light[1];
        }
        public float blue() {
            return light[2];
        }
        public float brightness() {
            return DynamicLightClient.projected() ? Math.max(light[0], Math.max(light[1], light[2])) : light[3];
        }

    }
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    private static final Scope NOOP = new Scope(null, false);
    public static final class Scope implements AutoCloseable {

        private final Sample previous;
        private final boolean changed;
        private Scope(Sample previous, boolean changed) {
            this.previous = previous;
            this.changed = changed;
        }
        @Override
        public void close() {
            if (changed) {
                if (previous == null)
                    CURRENT.remove();
                else
                    CURRENT.set(previous);
            }
        }

    }
    private DynamicObjectLight() {}
    public static Sample current() {
        return DynamicLightClient.inMesh() ? null : CURRENT.get();
    }
    public static Scope begin(Entity entity, float partial) {
        // Paintings and other per-vertex renderers must retain their spatial sampling.
        if (!(entity instanceof EntityLivingBase) && !(entity instanceof EntityItem))
            return CURRENT.get() == null ? NOOP : suspend();
        if (entity.world == null || entity.world != Minecraft.getMinecraft().world
            || DynamicLightClient.mode() == com.sumirelabs.pulsar.dynamic.DynamicLights.Mode.OFF)
            return CURRENT.get() == null ? NOOP : suspend();
        Sample previous = CURRENT.get();
        if (previous != null && previous.owner == entity)
            return NOOP;
        Sample sample = new Sample(entity);
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial + entity.getEyeHeight();
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial;
        DynamicLightClient.sampleObject(entity.getEntityId(), x, y, z, partial, sample.light);
        CURRENT.set(sample);
        return new Scope(previous, true);
    }
    public static Scope suspend() {
        Sample previous = CURRENT.get();
        CURRENT.remove();
        return new Scope(previous, true);
    }
    public static Integer blockLight(World world) {
        Sample s = current();
        return s != null && s.world == world ? (int) s.brightness() : null;
    }
    public static float coordinate(int unit, float u, float v) {
        Sample sample = current();
        return sample == null || unit != OpenGlHelper.lightmapTexUnit ? u : Math.max(u, sample.brightness() * 16f);
    }

}
