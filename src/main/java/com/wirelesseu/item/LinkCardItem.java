package com.wirelesseu.item;

import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public final class LinkCardItem extends Item {
    public static final int MAX_PENDING_TARGETS = 32;

    public LinkCardItem() {
        setMaxStackSize(1);
        setRegistryName("wireless_eu", "link_card");
        setTranslationKey("wireless_eu.link_card");
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing side,
                                     float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);
        WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(world, pos);
        if (machine != null) {
            if (world.isRemote) {
                return EnumActionResult.SUCCESS;
            }
            return machine.handleLinkCardUse(player, stack, player.isSneaking())
                    ? EnumActionResult.SUCCESS : EnumActionResult.PASS;
        }
        if (!WirelessEuBroadcasterMachine.isPotentialLinkTarget(world, pos)) {
            return EnumActionResult.PASS;
        }
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        Binding binding = getBinding(stack);
        if (binding == null) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_unbound"));
            return EnumActionResult.SUCCESS;
        }
        World sourceWorld = net.minecraftforge.common.DimensionManager.getWorld(binding.dimension);
        WirelessEuBroadcasterMachine source = sourceWorld == null ? null
                : WirelessEuMachines.getMachine(sourceWorld, binding.pos);
        if (source == null) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_source_missing"));
            return EnumActionResult.SUCCESS;
        }
        source.collectLinkTarget(player, stack, world, pos);
        return EnumActionResult.SUCCESS;
    }

    public static void bind(ItemStack stack, int dimension, BlockPos pos) {
        NBTTagCompound tag = stack.getOrCreateSubCompound("WirelessEU");
        tag.setInteger("BoundDimension", dimension);
        tag.setLong("BoundPos", pos.toLong());
        tag.removeTag("PendingTargets");
    }

    public static Binding getBinding(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound("WirelessEU");
        if (tag == null || !tag.hasKey("BoundDimension") || !tag.hasKey("BoundPos")) {
            return null;
        }
        return new Binding(tag.getInteger("BoundDimension"), BlockPos.fromLong(tag.getLong("BoundPos")));
    }

    public static void addPendingTarget(ItemStack stack, int dimension, BlockPos pos) {
        NBTTagCompound tag = stack.getOrCreateSubCompound("WirelessEU");
        NBTTagList pending = tag.getTagList("PendingTargets", 10);
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("Dimension", dimension);
        entry.setLong("Pos", pos.toLong());
        pending.appendTag(entry);
        tag.setTag("PendingTargets", pending);
    }

    public static List<PendingTarget> getPendingTargets(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound("WirelessEU");
        List<PendingTarget> result = new ArrayList<>();
        if (tag == null) {
            return result;
        }
        NBTTagList pending = tag.getTagList("PendingTargets", 10);
        for (int i = 0; i < pending.tagCount() && i < MAX_PENDING_TARGETS; i++) {
            NBTTagCompound entry = pending.getCompoundTagAt(i);
            result.add(new PendingTarget(entry.getInteger("Dimension"), BlockPos.fromLong(entry.getLong("Pos"))));
        }
        return result;
    }

    public static void clearPendingTargets(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound("WirelessEU");
        if (tag != null) {
            tag.removeTag("PendingTargets");
        }
    }

    public static final class Binding {
        public final int dimension;
        public final BlockPos pos;

        public Binding(int dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }

    public static final class PendingTarget {
        public final int dimension;
        public final BlockPos pos;

        public PendingTarget(int dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }
}
