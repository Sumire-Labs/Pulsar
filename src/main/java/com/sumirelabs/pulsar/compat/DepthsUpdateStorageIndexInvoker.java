package com.sumirelabs.pulsar.compat;

import java.lang.reflect.Method;

/**
 * Resolves the storage-index API across published Depths Update versions.
 *
 * <p>The a12 jar exposes section mapping on {@code HeightContext}, while newer
 * source revisions also provide a world-aware forwarding method on
 * {@code HeightManager}. This class deliberately has no Minecraft dependency
 * so both API shapes can be covered by ordinary unit tests.
 */
final class DepthsUpdateStorageIndexInvoker {

    private final Method method;
    private final boolean invokeOnContext;
    private final String description;

    private DepthsUpdateStorageIndexInvoker(final Method method, final boolean invokeOnContext,
                                            final String description) {
        this.method = method;
        this.invokeOnContext = invokeOnContext;
        this.description = description;
    }

    static DepthsUpdateStorageIndexInvoker resolve(final Class<?> heightManagerClass,
                                                   final Class<?> heightContextClass,
                                                   final Class<?> worldClass)
            throws NoSuchMethodException {
        try {
            return new DepthsUpdateStorageIndexInvoker(
                    heightManagerClass.getMethod("toStorageIndex", worldClass, int.class),
                    false, "HeightManager#toStorageIndex(World,int)");
        } catch (final NoSuchMethodException missingWorldAwareMethod) {
            return new DepthsUpdateStorageIndexInvoker(
                    heightContextClass.getMethod("toStorageIndex", int.class),
                    true, "HeightContext#toStorageIndex(int)");
        }
    }

    int invoke(final Object heightContext, final Object world, final int blockY)
            throws ReflectiveOperationException {
        final Object receiver = this.invokeOnContext ? heightContext : null;
        final Object result = this.invokeOnContext
                ? this.method.invoke(receiver, blockY)
                : this.method.invoke(receiver, world, blockY);
        return (Integer) result;
    }

    String getDescription() {
        return this.description;
    }
}
