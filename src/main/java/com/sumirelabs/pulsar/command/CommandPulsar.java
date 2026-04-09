package com.sumirelabs.pulsar.command;

import com.sumirelabs.pulsar.light.WorldLightManager;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code /pulsar} command. Provides relight + stats utilities for the
 * Pulsar lighting engine. Mirrors {@code CommandSupernova} from 1.7.10
 * SuperNova, with the RGB-specific subcommands removed.
 */
public class CommandPulsar extends CommandBase {

    @Override
    public String getName() {
        return "pulsar";
    }

    @Override
    public String getUsage(final ICommandSender sender) {
        return "/pulsar <stats | relight [<cx> <cz> | <radius>]>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(final MinecraftServer server, final ICommandSender sender, final String[] args) throws CommandException {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
            return;
        }

        switch (args[0]) {
            case "stats":
                processStats(sender);
                return;
            case "relight":
                processRelight(sender, args);
                return;
            default:
                sender.sendMessage(new TextComponentString(getUsage(sender)));
        }
    }

    private void processStats(final ICommandSender sender) {
        final World world = sender.getEntityWorld();
        if (world == null) {
            sender.sendMessage(new TextComponentString("No world available."));
            return;
        }
        final WorldLightManager mgr = ((PulsarWorld) world).pulsar$getLightManager();
        if (mgr == null) {
            sender.sendMessage(new TextComponentString("Pulsar light manager is not active for this world."));
            return;
        }
        sender.sendMessage(new TextComponentString("§ePulsar§r — see logs/pulsar-stats.log for live stats."));
        sender.sendMessage(new TextComponentString("Pending light updates: " + (mgr.hasUpdates() ? "yes" : "no")));
    }

    private void processRelight(final ICommandSender sender, final String[] args) throws CommandException {
        final World world = sender.getEntityWorld();
        if (world == null) {
            sender.sendMessage(new TextComponentString("No world available."));
            return;
        }
        final WorldLightManager mgr = ((PulsarWorld) world).pulsar$getLightManager();
        if (mgr == null) {
            sender.sendMessage(new TextComponentString("Pulsar light manager is not active for this world."));
            return;
        }

        if (args.length == 3) {
            // /pulsar relight <cx> <cz>
            final int cx = parseInt(args[1]);
            final int cz = parseInt(args[2]);
            if (mgr.forceRelightChunk(cx, cz)) {
                sender.sendMessage(new TextComponentString("Queued relight for chunk (" + cx + ", " + cz + ")."));
            } else {
                sender.sendMessage(new TextComponentString("Chunk (" + cx + ", " + cz + ") is not loaded."));
            }
        } else if (args.length == 2) {
            // /pulsar relight <radius>
            if (!(sender instanceof EntityPlayerMP)) {
                sender.sendMessage(new TextComponentString("Radius form requires a player sender."));
                return;
            }
            final EntityPlayerMP player = (EntityPlayerMP) sender;
            final int radius = Math.min(Math.max(0, parseInt(args[1])), 16);
            final int playerCx = (int) player.posX >> 4;
            final int playerCz = (int) player.posZ >> 4;
            int count = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (mgr.forceRelightChunk(playerCx + dx, playerCz + dz)) count++;
                }
            }
            sender.sendMessage(new TextComponentString("Queued relight for " + count + " chunks (radius " + radius + ")."));
        } else {
            // /pulsar relight (no args -- current chunk)
            if (!(sender instanceof EntityPlayerMP)) {
                sender.sendMessage(new TextComponentString("Specify chunk coordinates: /pulsar relight <cx> <cz>"));
                return;
            }
            final EntityPlayerMP player = (EntityPlayerMP) sender;
            final int cx = (int) player.posX >> 4;
            final int cz = (int) player.posZ >> 4;
            if (mgr.forceRelightChunk(cx, cz)) {
                sender.sendMessage(new TextComponentString("Queued relight for chunk (" + cx + ", " + cz + ")."));
            } else {
                sender.sendMessage(new TextComponentString("Chunk (" + cx + ", " + cz + ") is not loaded."));
            }
        }
    }

    @Override
    public List<String> getTabCompletions(final MinecraftServer server, final ICommandSender sender,
                                          final String[] args, final net.minecraft.util.math.BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, Arrays.asList("stats", "relight"));
        }
        return Collections.emptyList();
    }
}
