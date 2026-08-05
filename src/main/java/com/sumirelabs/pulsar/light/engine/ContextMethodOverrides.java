package com.sumirelabs.pulsar.light.engine;

/**
 * Minecraft-independent reflection helper for classifying context-method
 * overrides once per implementation class.
 */
final class ContextMethodOverrides {

    private ContextMethodOverrides() {
    }

    static int detect(final Class<?> implementationClass, final Class<?> baseClass,
                      final String firstMethod, final int firstFlag,
                      final String secondMethod, final int secondFlag,
                      final Class<?>... parameterTypes) {
        try {
            int flags = 0;
            if (implementationClass.getMethod(firstMethod, parameterTypes).getDeclaringClass() != baseClass) {
                flags |= firstFlag;
            }
            if (implementationClass.getMethod(secondMethod, parameterTypes).getDeclaringClass() != baseClass) {
                flags |= secondFlag;
            }
            return flags;
        } catch (final ReflectiveOperationException | SecurityException | LinkageError ignored) {
            // A failed classification must prefer correct contextual values
            // over accidentally caching a dynamic result.
            return firstFlag | secondFlag;
        }
    }
}
