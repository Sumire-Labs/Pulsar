/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.world.PulsarWorld;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.Loader;

/** Implemented on Renovated tiles that invalidate their cached opacity. */
public interface ArchitectureShapeLight {

    boolean pulsar$subscribeToOpacityChanges();

    static boolean subscribe(TileEntity tile, IBlockState state) {
        return tile instanceof ArchitectureShapeLight notifier && !FluidLightBridge.LOADED
            && tile.getClass().getName().equals("com.elytradev.architecture.common.tile.TileShape")
            && state.getBlock().getClass().getName().equals("com.elytradev.architecture.common.block.BlockShape")
            && Versions.SUPPORTED
            && (LightInfo.of(state) & LightInfo.CONTEXT_MASK) == LightInfo.CONTEXT_OPACITY
            && notifier.pulsar$subscribeToOpacityChanges();
    }

    static void invalidated(TileEntity tile) {
        var world = tile.getWorld();
        if (world == null || world.isRemote || !(world instanceof PulsarWorld access))
            return;
        var manager = access.pulsar$getLightManager();
        if (manager != null) {
            var pos = tile.getPos();
            manager.contextualLight().request(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    final class Versions {

        private Versions() {}
        // In these versions metadata supplies emission; all runtime opacity cache resets
        // are in the three methods hooked by MixinArchitectureShapeLight.
        static final boolean SUPPORTED = supportedVersion();
        private static boolean supportedVersion() {
            var mod = Loader.instance().getIndexedModList().get("architecturecraft");
            return mod != null && switch (mod.getVersion()) {
                case "3.108-0.8", "3.108-0.9.0pre1", "3.108-0.9.0" -> true;
                default -> false;
            };
        }

    }

}
