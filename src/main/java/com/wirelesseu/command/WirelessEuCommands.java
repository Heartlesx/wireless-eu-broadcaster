package com.wirelesseu.command;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teleport helper used by the clickable coordinates in the broadcaster terminal chat messages.
 *
 * A command only accepts coordinates the server itself handed to that player within the last five
 * minutes, so the 0-permission command cannot be used to teleport to arbitrary machines.
 */
@Mod.EventBusSubscriber(modid = WirelessEuMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WirelessEuCommands {
    private static final long TICKET_LIFETIME_MILLIS = 5L * 60L * 1000L;
    private static final Map<UUID, List<Ticket>> TICKETS = new HashMap<>();

    private WirelessEuCommands() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("wireless_eu")
                .then(Commands.literal("goto_broadcaster")
                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .executes(WirelessEuCommands::teleportToBroadcaster)))
                .then(Commands.literal("goto_target")
                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                .then(Commands.argument("position", BlockPosArgument.blockPos())
                                        .executes(WirelessEuCommands::teleportToTarget)))));
    }

    public static void rememberTeleport(ServerPlayer player, ResourceLocation dimension, BlockPos position) {
        long now = System.currentTimeMillis();
        List<Ticket> tickets = TICKETS.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>());
        tickets.removeIf(ticket -> ticket.expiresAt() < now);
        tickets.add(new Ticket(dimension, position.immutable(), now + TICKET_LIFETIME_MILLIS));
    }

    private static boolean hasTicket(ServerPlayer player, ResourceLocation dimension, BlockPos position) {
        List<Ticket> tickets = TICKETS.get(player.getUUID());
        if (tickets == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Iterator<Ticket> iterator = tickets.iterator();
        boolean found = false;
        while (iterator.hasNext()) {
            Ticket ticket = iterator.next();
            if (ticket.expiresAt() < now) {
                iterator.remove();
                continue;
            }
            if (!found && ticket.dimension().equals(dimension) && ticket.position().equals(position)) {
                found = true;
            }
        }
        return found;
    }

    public static String teleportCommand(BlockPos position) {
        return "/wireless_eu goto_broadcaster " + position.getX() + " " + position.getY() + " " + position.getZ();
    }

    public static String teleportTargetCommand(ResourceLocation dimension, BlockPos position) {
        return "/wireless_eu goto_target " + dimension + " " + position.getX() + " " + position.getY() + " "
                + position.getZ();
    }

    private static int teleportToBroadcaster(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos position = BlockPosArgument.getLoadedBlockPos(context, "position");
        ResourceLocation dimension = player.serverLevel().dimension().location();
        if (!hasTicket(player, dimension, position)) {
            context.getSource().sendFailure(Component.translatable("chat.wireless_eu.teleport_no_ticket"));
            return 0;
        }
        MetaMachine machine = MetaMachine.getMachine(player.serverLevel(), position);
        if (!(machine instanceof WirelessEuBroadcasterMachine)) {
            context.getSource().sendFailure(Component.literal("目标位置没有无线 EU 广播器"));
            return 0;
        }

        player.teleportTo(player.serverLevel(), position.getX() + 0.5D, position.getY() + 1.0D,
                position.getZ() + 0.5D, player.getYRot(), player.getXRot());
        return 1;
    }

    private static int teleportToTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ResourceLocation dimension = ResourceLocationArgument.getId(context, "dimension");
        ServerLevel targetLevel = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        BlockPos position = BlockPosArgument.getBlockPos(context, "position");
        if (targetLevel == null || !targetLevel.hasChunkAt(position)) {
            context.getSource().sendFailure(Component.translatable("chat.wireless_eu.target_unavailable"));
            return 0;
        }
        if (!hasTicket(player, dimension, position)) {
            context.getSource().sendFailure(Component.translatable("chat.wireless_eu.teleport_no_ticket"));
            return 0;
        }
        MetaMachine machine = MetaMachine.getMachine(targetLevel, position);
        if (machine == null || machine instanceof WirelessEuBroadcasterMachine) {
            context.getSource().sendFailure(Component.translatable("chat.wireless_eu.target_not_found"));
            return 0;
        }

        player.teleportTo(targetLevel, position.getX() + 0.5D, position.getY() + 1.0D,
                position.getZ() + 0.5D, player.getYRot(), player.getXRot());
        return 1;
    }

    private record Ticket(ResourceLocation dimension, BlockPos position, long expiresAt) {
    }
}
