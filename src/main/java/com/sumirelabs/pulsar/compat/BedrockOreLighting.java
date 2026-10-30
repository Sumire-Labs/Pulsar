package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;

/** Bedrock Ores' final block reads only the ore state held by its final tile. */
public final class BedrockOreLighting {
    private BedrockOreLighting() {}

    public static boolean notified(TileEntity tile, IBlockState host) {
        return tile instanceof BedrockOreLightTile notifier && notifier.pulsar$hasOreLightNotifications()
                && host.getBlock().getClass().getName().equals("li.cil.bedrockores.common.block.BlockBedrockOre")
                && (LightInfo.of(host) & LightInfo.CONTEXT_OPACITY) == 0;
    }

    public static void changed(TileEntity tile) {
        var world = tile.getWorld();
        if (world == null || world.isRemote || !(world instanceof PulsarWorld access)) return;
        var manager = access.pulsar$getLightManager();
        if (manager == null) return;
        var pos = tile.getPos();
        // Request a world-thread refresh, including dependent neighbours. The
        // normal completion/publish path schedules exactly the changed lanes.
        manager.contextualLight().request(pos.getX(), pos.getY(), pos.getZ());
        if (manager.rgbContextualLight() != null)
            manager.rgbContextualLight().request(pos.getX(), pos.getY(), pos.getZ());
    }
}
