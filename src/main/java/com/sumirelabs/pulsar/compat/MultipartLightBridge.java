package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.RgbStateProperties;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.lang.reflect.Method;
import java.util.List;

/** CBMultipart 1.12.2 bridge. Called only by world-thread samplers, never BFS workers.
 * Standard microblocks inherit the source material's palette; unknown parts retain white light.
 * No world/dimension side store is needed: the normal Pulsar snapshots own publication.
 */
public final class MultipartLightBridge {
    public static final boolean PRESENT = MultipartLightBridge.class.getClassLoader()
            .getResource("codechicken/multipart/BlockMultipart.class") != null;
    private static final String MICROBLOCK = "codechicken.microblock.Microblock";
    private static final String MATERIAL = "codechicken.microblock.BlockMicroMaterial";
    private record PartAccess(Method light, Method material, boolean standardLight) {}
    private static final ClassValue<PartAccess> PARTS = new ClassValue<>() {
        protected PartAccess computeValue(Class<?> type) {
            Method light = method(type, "getLightValue");
            Method material = inherits(type, MICROBLOCK) ? method(type, "getIMaterial") : null;
            return new PartAccess(light, material, light != null && material != null
                    && light.getDeclaringClass().getName().equals(MICROBLOCK)
                    && material.getDeclaringClass().getName().equals(MICROBLOCK));
        }
    };
    private static final ClassValue<java.util.Optional<Method>> STATES = new ClassValue<>() {
        protected java.util.Optional<Method> computeValue(Class<?> type) {
            return java.util.Optional.ofNullable(inherits(type, MATERIAL) ? method(type, "state") : null);
        }
    };
    private MultipartLightBridge() {}

    private static boolean inherits(Class<?> type, String name) {
        for (; type != null; type = type.getSuperclass()) if (type.getName().equals(name)) return true;
        return false;
    }
    private static Method method(Class<?> type, String name) {
        try { return type.getMethod(name); }
        catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) { return null; }
    }
    private static IBlockState state(Object material) throws ReflectiveOperationException {
        if (material == null) return null;
        Method accessor = STATES.get(material.getClass()).orElse(null);
        return accessor == null ? null : (IBlockState) accessor.invoke(material);
    }

    public static int emission(IBlockState host, IBlockAccess world, BlockPos pos) {
        var tile = world.getTileEntity(pos);
        if (tile instanceof MultipartLightTile multipart) {
            int result = colors(multipart.jPartList());
            if (result >= 0) return result;
        }
        return Math.max(0, Math.min(15, host.getLightValue(world, pos))) * 0x111;
    }

    /** -1 requests neutral host emission if an unfamiliar API cannot be read. */
    static int colors(List<?> parts) {
        int result = 0;
        try {
            for (Object part : parts) {
                PartAccess access = PARTS.get(part.getClass());
                if (access.light == null) return -1;
                int level = Math.max(0, Math.min(15, ((Number) access.light.invoke(part)).intValue()));
                if (level == 0) continue;
                Object materialObject = access.material == null ? null : access.material.invoke(part);
                int adapted = ProjectRedPartColors.color(part,level);
                if(adapted<0)adapted=ProjectRedPartColors.color(materialObject,level);
                if(adapted>=0){result=max(result,adapted);continue;}
                IBlockState material = state(materialObject);
                // A material is not a block placed at this position. Never invoke its
                // positional API against the host tile or substitute its static strength.
                int color = material == null || (RgbStateProperties.of(material) & RgbStateProperties.DYNAMIC_SOURCE) != 0
                        ? level * 0x111 : BlockLightColors.emission(material, level);
                result = max(result, color);
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return -1; }
    }
    private static int max(int a, int b) {
        return Math.max(a & 0xF00, b & 0xF00) | Math.max(a & 0xF0, b & 0xF0) | Math.max(a & 15, b & 15);
    }

    /** Only built-in, constant material emission is notification-only. Unknown/custom
     * parts, overridden getters and contextual material properties retain polling. */
    public static boolean needsPolling(List<?> parts) {
        try {
            for (Object part : parts) {
                PartAccess access = PARTS.get(part.getClass());
                if (!access.standardLight) return true;
                Object material = access.material.invoke(part);
                if (material == null || !material.getClass().getName().equals(MATERIAL)) return true;
                IBlockState state = state(material);
                if (state == null || state.getBlock() instanceof com.sumirelabs.pulsar.api.PositionalColoredLightSource
                        || state.getBlock() instanceof com.sumirelabs.pulsar.api.PositionalColoredTranslucency) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return true; }
    }

    public static boolean needsPolling(net.minecraft.tileentity.TileEntity tile) {
        return !(tile instanceof MultipartLightTile multipart) || multipart.pulsar$needsLightPolling();
    }
}
