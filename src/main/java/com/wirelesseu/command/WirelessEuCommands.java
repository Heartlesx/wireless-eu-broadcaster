package com.wirelesseu.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = WirelessEuMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WirelessEuCommands {
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
                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .executes(WirelessEuCommands::teleportToTarget))));
    }

    public static String teleportCommand(BlockPos position) {
        return "/wireless_eu goto_broadcaster " + position.getX() + " " + position.getY() + " " + position.getZ();
    }

    public static String teleportTargetCommand(BlockPos position) {
        return "/wireless_eu goto_target " + position.getX() + " " + position.getY() + " " + position.getZ();
    }

    private static int teleportToBroadcaster(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos position = BlockPosArgument.getLoadedBlockPos(context, "position");
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
        BlockPos position = BlockPosArgument.getLoadedBlockPos(context, "position");
        MetaMachine machine = MetaMachine.getMachine(player.serverLevel(), position);
        if (machine == null || machine instanceof WirelessEuBroadcasterMachine) {
            context.getSource().sendFailure(Component.translatable("chat.wireless_eu.target_not_found"));
            return 0;
        }

        player.teleportTo(player.serverLevel(), position.getX() + 0.5D, position.getY() + 1.0D,
                position.getZ() + 0.5D, player.getYRot(), player.getXRot());
        return 1;
    }
}
