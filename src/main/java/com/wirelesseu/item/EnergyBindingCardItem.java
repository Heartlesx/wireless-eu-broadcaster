package com.wirelesseu.item;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Portable binding card for the broadcaster: sneak-right-click a broadcaster to bind, and every compatible GT machine
 * the carrying player places afterwards is connected automatically. Targets placed while the broadcaster chunk is
 * unloaded are queued on the card and committed once the chunk loads again.
 */
public final class EnergyBindingCardItem extends Item {
    public static final int MAX_PENDING_TARGETS = 64;
    private static final String TAG = "WirelessEU";

    public EnergyBindingCardItem() {
        setMaxStackSize(1);
        setRegistryName("wireless_eu", "energy_binding_card");
        setTranslationKey("wireless_eu.energy_binding_card");
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing side,
                                     float hitX, float hitY, float hitZ) {
        WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(world, pos);
        if (machine == null || !player.isSneaking()) {
            // Non-sneak clicks stay untouched so the broadcaster keeps opening its GUI like before.
            return EnumActionResult.PASS;
        }
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        if (WirelessEuMod.enableAutoConnect) {
            machine.handleBindingCardUse(player, player.getHeldItem(hand));
        }
        return EnumActionResult.SUCCESS;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!player.isSneaking()) {
            return new ActionResult<>(EnumActionResult.PASS, stack);
        }
        if (world.isRemote) {
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        }
        Binding binding = getBinding(stack);
        if (binding == null) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_card_not_bound"));
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        }
        clear(stack);
        player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_card_unbound_done"));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        Binding binding = getBinding(stack);
        if (binding == null) {
            tooltip.add(I18n.format("tooltip.wireless_eu.binding_card_unbound"));
            return;
        }
        tooltip.add(I18n.format("tooltip.wireless_eu.binding_card_bound",
                binding.pos.getX(), binding.pos.getY(), binding.pos.getZ()));
        if (worldIn != null && worldIn.provider.getDimension() != binding.dimension) {
            tooltip.add(I18n.format("tooltip.wireless_eu.binding_card_dimension", dimensionName(binding.dimension)));
        }
        int pending = getPendingTargets(stack).size();
        if (pending > 0) {
            tooltip.add(I18n.format("tooltip.wireless_eu.binding_card_pending", pending));
        }
    }

    private static String dimensionName(int dimension) {
        DimensionType type = DimensionType.getById(dimension);
        String name = type == null ? String.valueOf(dimension) : type.getName();
        String key = "gui.wireless_eu.dimension." + name;
        String translated = I18n.format(key);
        return !translated.isEmpty() && !translated.equals(key) ? translated : name;
    }

    /** Stores the binding and resets the queue, so a rebound card starts clean. */
    public static void bind(ItemStack stack, int dimension, BlockPos pos) {
        NBTTagCompound tag = stack.getOrCreateSubCompound(TAG);
        tag.setInteger("BoundDimension", dimension);
        tag.setLong("BoundPos", pos.toLong());
        tag.removeTag("PendingTargets");
        tag.removeTag("UpgradeWarned");
    }

    public static void clear(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound(TAG);
        if (tag != null) {
            tag.removeTag("BoundDimension");
            tag.removeTag("BoundPos");
            tag.removeTag("PendingTargets");
            tag.removeTag("UpgradeWarned");
        }
    }

    public static Binding getBinding(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound(TAG);
        if (tag == null || !tag.hasKey("BoundDimension") || !tag.hasKey("BoundPos")) {
            return null;
        }
        return new Binding(tag.getInteger("BoundDimension"), BlockPos.fromLong(tag.getLong("BoundPos")));
    }

    /** @return the queue size after the call, or -1 when the queue is full and nothing was added. */
    public static int addPendingTarget(ItemStack stack, int dimension, BlockPos pos) {
        NBTTagCompound tag = stack.getOrCreateSubCompound(TAG);
        NBTTagList pending = tag.getTagList("PendingTargets", 10);
        long packed = pos.toLong();
        for (int i = 0; i < pending.tagCount(); i++) {
            NBTTagCompound entry = pending.getCompoundTagAt(i);
            if (entry.getInteger("Dimension") == dimension && entry.getLong("Pos") == packed) {
                return pending.tagCount();
            }
        }
        if (pending.tagCount() >= MAX_PENDING_TARGETS) {
            return -1;
        }
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("Dimension", dimension);
        entry.setLong("Pos", packed);
        pending.appendTag(entry);
        tag.setTag("PendingTargets", pending);
        return pending.tagCount();
    }

    public static List<PendingTarget> getPendingTargets(ItemStack stack) {
        List<PendingTarget> result = new ArrayList<>();
        NBTTagCompound tag = stack.getSubCompound(TAG);
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

    public static void setPendingTargets(ItemStack stack, List<PendingTarget> targets) {
        NBTTagCompound tag = stack.getOrCreateSubCompound(TAG);
        if (targets.isEmpty()) {
            tag.removeTag("PendingTargets");
            return;
        }
        NBTTagList pending = new NBTTagList();
        for (PendingTarget target : targets) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("Dimension", target.dimension);
            entry.setLong("Pos", target.pos.toLong());
            pending.appendTag(entry);
        }
        tag.setTag("PendingTargets", pending);
    }

    public static boolean isUpgradeWarned(ItemStack stack) {
        NBTTagCompound tag = stack.getSubCompound(TAG);
        return tag != null && tag.getBoolean("UpgradeWarned");
    }

    public static void setUpgradeWarned(ItemStack stack, boolean warned) {
        stack.getOrCreateSubCompound(TAG).setBoolean("UpgradeWarned", warned);
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
