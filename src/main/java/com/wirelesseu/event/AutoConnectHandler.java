package com.wirelesseu.event;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.item.EnergyBindingCardItem;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Server-side auto-connect for the Energy Binding Card: every compatible machine a carrying player places is
 * connected on the spot, and targets that could not be connected yet (broadcaster chunk unloaded, another
 * same-tier broadcaster in range, or the cross-dimension upgrade missing) are queued on the card and committed
 * once the condition is met.
 */
public final class AutoConnectHandler {
    private static final List<PendingPlacement> PENDING_PLACEMENTS = new ArrayList<>();

    private AutoConnectHandler() {
    }

    /**
     * Forge fires the place event before the block entity is guaranteed to exist, so the event only records the
     * position; the next server tick verifies that a linkable machine actually appeared and connects it then.
     */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!WirelessEuMod.enableAutoConnect
                || !(event.getLevel() instanceof ServerLevel level) || level.isClientSide
                || !(event.getPlacedBlock().getBlock() instanceof MetaMachineBlock)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PENDING_PLACEMENTS.add(new PendingPlacement(player, level.dimension().location(),
                event.getPos().immutable(), player.tickCount));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_PLACEMENTS.isEmpty()) {
            return;
        }
        Iterator<PendingPlacement> iterator = PENDING_PLACEMENTS.iterator();
        while (iterator.hasNext()) {
            PendingPlacement pending = iterator.next();
            ServerLevel level = pending.player().getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, pending.dimension()));
            if (level == null) {
                iterator.remove();
                continue;
            }
            if (level.hasChunkAt(pending.pos())
                    && WirelessEuBroadcasterMachine.isPotentialLinkTarget(level, pending.pos())) {
                for (ItemStack card : findBoundCards(pending.player())) {
                    tryConnectNow(pending.player(), card, level, pending.pos());
                }
                iterator.remove();
            } else if (pending.player().tickCount - pending.createdTick() > 40) {
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!WirelessEuMod.enableAutoConnect || event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 20 != 0) {
            return;
        }
        for (ItemStack card : findBoundCards(player)) {
            if (!EnergyBindingCardItem.getPendingTargets(card).isEmpty()) {
                commitPending(player, card);
            }
        }
    }

    private static void tryConnectNow(ServerPlayer player, ItemStack card, Level targetLevel, BlockPos targetPos) {
        EnergyBindingCardItem.Binding binding = EnergyBindingCardItem.getBinding(card);
        if (binding == null) {
            return;
        }
        ServerLevel sourceLevel = EnergyBindingCardItem.getBoundLevel(player, binding);
        if (sourceLevel == null || !sourceLevel.hasChunkAt(binding.pos())) {
            queueTarget(player, card, targetLevel.dimension().location(), targetPos);
            return;
        }
        WirelessEuBroadcasterMachine machine = getBroadcaster(sourceLevel, binding.pos());
        if (machine == null) {
            EnergyBindingCardItem.clear(card);
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_stale",
                    binding.pos().getX(), binding.pos().getY(), binding.pos().getZ()));
            return;
        }
        WirelessEuBroadcasterMachine.AutoConnectResult result =
                machine.autoConnectTarget(player, targetLevel, targetPos);
        switch (result.status) {
            case CONNECTED -> {
                com.wirelesseu.command.WirelessEuCommands.rememberTeleport(player,
                        targetLevel.dimension().location(), targetPos);
                Component message = Component.translatable("chat.wireless_eu.binding_connected",
                                Component.translatable(result.targetName))
                        .append(EnergyBindingCardItem.targetLocation(targetLevel.dimension().location(), targetPos));
                player.sendSystemMessage(message);
                player.displayClientMessage(message, true);
            }
            case NEEDS_UPGRADE -> {
                if (EnergyBindingCardItem.addPendingTarget(card, targetLevel.dimension().location(), targetPos) < 0) {
                    player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_queue_full",
                            EnergyBindingCardItem.MAX_PENDING_TARGETS));
                } else {
                    EnergyBindingCardItem.setUpgradeWarned(card, true);
                    player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_needs_upgrade"));
                }
            }
            case BROADCASTER_LIMIT -> {
                if (EnergyBindingCardItem.addPendingTarget(card, targetLevel.dimension().location(), targetPos) < 0) {
                    player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_queue_full",
                            EnergyBindingCardItem.MAX_PENDING_TARGETS));
                } else {
                    player.sendSystemMessage(Component.translatable(
                            "chat.wireless_eu.binding_broadcaster_conflict"));
                }
            }
            case TIER_TOO_HIGH -> player.sendSystemMessage(
                    Component.translatable("chat.wireless_eu.binding_tier_too_high"));
            case NOT_OWNER -> player.sendSystemMessage(
                    Component.translatable("chat.wireless_eu.binding_card_owner"));
            default -> {
            }
        }
    }

    private static void queueTarget(ServerPlayer player, ItemStack card, net.minecraft.resources.ResourceLocation dimension,
                                    BlockPos pos) {
        int size = EnergyBindingCardItem.addPendingTarget(card, dimension, pos);
        Component message = size < 0
                ? Component.translatable("chat.wireless_eu.binding_queue_full",
                EnergyBindingCardItem.MAX_PENDING_TARGETS)
                : Component.translatable("chat.wireless_eu.binding_queued", size,
                EnergyBindingCardItem.MAX_PENDING_TARGETS);
        player.sendSystemMessage(message);
        player.displayClientMessage(message, true);
    }

    private static void commitPending(ServerPlayer player, ItemStack card) {
        EnergyBindingCardItem.Binding binding = EnergyBindingCardItem.getBinding(card);
        if (binding == null) {
            return;
        }
        ServerLevel sourceLevel = EnergyBindingCardItem.getBoundLevel(player, binding);
        if (sourceLevel == null || !sourceLevel.hasChunkAt(binding.pos())) {
            return;
        }
        WirelessEuBroadcasterMachine machine = getBroadcaster(sourceLevel, binding.pos());
        if (machine == null) {
            EnergyBindingCardItem.clear(card);
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_stale",
                    binding.pos().getX(), binding.pos().getY(), binding.pos().getZ()));
            return;
        }
        List<EnergyBindingCardItem.PendingTarget> keep = new ArrayList<>();
        int connected = 0;
        int dropped = 0;
        boolean upgradeBlocked = false;
        for (EnergyBindingCardItem.PendingTarget entry : EnergyBindingCardItem.getPendingTargets(card)) {
            ServerLevel targetLevel = EnergyBindingCardItem.getBoundLevel(player, new EnergyBindingCardItem.Binding(
                    entry.dimension(), entry.pos()));
            if (targetLevel == null || !targetLevel.hasChunkAt(entry.pos())) {
                keep.add(entry);
                continue;
            }
            WirelessEuBroadcasterMachine.AutoConnectResult result =
                    machine.autoConnectTarget(player, targetLevel, entry.pos());
            switch (result.status) {
                case CONNECTED -> connected++;
                case NEEDS_UPGRADE, BROADCASTER_LIMIT -> {
                    keep.add(entry);
                    upgradeBlocked = true;
                }
                case ALREADY -> {
                }
                default -> dropped++;
            }
        }
        EnergyBindingCardItem.setPendingTargets(card, keep);
        if (connected > 0 || dropped > 0) {
            EnergyBindingCardItem.setUpgradeWarned(card, false);
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_queue_committed",
                    connected, dropped));
        } else if (upgradeBlocked && !EnergyBindingCardItem.isUpgradeWarned(card)) {
            EnergyBindingCardItem.setUpgradeWarned(card, true);
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_queue_upgrade_pending",
                    keep.size()));
        }
    }

    private static WirelessEuBroadcasterMachine getBroadcaster(ServerLevel level, BlockPos pos) {
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        return machine instanceof WirelessEuBroadcasterMachine broadcaster ? broadcaster : null;
    }

    private static List<ItemStack> findBoundCards(ServerPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        collectBoundCards(player.getInventory().items, result);
        collectBoundCards(player.getInventory().offhand, result);
        return result;
    }

    private static void collectBoundCards(List<ItemStack> source, List<ItemStack> result) {
        for (ItemStack stack : source) {
            if (!stack.isEmpty() && stack.getItem() instanceof EnergyBindingCardItem
                    && EnergyBindingCardItem.getBinding(stack) != null) {
                result.add(stack);
            }
        }
    }

    private record PendingPlacement(ServerPlayer player, ResourceLocation dimension, BlockPos pos, long createdTick) {
    }
}
