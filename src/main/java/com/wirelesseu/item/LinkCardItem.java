package com.wirelesseu.item;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class LinkCardItem extends Item {
    public static final int MAX_PENDING_TARGETS = 32;
    private static final String BOUND_DIMENSION = "BoundDimension";
    private static final String BOUND_POS = "BoundPos";
    private static final String PENDING_TARGETS = "PendingTargets";

    public LinkCardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return handleUse(stack, context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return handleUse(context.getItemInHand(), context);
    }

    private InteractionResult handleUse(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        MetaMachine clickedMachine = MetaMachine.getMachine(level, clickedPos);
        boolean broadcaster = clickedMachine instanceof WirelessEuBroadcasterMachine;
        if (level.isClientSide) {
            return broadcaster || WirelessEuBroadcasterMachine.isPotentialLinkTarget(level, clickedPos)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        if (clickedMachine instanceof WirelessEuBroadcasterMachine machine) {
            return machine.handleLinkCardUse(player, stack, player.isShiftKeyDown())
                    ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        if (!WirelessEuBroadcasterMachine.isPotentialLinkTarget(level, clickedPos)) {
            return InteractionResult.PASS;
        }
        Binding binding = getBinding(stack);
        if (binding == null) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_unbound"));
            return InteractionResult.CONSUME;
        }
        ServerLevel sourceLevel = getBoundLevel(player, binding);
        if (sourceLevel == null || !sourceLevel.hasChunkAt(binding.pos())) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_source_missing"));
            return InteractionResult.CONSUME;
        }
        MetaMachine sourceMachine = MetaMachine.getMachine(sourceLevel, binding.pos());
        if (!(sourceMachine instanceof WirelessEuBroadcasterMachine machine)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_source_missing"));
            return InteractionResult.CONSUME;
        }
        machine.collectLinkTarget(player, stack, (ServerLevel) level, clickedPos);
        return InteractionResult.CONSUME;
    }

    public static void bind(ItemStack stack, ResourceLocation dimension, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(BOUND_DIMENSION, dimension.toString());
        tag.putLong(BOUND_POS, pos.asLong());
        tag.remove(PENDING_TARGETS);
    }

    public static Binding getBinding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(BOUND_DIMENSION, Tag.TAG_STRING)
                || !tag.contains(BOUND_POS, Tag.TAG_LONG)) {
            return null;
        }
        return new Binding(ResourceLocation.parse(tag.getString(BOUND_DIMENSION)),
                BlockPos.of(tag.getLong(BOUND_POS)));
    }

    public static void addPendingTarget(ItemStack stack, ResourceLocation dimension, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTag();
        ListTag pending = tag.getList(PENDING_TARGETS, Tag.TAG_COMPOUND);
        CompoundTag entry = new CompoundTag();
        entry.putString(BOUND_DIMENSION, dimension.toString());
        entry.putLong(BOUND_POS, pos.asLong());
        pending.add(entry);
        tag.put(PENDING_TARGETS, pending);
    }

    public static List<PendingTarget> getPendingTargets(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return List.of();
        }
        ListTag pending = tag.getList(PENDING_TARGETS, Tag.TAG_COMPOUND);
        List<PendingTarget> result = new ArrayList<>(Math.min(MAX_PENDING_TARGETS, pending.size()));
        for (int index = 0; index < pending.size() && index < MAX_PENDING_TARGETS; index++) {
            CompoundTag entry = pending.getCompound(index);
            if (entry.contains(BOUND_DIMENSION, Tag.TAG_STRING) && entry.contains(BOUND_POS, Tag.TAG_LONG)) {
                result.add(new PendingTarget(ResourceLocation.parse(entry.getString(BOUND_DIMENSION)),
                        BlockPos.of(entry.getLong(BOUND_POS))));
            }
        }
        return result;
    }

    public static void clearPendingTargets(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(PENDING_TARGETS);
        }
    }

    private static ServerLevel getBoundLevel(ServerPlayer player, Binding binding) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, binding.dimension());
        return player.getServer().getLevel(dimension);
    }

    public record Binding(ResourceLocation dimension, BlockPos pos) {
    }

    public record PendingTarget(ResourceLocation dimension, BlockPos pos) {
    }
}
