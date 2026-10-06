package com.wirelesseu.machine;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.command.WirelessEuCommand;
import com.wirelesseu.gui.WirelessEuContainer;
import com.wirelesseu.gui.WirelessEuGuiHandler;
import com.wirelesseu.item.EnergyBindingCardItem;
import com.wirelesseu.item.LinkCardItem;
import com.wirelesseu.network.WirelessEuNetwork;
import gregtech.api.GTValues;
import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IEnergyContainer;
import gregtech.api.capability.ILaserContainer;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.ITieredMetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.common.metatileentities.electric.MetaTileEntityBatteryBuffer;
import gregtech.common.pipelike.cable.net.EnergyNetWalker;
import gregtech.common.pipelike.cable.net.EnergyRoutePath;
import gregtech.common.pipelike.cable.tile.TileEntityCable;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.oredict.OreDictionary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GT 1.12.2 implementation of the Wireless EU broadcaster. It deliberately uses GT's MetaTileEntity lifecycle so
 * the block keeps the battery-buffer casing and GT's EU/laser capability conventions.
 */
public final class WirelessEuBroadcasterMachine extends MetaTileEntityBatteryBuffer {
    public static final int RANGE = 384;
    public static final int UPGRADE_CIRCUIT_COUNT = 32;
    public static final int LINK_BATCH_SIZE = 32;
    // Dc = decillion (10^33), Ud = undecillion (10^36).
    private static final String[] COMPACT_EU_SUFFIXES = {"", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q", "Dc", "Ud"};

    private final int tier;
    private final Map<Long, WirelessTarget> connections = new LinkedHashMap<>();
    private final Map<String, WirelessRemoteTarget> remoteConnections = new LinkedHashMap<>();
    private final List<ItemStack> upgradeCircuits = new ArrayList<>();
    private List<WirelessTarget> scanResults = new ArrayList<>();
    private int roundRobinIndex;
    private BigInteger inputSample = BigInteger.ZERO;
    private BigInteger inputPerSecond = BigInteger.ZERO;
    private long lastInputSampleTick = -1L;
    private BigInteger lastEnergySample = BigInteger.valueOf(-1L);
    private boolean bufferIncreasing;
    private long inputFirewallSampleTick = Long.MIN_VALUE;
    private final EnumMap<EnumFacing, Boolean> inputFirewallCache = new EnumMap<>(EnumFacing.class);
    private UUID ownerUuid;
    private boolean crossDimensionUnlocked;

    public WirelessEuBroadcasterMachine(ResourceLocation id, int tier) {
        super(id, tier, 16);
        this.tier = tier;
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new WirelessEuBroadcasterMachine(metaTileEntityId, tier);
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        super.getSubItems(tab, items);
        ItemStack full = getStackForm();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("WirelessEuFullTest", true);
        full.setTagCompound(tag);
        full.setStackDisplayName(I18n.format("item.wireless_eu.full_test",
                I18n.format(getMetaName() + ".name")));
        items.add(full);
    }

    @Override
    public String getItemSubTypeId(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound().getBoolean("WirelessEuFullTest")
                ? "full_test" : super.getItemSubTypeId(stack);
    }

    @Override
    public void initFromItemStackData(NBTTagCompound tag) {
        super.initFromItemStackData(tag);
        if (tag.getBoolean("WirelessEuFullTest")) {
            ((AmplifyingLaserContainer) energyContainer).changeEnergy(getCapacityEu().subtract(getStoredEu()));
        }
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add("Wireless EU: " + tierName(tier));
        tooltip.add("Input: 1x or 2.5x EU (server config), unlimited amperage");
        tooltip.add("Output: unlimited amperage at machine voltage");
        tooltip.add("Buffer: " + formatCompactEu(AmplifyingLaserContainer.CAPACITY) + " EU");
        tooltip.add("Range: " + RANGE + " blocks");
    }

    @Override
    protected void reinitializeEnergyContainer() {
        long voltage = getVoltage();
        this.energyContainer = new AmplifyingLaserContainer(this, voltage);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, EnumFacing side) {
        if (capability == GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER
                || capability == GregtechTileCapabilities.CAPABILITY_LASER) {
            return (T) energyContainer;
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void onPlacement(EntityLivingBase placer) {
        super.onPlacement(placer);
        if (placer instanceof EntityPlayer) {
            ownerUuid = placer.getUniqueID();
            markDirty();
        }
    }

    @Override
    public void update() {
        super.update();
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }
        // Power is pushed every tick like the 1.20.1 build; the 20 tick block only refreshes the slower values.
        broadcastEnergy();
        if (getOffsetTimer() % 20L == 0L) {
            updateInputRate();
            validateConnections();
            syncOpenTerminals();
        }
    }

    private void updateInputRate() {
        long tick = getOffsetTimer();
        if (lastInputSampleTick >= 0L) {
            long elapsed = Math.max(1L, tick - lastInputSampleTick);
            inputPerSecond = inputSample.multiply(BigInteger.valueOf(20L)).divide(BigInteger.valueOf(elapsed));
        }
        inputSample = BigInteger.ZERO;
        lastInputSampleTick = tick;
        BigInteger stored = getStoredEu();
        bufferIncreasing = lastEnergySample.signum() >= 0 && stored.compareTo(lastEnergySample) > 0;
        lastEnergySample = stored;
    }

    void recordInput(BigInteger amount) {
        inputSample = inputSample.add(amount);
    }

    private void broadcastEnergy() {
        if (connections.isEmpty() && remoteConnections.isEmpty()) {
            return;
        }
        List<BroadcastTarget> targets = new ArrayList<>();
        for (WirelessTarget target : connections.values()) {
            targets.add(new BroadcastTarget(getWorld(), target));
        }
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            World world = DimensionManager.getWorld(target.dimension);
            if (world != null) {
                targets.add(new BroadcastTarget(world, target));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        int start = Math.floorMod(roundRobinIndex, targets.size());
        for (int offset = 0; offset < targets.size() && getStoredEu().signum() > 0; offset++) {
            BroadcastTarget target = targets.get((start + offset) % targets.size());
            Receiver receiver = resolveReceiver(target.world, target.pos);
            if (receiver == null || receiver.container.getInputVoltage() > getVoltage()) {
                continue;
            }
            long voltage = Math.max(1L, receiver.container.getInputVoltage());
            // GT receivers multiply voltage by amps in long arithmetic; keep each call representable.
            long available = getStoredEu().divide(BigInteger.valueOf(voltage))
                    .min(BigInteger.valueOf(Long.MAX_VALUE / voltage)).longValue();
            long accepted = Math.min(available, Math.max(1L, receiver.container.getInputAmperage()));
            if (accepted <= 0L) {
                continue;
            }
            accepted = receiver.container.acceptEnergyFromNetwork(receiver.side,
                    receiver.container.getInputVoltage(), accepted);
            if (accepted > 0L) {
                long transferred = accepted * voltage;
                energyContainer.changeEnergy(-transferred);
            }
        }
        roundRobinIndex = (start + 1) % targets.size();
    }

    private void validateConnections() {
        Iterator<Map.Entry<Long, WirelessTarget>> local = connections.entrySet().iterator();
        while (local.hasNext()) {
            WirelessTarget target = local.next().getValue();
            if (!getWorld().isBlockLoaded(target.pos)) {
                continue;
            }
            if (findTarget(getWorld(), target.pos, false) == null) {
                local.remove();
            }
        }
        markDirty();
    }

    public long getVoltage() {
        return GTValues.V[getTier()];
    }

    @Override
    public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing, CuboidRayTraceResult hit) {
        if (!player.isSneaking() && getWorld() != null && !getWorld().isRemote) {
            player.openGui(WirelessEuMod.instance, WirelessEuGuiHandler.BROADCASTER, getWorld(), getPos().getX(),
                    getPos().getY(), getPos().getZ());
            if (player instanceof EntityPlayerMP) {
                sendStateTo((EntityPlayerMP) player);
            }
            return true;
        }
        return super.onRightClick(player, hand, facing, hit);
    }

    public int getTierValue() {
        return tier;
    }

    public BigInteger getInputPerSecond() {
        return inputPerSecond;
    }

    public BigInteger getFillTimeSeconds() {
        BigInteger remaining = getCapacityEu().subtract(getStoredEu());
        if (remaining.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return inputPerSecond.signum() <= 0 ? BigInteger.valueOf(-1L)
                : remaining.add(inputPerSecond).subtract(BigInteger.ONE).divide(inputPerSecond);
    }

    public BigInteger getDepletionTimeSeconds() {
        BigInteger output = getConnectedLoadEuPerTick();
        BigInteger stored = getStoredEu();
        if (bufferIncreasing || output.signum() <= 0 || stored.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger outputPerSecond = output.multiply(BigInteger.valueOf(20L));
        return stored.add(outputPerSecond).subtract(BigInteger.ONE).divide(outputPerSecond);
    }

    public BigInteger getConnectedLoadEuPerTick() {
        BigInteger result = BigInteger.ZERO;
        for (WirelessTarget target : connections.values()) {
            result = result.add(BigInteger.valueOf(target.voltage).multiply(BigInteger.valueOf(target.amperage)));
        }
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            result = result.add(BigInteger.valueOf(target.voltage).multiply(BigInteger.valueOf(target.amperage)));
        }
        return result;
    }

    public boolean isOwner(EntityPlayer player) {
        return ownerUuid == null || ownerUuid.equals(player.getUniqueID());
    }

    public boolean isUpgradeCircuit(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem().getRegistryName() == null) {
            return false;
        }
        String domain = stack.getItem().getRegistryName().getNamespace();
        if (!"gregtech".equals(domain) && !"gtlitecore".equals(domain)) {
            return false;
        }
        String tierId = WirelessEuMachines.TIER_IDS[indexOfTier()];
        for (int id : OreDictionary.getOreIDs(stack)) {
            String oreName = OreDictionary.getOreName(id).toLowerCase(Locale.ROOT);
            if (oreName.startsWith("circuit") && (oreName.contains(tierId) || isKnownCircuitFamily(oreName))) {
                return true;
            }
        }
        return false;
    }

    public int installedCircuitCount() {
        int total = 0;
        for (ItemStack stack : upgradeCircuits) {
            total += stack.getCount();
        }
        return total;
    }

    private int indexOfTier() {
        for (int i = 0; i < WirelessEuMachines.TIERS.length; i++) {
            if (WirelessEuMachines.TIERS[i] == tier) {
                return i;
            }
        }
        return 0;
    }

    private boolean isKnownCircuitFamily(String oreName) {
        String family = oreName.startsWith("circuit.") ? oreName.substring("circuit.".length()) : oreName;
        String[] families;
        switch (indexOfTier()) {
            case 0:
                families = new String[]{"electronic", "basic_integrated", "microprocessor"};
                break;
            case 1:
                families = new String[]{"good_electronic", "processor"};
                break;
            case 2:
                families = new String[]{"advanced_integrated", "processor.assembly", "nano_processor"};
                break;
            case 3:
                families = new String[]{"nano_processor.assembly", "quantum_processor"};
                break;
            case 4:
                families = new String[]{"crystal_processor"};
                break;
            case 5:
                families = new String[]{"wetware_processor"};
                break;
            case 6:
                families = new String[]{"wetware_processor.assembly", "gooware.processor"};
                break;
            case 7:
                families = new String[]{"gooware.assembly", "optical.processor"};
                break;
            case 8:
                families = new String[]{"gooware.computer", "optical.assembly", "spintronic.processor"};
                break;
            case 9:
                families = new String[]{"gooware.mainframe", "optical.computer", "spintronic.assembly",
                        "cosmic.processor"};
                break;
            case 10:
                families = new String[]{"optical.mainframe", "spintronic.computer", "cosmic.assembly",
                        "supracausal.processor"};
                break;
            case 11:
                families = new String[]{"spintronic.mainframe", "cosmic.computer", "supracausal.assembly"};
                break;
            case 12:
                families = new String[]{"cosmic.mainframe", "supracausal.computer"};
                break;
            case 13:
                families = new String[]{"supracausal.mainframe"};
                break;
            default:
                families = new String[0];
        }
        for (String candidate : families) {
            if (family.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean insertUpgradeCircuits(EntityPlayer player, net.minecraft.util.EnumHand hand) {
        if (!isOwner(player)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_owner"));
            return false;
        }
        if (crossDimensionUnlocked) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_already"));
            return false;
        }
        ItemStack held = player.getHeldItem(hand);
        if (!isUpgradeCircuit(held)) {
            return false;
        }
        int inserted = Math.min(held.getCount(), UPGRADE_CIRCUIT_COUNT - installedCircuitCount());
        if (inserted <= 0) {
            return false;
        }
        ItemStack copy = held.copy();
        copy.setCount(inserted);
        upgradeCircuits.add(copy);
        held.shrink(inserted);
        crossDimensionUnlocked = installedCircuitCount() >= UPGRADE_CIRCUIT_COUNT;
        markDirty();
        if (crossDimensionUnlocked) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_success", tierName(tier)));
        } else {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_inserted", inserted,
                    UPGRADE_CIRCUIT_COUNT - installedCircuitCount(), tierName(tier)));
        }
        return true;
    }

    public void sendUpgradeRemainingMessage(EntityPlayer player) {
        if (crossDimensionUnlocked) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_already"));
        } else if (isOwner(player)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_remaining",
                    UPGRADE_CIRCUIT_COUNT - installedCircuitCount(), tierName(tier)));
        } else {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.upgrade_owner"));
        }
    }

    public void addUpgradeCircuitDrops(List<ItemStack> drops) {
        for (ItemStack stack : upgradeCircuits) {
            drops.add(stack.copy());
        }
    }

    public boolean handleLinkCardUse(EntityPlayer player, ItemStack card, boolean sneaking) {
        if (!isOwner(player)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_owner"));
            return true;
        }
        if (!crossDimensionUnlocked) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_not_upgraded"));
            return true;
        }
        if (sneaking) {
            LinkCardItem.Binding binding = LinkCardItem.getBinding(card);
            if (binding != null && (binding.dimension != getWorld().provider.getDimension()
                    || !binding.pos.equals(getPos()))) {
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_bound_other"));
                return true;
            }
            LinkCardItem.bind(card, getWorld().provider.getDimension(), getPos());
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_bound"));
            return true;
        }
        LinkCardItem.Binding binding = LinkCardItem.getBinding(card);
        if (binding == null || binding.dimension != getWorld().provider.getDimension()
                || !binding.pos.equals(getPos())) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_unbound"));
            return true;
        }
        commitLinkCard(player, card);
        return true;
    }

    /** Validates the whole batch first, so one bad target cancels the commit instead of linking half of it. */
    private void commitLinkCard(EntityPlayer player, ItemStack card) {
        List<LinkCardItem.PendingTarget> pending = LinkCardItem.getPendingTargets(card);
        if (pending.isEmpty()) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_commit_empty"));
            return;
        }
        List<WirelessRemoteTarget> additions = new ArrayList<>();
        for (LinkCardItem.PendingTarget entry : pending) {
            World targetWorld = DimensionManager.getWorld(entry.dimension);
            if (targetWorld == null || !targetWorld.isBlockLoaded(entry.pos)) {
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_unloaded"));
                return;
            }
            if (targetWorld == getWorld() && isInRange(entry.pos)) {
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_in_range"));
                return;
            }
            WirelessTarget target = findTarget(targetWorld, entry.pos, false);
            if (target == null) {
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_invalid"));
                return;
            }
            if (remoteConnections.containsKey(remoteKey(entry.dimension, entry.pos))
                    || containsRemote(additions, entry.dimension, entry.pos)) {
                continue;
            }
            WirelessRemoteTarget remote = new WirelessRemoteTarget(entry.dimension, entry.pos, target.name,
                    target.kind, target.tier, target.voltage, target.amperage);
            additions.add(remote);
        }
        for (WirelessRemoteTarget remote : additions) {
            remoteConnections.put(remoteKey(remote.dimension, remote.pos), remote);
        }
        LinkCardItem.clearPendingTargets(card);
        markDirty();
        player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_commit_success", additions.size()));
    }

    private static boolean containsRemote(Collection<WirelessRemoteTarget> targets, int dimension, BlockPos pos) {
        for (WirelessRemoteTarget target : targets) {
            if (target.dimension == dimension && target.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public void collectLinkTarget(EntityPlayer player, ItemStack card, World targetWorld, BlockPos targetPos) {
        if (!isOwner(player)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_owner"));
            return;
        }
        if (!crossDimensionUnlocked) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_not_upgraded"));
            return;
        }
        LinkCardItem.Binding binding = LinkCardItem.getBinding(card);
        if (binding == null || binding.dimension != getWorld().provider.getDimension()
                || !binding.pos.equals(getPos())) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_card_bound_other"));
            return;
        }
        if (targetWorld == getWorld() && isInRange(targetPos)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_in_range"));
            return;
        }
        List<LinkCardItem.PendingTarget> pending = LinkCardItem.getPendingTargets(card);
        if (pending.size() >= LinkCardItem.MAX_PENDING_TARGETS) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_limit"));
            return;
        }
        int dimension = targetWorld.provider.getDimension();
        if (remoteConnections.containsKey(remoteKey(dimension, targetPos))
                || containsPending(pending, dimension, targetPos)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_duplicate"));
            return;
        }
        if (findTarget(targetWorld, targetPos, false) == null) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_invalid"));
            return;
        }
        LinkCardItem.addPendingTarget(card, dimension, targetPos);
        player.sendMessage(new TextComponentTranslation("chat.wireless_eu.link_target_added",
                pending.size() + 1, LinkCardItem.MAX_PENDING_TARGETS));
    }

    private static boolean containsPending(List<LinkCardItem.PendingTarget> pending, int dimension, BlockPos pos) {
        for (LinkCardItem.PendingTarget entry : pending) {
            if (entry.dimension == dimension && entry.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public enum AutoConnectStatus {
        CONNECTED,
        ALREADY,
        NEEDS_UPGRADE,
        TIER_TOO_HIGH,
        NOT_TARGET,
        NOT_OWNER
    }

    public static final class AutoConnectResult {
        public final AutoConnectStatus status;
        public final String targetName;

        AutoConnectResult(AutoConnectStatus status, String targetName) {
            this.status = status;
            this.targetName = targetName;
        }
    }

    public void handleBindingCardUse(EntityPlayer player, ItemStack card) {
        if (!isOwner(player)) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_card_owner"));
            return;
        }
        int dimension = getWorld().provider.getDimension();
        EnergyBindingCardItem.Binding binding = EnergyBindingCardItem.getBinding(card);
        if (binding != null && binding.dimension == dimension && binding.pos.equals(getPos())) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.binding_card_bound").appendSibling(
                    locationText(WirelessEuCommand.teleportCommand(getPos()),
                            "chat.wireless_eu.broadcaster_location_hover", getPos())));
            return;
        }
        boolean rebound = binding != null;
        EnergyBindingCardItem.bind(card, dimension, getPos());
        player.sendMessage(new TextComponentTranslation(rebound
                        ? "chat.wireless_eu.binding_card_rebound" : "chat.wireless_eu.binding_card_bound")
                .appendSibling(locationText(WirelessEuCommand.teleportCommand(getPos()),
                        "chat.wireless_eu.broadcaster_location_hover", getPos())));
    }

    /**
     * Connects one target of a player carrying a binding card. The connection is written exactly like a manual one,
     * so the GUI, the load display and the migration keep treating it the same; the returned status tells the caller
     * what to message and when to keep the target queued.
     */
    public AutoConnectResult autoConnectTarget(EntityPlayer player, World targetWorld, BlockPos targetPos) {
        if (!isOwner(player)) {
            return new AutoConnectResult(AutoConnectStatus.NOT_OWNER, null);
        }
        if (targetWorld == null || !targetWorld.isBlockLoaded(targetPos)) {
            return new AutoConnectResult(AutoConnectStatus.NOT_TARGET, null);
        }
        int dimension = targetWorld.provider.getDimension();
        boolean sameWorld = targetWorld == getWorld();
        boolean inRange = sameWorld && isInRange(targetPos);
        if (!inRange && !crossDimensionUnlocked) {
            return new AutoConnectResult(AutoConnectStatus.NEEDS_UPGRADE, null);
        }
        if (remoteConnections.containsKey(remoteKey(dimension, targetPos))
                || (sameWorld && connections.containsKey(targetPos.toLong()))) {
            return new AutoConnectResult(AutoConnectStatus.ALREADY, null);
        }
        if (!isPotentialLinkTarget(targetWorld, targetPos)) {
            return new AutoConnectResult(AutoConnectStatus.NOT_TARGET, null);
        }
        WirelessTarget target = findTarget(targetWorld, targetPos, false);
        if (target == null) {
            return new AutoConnectResult(AutoConnectStatus.TIER_TOO_HIGH, null);
        }
        if (inRange) {
            connections.put(target.pos.toLong(), target);
        } else {
            remoteConnections.put(remoteKey(dimension, target.pos), new WirelessRemoteTarget(dimension, target.pos,
                    target.name, target.kind, target.tier, target.voltage, target.amperage));
        }
        markDirty();
        syncOpenTerminals();
        return new AutoConnectResult(AutoConnectStatus.CONNECTED, target.name);
    }

    public void handleAction(EntityPlayerMP player, WirelessEuNetwork.ServerAction action,
                             List<WirelessEuNetwork.TargetReference> selectedTargets) {
        if (action == WirelessEuNetwork.ServerAction.LOCATE_TARGET) {
            sendTargetLocation(player, selectedTargets);
            sendStateTo(player);
            return;
        }
        if (action == WirelessEuNetwork.ServerAction.LOCATE_BROADCASTER) {
            sendHigherBroadcasterLocation(player, selectedTargets);
            sendStateTo(player);
            return;
        }
        if (!isOwner(player)) {
            sendStateTo(player);
            return;
        }
        switch (action) {
            case SCAN:
                scanTargets();
                break;
            case CONNECT_SELECTED:
                if (!selectedTargets.isEmpty()) {
                    BlockPos conflictingBroadcaster = findConflictingBroadcaster();
                    if (conflictingBroadcaster != null) {
                        WirelessEuNetwork.sendConnectionRejected(player, getPos(),
                                WirelessEuNetwork.ConnectionRejection.BROADCASTER_LIMIT);
                        sendConflictingBroadcasterMessage(player, conflictingBroadcaster);
                    } else {
                        WirelessEuNetwork.ConnectionRejection rejection = connectTargets(selectedTargets);
                        if (rejection != null) {
                            WirelessEuNetwork.sendConnectionRejected(player, getPos(), rejection);
                        }
                    }
                }
                break;
            case DISCONNECT_SELECTED:
                disconnectTargets(selectedTargets);
                break;
            case MIGRATE_TO_HIGHER:
                migrateToHigherBroadcaster(player, selectedTargets);
                break;
            default:
                break;
        }
        sendStateTo(player);
    }

    public void sendStateTo(EntityPlayerMP player) {
        WirelessEuNetwork.sendState(player, this);
    }

    private void syncOpenTerminals() {
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }
        for (EntityPlayer player : getWorld().playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            if (player.openContainer instanceof WirelessEuContainer
                    && ((WirelessEuContainer) player.openContainer).getPos().equals(getPos())) {
                sendStateTo((EntityPlayerMP) player);
            }
        }
    }

    public void scanTargets() {
        List<WirelessTarget> found = new ArrayList<>();
        for (Object raw : getWorld().loadedTileEntityList) {
            if (!(raw instanceof MetaTileEntityHolder)) {
                continue;
            }
            MetaTileEntityHolder holder = (MetaTileEntityHolder) raw;
            WirelessTarget target = findTarget(getWorld(), holder.getPos(), true);
            if (target != null) {
                found.add(target);
            }
        }
        found.sort(Comparator.comparingDouble(target -> target.pos.distanceSq(getPos())));
        scanResults = found;
        markDirty();
    }

    private WirelessEuNetwork.ConnectionRejection connectTargets(
            Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
        if (getWorld() == null) {
            return null;
        }
        int dimension = getWorld().provider.getDimension();
        List<WirelessTarget> additions = new ArrayList<>();
        for (WirelessTarget target : scanResults) {
            if (!selectedTargets.contains(new WirelessEuNetwork.TargetReference(dimension, target.pos))
                    || connections.containsKey(target.pos.toLong())) {
                continue;
            }
            WirelessTarget refreshed = findTarget(getWorld(), target.pos, true);
            if (refreshed != null) {
                additions.add(refreshed);
            }
        }
        for (WirelessTarget target : additions) {
            connections.put(target.pos.toLong(), target);
        }
        markDirty();
        return null;
    }

    private void disconnectTargets(Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
        boolean changed = false;
        int dimension = getWorld() == null ? Integer.MIN_VALUE : getWorld().provider.getDimension();
        for (WirelessEuNetwork.TargetReference reference : selectedTargets) {
            if (reference.dimension == dimension) {
                changed |= connections.remove(reference.pos.toLong()) != null;
            }
            changed |= remoteConnections.remove(remoteKey(reference.dimension, reference.pos)) != null;
        }
        if (changed) {
            markDirty();
        }
    }

    /** 1.20.1 rule: only one same-tier broadcaster may be linked inside the scan cube. */
    private BlockPos findConflictingBroadcaster() {
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Object raw : getWorld().loadedTileEntityList) {
            if (!(raw instanceof MetaTileEntityHolder)) {
                continue;
            }
            MetaTileEntity meta = ((MetaTileEntityHolder) raw).getMetaTileEntity();
            if (!(meta instanceof WirelessEuBroadcasterMachine) || meta == this) {
                continue;
            }
            WirelessEuBroadcasterMachine candidate = (WirelessEuBroadcasterMachine) meta;
            if (candidate.getTier() != getTier() || !isInRange(candidate.getPos())) {
                continue;
            }
            double distance = candidate.getPos().distanceSq(getPos());
            if (distance < closestDistance) {
                closest = candidate.getPos();
                closestDistance = distance;
            }
        }
        return closest;
    }

    private void sendConflictingBroadcasterMessage(EntityPlayerMP player, BlockPos position) {
        player.sendMessage(new TextComponentTranslation("chat.wireless_eu.broadcaster_conflict").appendSibling(
                locationText(WirelessEuCommand.teleportCommand(position),
                        "chat.wireless_eu.broadcaster_location_hover", position)));
    }

    private boolean isInRange(BlockPos pos) {
        BlockPos origin = getPos();
        return Math.abs(pos.getX() - origin.getX()) <= RANGE
                && Math.abs(pos.getY() - origin.getY()) <= RANGE
                && Math.abs(pos.getZ() - origin.getZ()) <= RANGE;
    }

    public List<WirelessEuNetwork.HigherBroadcasterInfo> getHigherBroadcasters() {
        List<WirelessEuNetwork.HigherBroadcasterInfo> result = new ArrayList<>();
        for (WirelessEuBroadcasterMachine candidate : findHigherBroadcasters()) {
            result.add(new WirelessEuNetwork.HigherBroadcasterInfo(candidate.getPos(), candidate.getTier(),
                    (long) candidate.getPos().distanceSq(getPos())));
        }
        return result;
    }

    private List<WirelessEuBroadcasterMachine> findHigherBroadcasters() {
        List<WirelessEuBroadcasterMachine> result = new ArrayList<>();
        if (getWorld() == null) {
            return result;
        }
        for (Object raw : getWorld().loadedTileEntityList) {
            if (!(raw instanceof MetaTileEntityHolder)) {
                continue;
            }
            MetaTileEntity meta = ((MetaTileEntityHolder) raw).getMetaTileEntity();
            if (!(meta instanceof WirelessEuBroadcasterMachine) || meta == this) {
                continue;
            }
            WirelessEuBroadcasterMachine candidate = (WirelessEuBroadcasterMachine) meta;
            if (candidate.getTier() > getTier()
                    && candidate.getPos().distanceSq(getPos()) <= (double) RANGE * RANGE) {
                result.add(candidate);
            }
        }
        result.sort(Comparator.comparingDouble(machine -> machine.getPos().distanceSq(getPos())));
        return result;
    }

    private void migrateToHigherBroadcaster(EntityPlayerMP player,
                                            Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
        WirelessEuNetwork.TargetReference selected = selectedTargets.isEmpty() ? null
                : selectedTargets.iterator().next();
        WirelessEuBroadcasterMachine destination = null;
        if (selected != null) {
            for (WirelessEuBroadcasterMachine candidate : findHigherBroadcasters()) {
                if (candidate.getPos().equals(selected.pos)) {
                    destination = candidate;
                    break;
                }
            }
        }
        if (destination == null) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.migration_not_found"));
            return;
        }
        if (crossDimensionUnlocked && !destination.crossDimensionUnlocked) {
            WirelessEuNetwork.sendConnectionRejected(player, getPos(),
                    WirelessEuNetwork.ConnectionRejection.MIGRATION_WIRELESS_UPGRADE_REQUIRED);
            return;
        }
        BigInteger stored = getStoredEu();
        if (stored.compareTo(destination.getCapacityEu().subtract(destination.getStoredEu())) > 0) {
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.migration_capacity"));
            return;
        }
        destination.connections.putAll(connections);
        destination.remoteConnections.putAll(remoteConnections);
        destination.upgradeCircuits.addAll(copyCircuits());
        destination.crossDimensionUnlocked |= crossDimensionUnlocked;
        if (stored.signum() > 0) {
            ((AmplifyingLaserContainer) destination.energyContainer).changeEnergy(stored);
            ((AmplifyingLaserContainer) energyContainer).changeEnergy(stored.negate());
        }
        connections.clear();
        remoteConnections.clear();
        upgradeCircuits.clear();
        crossDimensionUnlocked = false;
        markDirty();
        destination.markDirty();
        player.sendMessage(new TextComponentTranslation("chat.wireless_eu.migration_success"));
    }

    private void sendTargetLocation(EntityPlayerMP player,
                                    Collection<WirelessEuNetwork.TargetReference> targets) {
        for (WirelessEuNetwork.TargetReference reference : targets) {
            World targetWorld = getWorld() != null && reference.dimension == getWorld().provider.getDimension()
                    ? getWorld() : DimensionManager.getWorld(reference.dimension);
            if (targetWorld == null || !targetWorld.isBlockLoaded(reference.pos)) {
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.target_unavailable"));
                continue;
            }
            if (findTarget(targetWorld, reference.pos, false) == null) {
                player.sendMessage(new TextComponentTranslation("chat.wireless_eu.target_not_found"));
                continue;
            }
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.target_location").appendSibling(
                    locationText(WirelessEuCommand.teleportTargetCommand(reference.dimension, reference.pos),
                            "chat.wireless_eu.target_location_hover", reference.pos)));
        }
    }

    private void sendHigherBroadcasterLocation(EntityPlayerMP player,
                                               Collection<WirelessEuNetwork.TargetReference> targets) {
        if (targets.isEmpty()) {
            return;
        }
        BlockPos requested = targets.iterator().next().pos;
        for (WirelessEuBroadcasterMachine candidate : findHigherBroadcasters()) {
            if (!candidate.getPos().equals(requested)) {
                continue;
            }
            player.sendMessage(new TextComponentTranslation("chat.wireless_eu.broadcaster_location").appendSibling(
                    locationText(WirelessEuCommand.teleportCommand(candidate.getPos()),
                            "chat.wireless_eu.broadcaster_location_hover", candidate.getPos())));
            return;
        }
    }

    public static TextComponentString locationText(String command, String hoverKey, BlockPos pos) {
        TextComponentString location = new TextComponentString(
                "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]");
        location.setStyle(new Style()
                .setColor(TextFormatting.AQUA)
                .setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentTranslation(hoverKey))));
        return location;
    }

    private List<ItemStack> copyCircuits() {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : upgradeCircuits) {
            result.add(stack.copy());
        }
        return result;
    }

    public List<WirelessTarget> getScanResults() {
        return Collections.unmodifiableList(scanResults);
    }

    public List<WirelessTarget> getConnections() {
        return new ArrayList<>(connections.values());
    }

    public List<WirelessRemoteTarget> getRemoteConnections() {
        return new ArrayList<>(remoteConnections.values());
    }

    public boolean isCrossDimensionUnlocked() {
        return crossDimensionUnlocked;
    }

    /**
     * Battery buffers may be wireless targets, but their EU must never return into the broadcaster input: the input
     * would amplify it by 2.5x again and create energy out of nothing. Anything a battery buffer outputs into the
     * cable network of this input side is therefore refused.
     */
    boolean isInputBlocked(EnumFacing side) {
        if (side == null || getWorld() == null || getWorld().isRemote) {
            return false;
        }
        long tick = getOffsetTimer();
        if (tick != inputFirewallSampleTick) {
            inputFirewallSampleTick = tick;
            inputFirewallCache.clear();
        }
        Boolean cached = inputFirewallCache.get(side);
        if (cached != null) {
            return cached;
        }
        boolean blocked = findBatteryBufferOutput(side);
        inputFirewallCache.put(side, blocked);
        return blocked;
    }

    private boolean findBatteryBufferOutput(EnumFacing broadcasterSide) {
        World world = getWorld();
        BlockPos adjacentPos = getPos().offset(broadcasterSide);
        MetaTileEntity adjacentMachine = getMetaTileEntity(world, adjacentPos);
        if (adjacentMachine instanceof MetaTileEntityBatteryBuffer) {
            return outputsInto(adjacentMachine, broadcasterSide.getOpposite());
        }
        if (!(world.getTileEntity(adjacentPos) instanceof TileEntityCable)) {
            return false;
        }
        for (EnergyRoutePath route : EnergyNetWalker.createNetData(world, adjacentPos)) {
            EnumFacing routeFacing = route.getTargetFacing();
            EnumFacing intoCable = routeFacing.getOpposite();
            BlockPos pipePos = route.getTargetPipe().getPos();
            if (isBatteryBufferOutputAt(world, pipePos.offset(routeFacing), intoCable)
                    || isBatteryBufferOutputAt(world, pipePos.offset(intoCable), routeFacing)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBatteryBufferOutputAt(World world, BlockPos pos, EnumFacing outputSide) {
        if (world == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        MetaTileEntity machine = getMetaTileEntity(world, pos);
        return machine instanceof MetaTileEntityBatteryBuffer && outputsInto(machine, outputSide);
    }

    private static boolean outputsInto(MetaTileEntity machine, EnumFacing side) {
        IEnergyContainer container = machine.getCapability(GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER, side);
        return container != null && container.outputsEnergy(side);
    }

    public long getEnergyStored() {
        return energyContainer == null ? 0L : energyContainer.getEnergyStored();
    }

    public long getEnergyCapacity() {
        return energyContainer == null ? 0L : energyContainer.getEnergyCapacity();
    }

    public BigInteger getStoredEu() {
        return energyContainer == null ? BigInteger.ZERO : ((AmplifyingLaserContainer) energyContainer).getStoredEu();
    }

    public BigInteger getCapacityEu() {
        return AmplifyingLaserContainer.CAPACITY;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("RoundRobinIndex", roundRobinIndex);
        tag.setBoolean("CrossDimensionUnlocked", crossDimensionUnlocked);
        if (ownerUuid != null) {
            tag.setUniqueId("Owner", ownerUuid);
        }
        NBTTagList local = new NBTTagList();
        for (WirelessTarget target : connections.values()) {
            local.appendTag(target.save());
        }
        tag.setTag("WirelessConnections", local);
        NBTTagList remote = new NBTTagList();
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            remote.appendTag(target.save());
        }
        tag.setTag("WirelessRemoteConnections", remote);
        NBTTagList circuits = new NBTTagList();
        for (ItemStack stack : upgradeCircuits) {
            circuits.appendTag(stack.writeToNBT(new NBTTagCompound()));
        }
        tag.setTag("UpgradeCircuits", circuits);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        roundRobinIndex = Math.max(0, tag.getInteger("RoundRobinIndex"));
        ownerUuid = tag.hasUniqueId("Owner") ? tag.getUniqueId("Owner") : null;
        crossDimensionUnlocked = tag.getBoolean("CrossDimensionUnlocked");
        connections.clear();
        NBTTagList local = tag.getTagList("WirelessConnections", 10);
        for (NBTBase entry : local) {
            WirelessTarget target = WirelessTarget.load((NBTTagCompound) entry, tier, getVoltage());
            connections.put(target.pos.toLong(), target);
        }
        remoteConnections.clear();
        NBTTagList remote = tag.getTagList("WirelessRemoteConnections", 10);
        for (NBTBase entry : remote) {
            WirelessRemoteTarget target = WirelessRemoteTarget.load((NBTTagCompound) entry);
            remoteConnections.put(remoteKey(target.dimension, target.pos), target);
        }
        upgradeCircuits.clear();
        NBTTagList circuits = tag.getTagList("UpgradeCircuits", 10);
        for (NBTBase entry : circuits) {
            ItemStack stack = new ItemStack((NBTTagCompound) entry);
            if (!stack.isEmpty() && isUpgradeCircuit(stack)) {
                upgradeCircuits.add(stack);
            }
        }
        crossDimensionUnlocked |= installedCircuitCount() >= UPGRADE_CIRCUIT_COUNT;
    }

    private WirelessTarget findTarget(World world, BlockPos pos, boolean requireRange) {
        if (world == null || !world.isBlockLoaded(pos)) {
            return null;
        }
        if (world == getWorld() && pos.equals(getPos())) {
            return null;
        }
        if (requireRange && !crossDimensionUnlocked
                && (world != getWorld() || pos.distanceSq(getPos()) > (double) RANGE * RANGE)) {
            return null;
        }
        MetaTileEntity machine = getMetaTileEntity(world, pos);
        if (!(machine instanceof ITieredMetaTileEntity) || machine instanceof MultiblockControllerBase
                || machine instanceof WirelessEuBroadcasterMachine) {
            return null;
        }
        ITieredMetaTileEntity tiered = (ITieredMetaTileEntity) machine;
        if (tiered.getTier() > tier) {
            return null;
        }
        Receiver receiver = resolveReceiver(world, pos);
        if (receiver == null || receiver.container.getInputVoltage() <= 0L
                || receiver.container.getInputVoltage() > getVoltage()) {
            return null;
        }
        TargetKind kind = receiver.laser ? TargetKind.LASER_STORAGE : TargetKind.SINGLEBLOCK_MACHINE;
        return new WirelessTarget(pos, machine.getMetaName(), kind, tiered.getTier(),
                receiver.container.getInputVoltage(), Math.max(1L, receiver.container.getInputAmperage()));
    }

    public static boolean isPotentialLinkTarget(World world, BlockPos pos) {
        if (world == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        MetaTileEntity machine = getMetaTileEntity(world, pos);
        if (!(machine instanceof ITieredMetaTileEntity) || machine instanceof MultiblockControllerBase
                || machine instanceof WirelessEuBroadcasterMachine) {
            return false;
        }
        return resolveReceiver(world, pos) != null;
    }

    private static Receiver resolveReceiver(World world, BlockPos pos) {
        MetaTileEntity machine = getMetaTileEntity(world, pos);
        if (machine == null) {
            return null;
        }
        for (EnumFacing side : EnumFacing.values()) {
            ILaserContainer laser = machine.getCapability(GregtechTileCapabilities.CAPABILITY_LASER, side);
            if (laser != null && laser.inputsEnergy(side)) {
                return new Receiver(laser, side, true);
            }
            IEnergyContainer energy = machine.getCapability(GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER, side);
            if (energy != null && energy.inputsEnergy(side)) {
                return new Receiver(energy, side, false);
            }
        }
        return null;
    }

    private static MetaTileEntity getMetaTileEntity(World world, BlockPos pos) {
        if (world == null) {
            return null;
        }
        net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof MetaTileEntityHolder)) {
            return null;
        }
        return ((MetaTileEntityHolder) tile).getMetaTileEntity();
    }

    private static String remoteKey(int dimension, BlockPos pos) {
        return dimension + ":" + pos.toLong();
    }

    public static String tierName(int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : "T" + tier;
    }

    public static String formatEu(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String formatEu(BigInteger value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String formatCompactEu(long value) {
        return formatCompactEu(BigInteger.valueOf(value));
    }

    public static String formatCompactEu(BigInteger value) {
        int exponent = Math.max(0, value.abs().toString().length() - 1);
        int index = Math.min(exponent / 3, COMPACT_EU_SUFFIXES.length - 1);
        if (exponent >= COMPACT_EU_SUFFIXES.length * 3) {
            return new BigDecimal(value).movePointLeft(exponent).setScale(2, RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString() + "e" + exponent;
        }
        return new BigDecimal(value).movePointLeft(index * 3).setScale(2, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + COMPACT_EU_SUFFIXES[index];
    }

    public static String formatAmperageLoad(BigInteger euPerTick, long sourceVoltage) {
        if (sourceVoltage <= 0L || euPerTick.signum() <= 0) {
            return "0A";
        }
        String amperage = new BigDecimal(euPerTick)
                .divide(BigDecimal.valueOf(sourceVoltage), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return (new BigDecimal(amperage).compareTo(BigDecimal.valueOf(1000L)) < 0
                ? amperage : formatCompactEu(new BigDecimal(amperage).toBigInteger())) + "A";
    }

    public static String formatInputEquivalent(BigInteger euPerTick) {
        int tier = -1;
        for (int index = 0; index < GTValues.V.length; index++) {
            if (BigInteger.valueOf(GTValues.V[index]).compareTo(euPerTick) > 0) {
                break;
            }
            tier = index;
        }
        if (tier < 0) {
            return "0A";
        }
        String amperage = new BigDecimal(euPerTick)
                .divide(BigDecimal.valueOf(GTValues.V[tier]), 2, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
        return (new BigDecimal(amperage).compareTo(BigDecimal.valueOf(1000L)) < 0
                ? amperage : formatCompactEu(new BigDecimal(amperage).toBigInteger())) + "A " + tierName(tier) + "/t";
    }

    public static String formatDuration(BigInteger seconds) {
        if (seconds.signum() < 0) {
            return I18n.format("gui.wireless_eu.duration_none");
        }
        if (seconds.signum() == 0) {
            return I18n.format("gui.wireless_eu.duration_zero");
        }
        BigInteger hours = seconds.divide(BigInteger.valueOf(3600L));
        long minutes = seconds.remainder(BigInteger.valueOf(3600L)).longValue() / 60L;
        long remainingSeconds = seconds.remainder(BigInteger.valueOf(60L)).longValue();
        if (hours.signum() > 0) {
            return I18n.format("gui.wireless_eu.duration_hours", formatCompactEu(hours), minutes);
        }
        if (minutes > 0L) {
            return I18n.format("gui.wireless_eu.duration_minutes", minutes, remainingSeconds);
        }
        return I18n.format("gui.wireless_eu.duration_seconds", remainingSeconds);
    }

    private static final class BroadcastTarget {
        private final World world;
        private final BlockPos pos;

        private BroadcastTarget(World world, WirelessTarget target) {
            this.world = world;
            this.pos = target.pos;
        }

        private BroadcastTarget(World world, WirelessRemoteTarget target) {
            this.world = world;
            this.pos = target.pos;
        }
    }

    private static final class Receiver {
        private final IEnergyContainer container;
        private final EnumFacing side;
        private final boolean laser;

        private Receiver(IEnergyContainer container, EnumFacing side, boolean laser) {
            this.container = container;
            this.side = side;
            this.laser = laser;
        }
    }
}
