package com.wirelesseu.event;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.command.WirelessEuCommand;
import com.wirelesseu.item.EnergyBindingCardItem;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side auto-connect for the Energy Binding Card: every compatible machine a carrying player places is
 * connected on the spot, and targets that could not be connected yet (broadcaster chunk unloaded or the
 * cross-dimension upgrade missing) are queued on the card and committed once the condition is met.
 */
public final class AutoConnectHandler {
    private AutoConnectHandler() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(AutoConnectHandler.class);
    }

    @SubscribeEvent
    @SuppressWarnings("deprecation") // Forge marks PlaceEvent deprecated, but it is the only event fired on the
    // player item-placement path (ForgeHooks.onPlaceItemIntoWorld); EntityPlaceEvent is never fired there.
    public static void onBlockPlaced(BlockEvent.PlaceEvent event) {
        if (!WirelessEuMod.enableAutoConnect) {
            return;
        }
        World world = event.getWorld();
        if (world.isRemote || !WirelessEuBroadcasterMachine.isPotentialLinkTarget(world, event.getPos())) {
            return;
        }
        EntityPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        for (ItemStack card : findBoundCards(player)) {
            tryConnectNow(player, card, world, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!WirelessEuMod.enableAutoConnect || event.phase != TickEvent.Phase.END
                || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.ticksExisted % 20 != 0) {
            return;
        }
        for (ItemStack card : findBoundCards(player)) {
            if (!EnergyBindingCardItem.getPendingTargets(card).isEmpty()) {
                commitPending(player, card);
            }
        }
    }

    private static void tryConnectNow(EntityPlayer player, ItemStack card, World targetWorld, BlockPos targetPos) {
        EnergyBindingCardItem.Binding binding = EnergyBindingCardItem.getBinding(card);
        if (binding == null) {
            return;
        }
        World sourceWorld = DimensionManager.getWorld(binding.dimension);
        if (sourceWorld == null || !sourceWorld.isBlockLoaded(binding.pos)) {
            queueTarget(player, card, targetWorld.provider.getDimension(), targetPos);
            return;
        }
        WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(sourceWorld, binding.pos);
        if (machine == null) {
            EnergyBindingCardItem.clear(card);
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_stale",
                    binding.pos.getX(), binding.pos.getY(), binding.pos.getZ()));
            return;
        }
        WirelessEuBroadcasterMachine.AutoConnectResult result =
                machine.autoConnectTarget(player, targetWorld, targetPos);
        switch (result.status) {
            case CONNECTED:
                ITextComponent message = new TextComponentTranslation("chat.wireless_eu.binding_connected",
                        result.targetName).appendSibling(targetLocation(targetWorld.provider.getDimension(), targetPos));
                player.sendMessage(message);
                player.sendStatusMessage(message, true);
                break;
            case NEEDS_UPGRADE:
                // Queued for the upgrade; the placement message replaces the tick handler's throttled warning.
                if (EnergyBindingCardItem.addPendingTarget(card, targetWorld.provider.getDimension(), targetPos) < 0) {
                    player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_queue_full",
                            EnergyBindingCardItem.MAX_PENDING_TARGETS));
                } else {
                    EnergyBindingCardItem.setUpgradeWarned(card, true);
                    player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_needs_upgrade"));
                }
                break;
            case TIER_TOO_HIGH:
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_tier_too_high"));
                break;
            case NOT_OWNER:
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_card_owner"));
                break;
            default:
                break;
        }
    }

    private static void queueTarget(EntityPlayer player, ItemStack card, int dimension, BlockPos pos) {
        int size = EnergyBindingCardItem.addPendingTarget(card, dimension, pos);
        ITextComponent message = size < 0
                ? new TextComponentTranslation("chat.wireless_eu.binding_queue_full",
                        EnergyBindingCardItem.MAX_PENDING_TARGETS)
                : new TextComponentTranslation("chat.wireless_eu.binding_queued", size,
                        EnergyBindingCardItem.MAX_PENDING_TARGETS);
        player.sendMessage(message);
        player.sendStatusMessage(message, true);
    }

    private static void commitPending(EntityPlayerMP player, ItemStack card) {
        EnergyBindingCardItem.Binding binding = EnergyBindingCardItem.getBinding(card);
        if (binding == null) {
            return;
        }
        World sourceWorld = DimensionManager.getWorld(binding.dimension);
        if (sourceWorld == null || !sourceWorld.isBlockLoaded(binding.pos)) {
            return;
        }
        WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(sourceWorld, binding.pos);
        if (machine == null) {
            EnergyBindingCardItem.clear(card);
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_stale",
                    binding.pos.getX(), binding.pos.getY(), binding.pos.getZ()));
            return;
        }
        List<EnergyBindingCardItem.PendingTarget> keep = new ArrayList<>();
        int connected = 0;
        int dropped = 0;
        boolean upgradeBlocked = false;
        for (EnergyBindingCardItem.PendingTarget entry : EnergyBindingCardItem.getPendingTargets(card)) {
            World targetWorld = DimensionManager.getWorld(entry.dimension);
            if (targetWorld == null || !targetWorld.isBlockLoaded(entry.pos)) {
                keep.add(entry);
                continue;
            }
            WirelessEuBroadcasterMachine.AutoConnectResult result =
                    machine.autoConnectTarget(player, targetWorld, entry.pos);
            switch (result.status) {
                case CONNECTED:
                    connected++;
                    break;
                case NEEDS_UPGRADE:
                    keep.add(entry);
                    upgradeBlocked = true;
                    break;
                case ALREADY:
                    break;
                default:
                    dropped++;
                    break;
            }
        }
        EnergyBindingCardItem.setPendingTargets(card, keep);
        if (connected > 0 || dropped > 0) {
            EnergyBindingCardItem.setUpgradeWarned(card, false);
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_queue_committed",
                    connected, dropped));
        } else if (upgradeBlocked && !EnergyBindingCardItem.isUpgradeWarned(card)) {
            EnergyBindingCardItem.setUpgradeWarned(card, true);
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_queue_upgrade_pending",
                    keep.size()));
        }
    }

    private static List<ItemStack> findBoundCards(EntityPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        if (player == null || player.inventory == null) {
            return result;
        }
        collectBoundCards(player.inventory.mainInventory, result);
        collectBoundCards(player.inventory.offHandInventory, result);
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

    private static ITextComponent targetLocation(int dimension, BlockPos pos) {
        return WirelessEuBroadcasterMachine.locationText(WirelessEuCommand.teleportTargetCommand(dimension, pos),
                "chat.wireless_eu.target_location_hover", pos);
    }
}
