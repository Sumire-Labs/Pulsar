package com.sumirelabs.pulsar.light.engine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Classifies only the requested methods. Class.getMethod may resolve every
 * declared signature and fail on an unrelated, stripped client-only type.
 */
final class ContextMethodOverrides {

    private ContextMethodOverrides() {
    }

    static int detect(final Class<?> implementationClass, final Class<?> baseClass,
                      final String firstMethod, final int firstFlag,
                      final String secondMethod, final int secondFlag,
                      final Class<?>... parameterTypes) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType signature = MethodType.methodType(int.class, parameterTypes);
            try { lookup.findVirtual(implementationClass, firstMethod, signature); }
            catch (IllegalAccessException inaccessibleClass) {
                lookup = MethodHandles.privateLookupIn(implementationClass, MethodHandles.lookup());
            }
            int flags = 0;
            if (lookup.revealDirect(lookup.findVirtual(implementationClass, firstMethod, signature)).getDeclaringClass() != baseClass) {
                flags |= firstFlag;
            }
            if (lookup.revealDirect(lookup.findVirtual(implementationClass, secondMethod, signature)).getDeclaringClass() != baseClass) {
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
