package com.wirelesseu.command;

import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/** Teleport helper used by the clickable coordinates in the broadcaster terminal chat messages. */
public final class WirelessEuCommand extends CommandBase {
    @Override
    public String getName() {
        return "wireless_eu";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/wireless_eu goto_broadcaster <x> <y> <z> | /wireless_eu goto_target <dimension> <x> <y> <z>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (args.length == 4 && "goto_broadcaster".equals(args[0])) {
            BlockPos pos = parseBlockPos(sender, args, 1, false);
            WorldServer world = player.getServerWorld();
            if (!world.isBlockLoaded(pos) || WirelessEuMachines.getMachine(world, pos) == null) {
                throw new CommandException("chat.wireless_eu.target_not_found");
            }
            teleport(player, world, pos);
            return;
        }
        if (args.length == 5 && "goto_target".equals(args[0])) {
            int dimension;
            try {
                dimension = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                throw new WrongUsageException(getUsage(sender));
            }
            WorldServer world = DimensionManager.getWorld(dimension);
            BlockPos pos = parseBlockPos(sender, args, 2, false);
            if (world == null || !world.isBlockLoaded(pos)) {
                throw new CommandException("chat.wireless_eu.target_unavailable");
            }
            // Targets are regular machines (a pump, a battery buffer, ...), not broadcasters, so this must use the
            // same predicate as the location message that produced the clickable coordinates.
            if (!WirelessEuBroadcasterMachine.isPotentialLinkTarget(world, pos)) {
                throw new CommandException("chat.wireless_eu.target_not_found");
            }
            teleport(player, world, pos);
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }

    public static String teleportCommand(BlockPos pos) {
        return "/wireless_eu goto_broadcaster " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public static String teleportTargetCommand(int dimension, BlockPos pos) {
        return "/wireless_eu goto_target " + dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void teleport(EntityPlayerMP player, WorldServer world, BlockPos pos) {
        if (player.dimension != world.provider.getDimension()) {
            player.changeDimension(world.provider.getDimension());
        }
        player.connection.setPlayerLocation(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                player.rotationYaw, player.rotationPitch);
    }
}
