package com.sumirelabs.pulsar.command;

import com.sumirelabs.pulsar.light.ChunkLightHelper;
import com.sumirelabs.pulsar.light.PulsarChunk;
import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.util.WorldUtil;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Client-side diagnosis command for the thin-client light pipeline:
 * {@code /pulsarc} samples the player's surroundings and prints, side by
 * side, the CLIENT's vanilla nibble values, the client SWMR visible
 * values/states and — in singleplayer — the integrated SERVER's values for
 * the same positions. One glance shows which layer diverges (server engine,
 * packet snapshot, client engine, or renderer).
 *
 * <p>The server world is read from the client thread without
 * synchronisation — fine for a debug readout, values may be mid-update.
 */
public class CommandPulsarClient extends CommandBase {

    @Override
    public String getName() {
        return "pulsarc";
    }

    @Override
    public String getUsage(final ICommandSender sender) {
        return "/pulsarc";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(final MinecraftServer server, final ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(final MinecraftServer server, final ICommandSender sender, final String[] args) {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityPlayer player = mc.player;
        if (player == null || mc.world == null) {
            return;
        }
        final BlockPos feet = new BlockPos(player);
        final BlockPos[] samples = {feet.down(), feet, feet.up(), feet.north(), feet.south(), feet.west(), feet.east()};
        final String[] labels = {"below", "feet ", "above", "north", "south", "west ", "east "};

        final MinecraftServer integrated = mc.getIntegratedServer();
        final World serverWorld = integrated != null ? integrated.getWorld(player.dimension) : null;

        sender.sendMessage(new TextComponentString(
                "§epulsarc §7— van=vanilla sky/blk, swmr=engine sky(state)/blk(state)"));
        for (int i = 0; i < samples.length; i++) {
            final BlockPos pos = samples[i];
            final String client = describe(mc.world, pos);
            final String serverStr = serverWorld != null ? describe(serverWorld, pos) : "n/a (not singleplayer)";
            sender.sendMessage(new TextComponentString(
                    "§7" + labels[i] + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                            + "§r §bC[" + client + "]§r §6S[" + serverStr + "]"));
        }
    }

    private static String describe(final World world, final BlockPos pos) {
        final WorldLightManager mgr = ((PulsarWorld) world).pulsar$getLightManager();
        final Chunk chunk = mgr != null ? mgr.getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4) : null;
        if (chunk == null) {
            return "chunk not registered";
        }
        final ExtendedBlockStorage section = pos.getY() >= 0 && pos.getY() < 256
                ? chunk.getBlockStorageArray()[pos.getY() >> 4] : null;
        final String vanSky;
        final String vanBlock;
        if (section == null) {
            vanSky = "noEBS";
            vanBlock = "noEBS";
        } else {
            final NibbleArray skyArr = section.getSkyLight();
            final NibbleArray blockArr = section.getBlockLight();
            vanSky = skyArr != null
                    ? String.valueOf(skyArr.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)) : "-";
            vanBlock = blockArr != null
                    ? String.valueOf(blockArr.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)) : "-";
        }

        final PulsarChunk pc = (PulsarChunk) chunk;
        final SWMRNibbleArray[] sky = pc.pulsar$getSkyNibbles();
        final SWMRNibbleArray[] block = pc.pulsar$getBlockNibbles();
        final int idx = (pos.getY() >> 4) - WorldUtil.getMinLightSection();
        final int swmrSky = sky != null
                ? ChunkLightHelper.getSkyLight(sky, pos.getX(), pos.getY(), pos.getZ()) : -1;
        final int swmrBlock = block != null
                ? ChunkLightHelper.getBlockLight(block, pos.getX(), pos.getY(), pos.getZ()) : -1;
        final String skyState = sky != null && idx >= 0 && idx < sky.length ? state(sky[idx]) : "?";
        final String blockState = block != null && idx >= 0 && idx < block.length ? state(block[idx]) : "?";

        return "van " + vanSky + "/" + vanBlock
                + " swmr " + swmrSky + "(" + skyState + ")/" + swmrBlock + "(" + blockState + ")"
                + (pc.pulsar$isLightReady() ? "" : " §cNOT-READY§r");
    }

    private static String state(final SWMRNibbleArray nib) {
        if (nib == null) return "null";
        if (nib.isNullNibbleVisible()) return "NULL";
        if (nib.isUninitialisedVisible()) return "UNINIT";
        if (nib.isHiddenVisible()) return "HIDDEN";
        return "INIT";
    }
}
