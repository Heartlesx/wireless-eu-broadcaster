package com.wirelesseu.item;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Portable binding card for the broadcaster: sneak-right-click a broadcaster to bind, and every compatible GT machine
 * the carrying player places afterwards is connected automatically. Targets placed while the broadcaster chunk is
 * unloaded, or before the broadcaster is upgraded, are queued on the card and committed once the condition is met.
 */
public final class EnergyBindingCardItem extends Item {
    public static final int MAX_PENDING_TARGETS = 64;
    private static final String TAG = "WirelessEU";

    public EnergyBindingCardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        MetaMachine clickedMachine = MetaMachine.getMachine(level, clickedPos);
        if (!(clickedMachine instanceof WirelessEuBroadcasterMachine) || !context.getPlayer().isShiftKeyDown()) {
            // Non-sneak clicks stay untouched so the broadcaster keeps opening its GUI like before.
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!com.wirelesseu.WirelessEuMod.enableAutoConnect) {
            return InteractionResult.SUCCESS;
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            ((WirelessEuBroadcasterMachine) clickedMachine).handleBindingCardUse(player,
                    context.getItemInHand());
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (getBinding(stack) == null) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_card_not_bound"));
            return InteractionResultHolder.success(stack);
        }
        clear(stack);
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_card_unbound_done"));
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @org.jetbrains.annotations.Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        Binding binding = getBinding(stack);
        if (binding == null) {
            tooltip.add(Component.translatable("tooltip.wireless_eu.binding_card_unbound"));
            return;
        }
        tooltip.add(Component.translatable("tooltip.wireless_eu.binding_card_bound",
                binding.pos().getX(), binding.pos().getY(), binding.pos().getZ()));
        if (level != null && !level.dimension().location().equals(binding.dimension())) {
            tooltip.add(Component.translatable("tooltip.wireless_eu.binding_card_dimension",
                    dimensionName(binding.dimension())));
        }
        int pending = getPendingTargets(stack).size();
        if (pending > 0) {
            tooltip.add(Component.translatable("tooltip.wireless_eu.binding_card_pending", pending));
        }
    }

    private static String dimensionName(ResourceLocation dimension) {
        String key = "gui.wireless_eu.dimension." + dimension.getNamespace() + "." + dimension.getPath();
        String translated = Component.translatable(key).getString();
        if (translated.equals(key) || translated.isBlank()) {
            return dimension.getPath();
        }
        return translated;
    }

    /** Stores the binding and resets the queue, so a rebound card starts clean. */
    public static void bind(ItemStack stack, ResourceLocation dimension, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTagElement(TAG);
        tag.putString("BoundDimension", dimension.toString());
        tag.putLong("BoundPos", pos.asLong());
        tag.remove("PendingTargets");
        tag.remove("UpgradeWarned");
    }

    public static void clear(ItemStack stack) {
        CompoundTag tag = stack.getTagElement(TAG);
        if (tag != null) {
            tag.remove("BoundDimension");
            tag.remove("BoundPos");
            tag.remove("PendingTargets");
            tag.remove("UpgradeWarned");
        }
    }

    public static Binding getBinding(ItemStack stack) {
        CompoundTag tag = stack.getTagElement(TAG);
        if (tag == null || !tag.contains("BoundDimension", Tag.TAG_STRING)
                || !tag.contains("BoundPos", Tag.TAG_LONG)) {
            return null;
        }
        return new Binding(ResourceLocation.parse(tag.getString("BoundDimension")),
                BlockPos.of(tag.getLong("BoundPos")));
    }

    /** @return the queue size after the call, or -1 when the queue is full and nothing was added. */
    public static int addPendingTarget(ItemStack stack, ResourceLocation dimension, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTagElement(TAG);
        ListTag pending = tag.getList("PendingTargets", Tag.TAG_COMPOUND);
        long packed = pos.asLong();
        for (int i = 0; i < pending.size(); i++) {
            CompoundTag entry = pending.getCompound(i);
            if (dimension.toString().equals(entry.getString("Dimension")) && entry.getLong("Pos") == packed) {
                return pending.size();
            }
        }
        if (pending.size() >= MAX_PENDING_TARGETS) {
            return -1;
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("Dimension", dimension.toString());
        entry.putLong("Pos", packed);
        pending.add(entry);
        tag.put("PendingTargets", pending);
        return pending.size();
    }

    public static List<PendingTarget> getPendingTargets(ItemStack stack) {
        List<PendingTarget> result = new ArrayList<>();
        CompoundTag tag = stack.getTagElement(TAG);
        if (tag == null) {
            return result;
        }
        ListTag pending = tag.getList("PendingTargets", Tag.TAG_COMPOUND);
        for (int i = 0; i < pending.size() && i < MAX_PENDING_TARGETS; i++) {
            CompoundTag entry = pending.getCompound(i);
            result.add(new PendingTarget(ResourceLocation.parse(entry.getString("Dimension")),
                    BlockPos.of(entry.getLong("Pos"))));
        }
        return result;
    }

    public static void setPendingTargets(ItemStack stack, List<PendingTarget> targets) {
        CompoundTag tag = stack.getOrCreateTagElement(TAG);
        if (targets.isEmpty()) {
            tag.remove("PendingTargets");
            return;
        }
        ListTag pending = new ListTag();
        for (PendingTarget target : targets) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Dimension", target.dimension().toString());
            entry.putLong("Pos", target.pos().asLong());
            pending.add(entry);
        }
        tag.put("PendingTargets", pending);
    }

    public static boolean isUpgradeWarned(ItemStack stack) {
        CompoundTag tag = stack.getTagElement(TAG);
        return tag != null && tag.getBoolean("UpgradeWarned");
    }

    public static void setUpgradeWarned(ItemStack stack, boolean warned) {
        stack.getOrCreateTagElement(TAG).putBoolean("UpgradeWarned", warned);
    }

    public static ServerLevel getBoundLevel(ServerPlayer player, Binding binding) {
        return player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, binding.dimension()));
    }

    public static Component targetLocation(ResourceLocation dimension, BlockPos pos) {
        return WirelessEuBroadcasterMachine.locationText(
                com.wirelesseu.command.WirelessEuCommands.teleportTargetCommand(dimension, pos),
                "chat.wireless_eu.target_location_hover", pos);
    }

    public record Binding(ResourceLocation dimension, BlockPos pos) {
    }

    public record PendingTarget(ResourceLocation dimension, BlockPos pos) {
    }
}
