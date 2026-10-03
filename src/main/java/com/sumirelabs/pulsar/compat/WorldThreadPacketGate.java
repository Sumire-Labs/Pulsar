package com.sumirelabs.pulsar.compat;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Transfers a world-reading packet body without blocking its network thread. */
public final class WorldThreadPacketGate {
    private WorldThreadPacketGate() {}

    /** True means the caller must cancel the immediate packet body. */
    public static boolean defer(final BooleanSupplier isWorldThread,
                                final Consumer<Runnable> schedule,
                                final BooleanSupplier isActive,
                                final Runnable receive) {
        if (isWorldThread.getAsBoolean()) return false;
        schedule.accept(() -> {
            if (isActive.getAsBoolean()) receive.run();
        });
        return true;
    }
}
