package com.wirelesseu.machine;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.ILaserContainer;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.TieredEnergyMachine;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.ITieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.machine.electric.BatteryBufferMachine;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import com.gregtechceu.gtceu.common.pipelike.cable.LevelEnergyNet;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.command.WirelessEuCommands;
import com.wirelesseu.item.EnergyBindingCardItem;
import com.wirelesseu.menu.WirelessBroadcasterMenu;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WirelessEuBroadcasterMachine extends TieredEnergyMachine
        implements IUIMachine, IInteractedMachine, IMachineLife {
    public static final int RANGE = 384;
    public static final int UPGRADE_CIRCUIT_COUNT = 32;
    private static final BigInteger INFINITE_SECONDS = BigInteger.valueOf(-2L);
    private static final BigInteger NO_INPUT_SECONDS = BigInteger.valueOf(-1L);
    // Dc = decillion (10^33), Ud = undecillion (10^36).
    private static final String[] COMPACT_EU_SUFFIXES =
            {"", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q", "Dc", "Ud"};

    private final Map<Long, WirelessTarget> connections = new LinkedHashMap<>();
    private final Map<RemoteTargetKey, WirelessRemoteTarget> remoteConnections = new LinkedHashMap<>();
    private final List<ItemStack> upgradeCircuits = new ArrayList<>();
    private List<WirelessTarget> scanResults = List.of();
    private int roundRobinIndex;
    private BigInteger pendingAmplifiedInput = BigInteger.ZERO;
    private BigInteger inputPerSecond = BigInteger.ZERO;
    private long lastInputSampleTick = -1L;
    private BigInteger lastEnergySample = BigInteger.valueOf(-1L);
    private boolean bufferIncreasing;
    private TickableSubscription broadcasterSubscription;
    private long inputFirewallSampleTick = Long.MIN_VALUE;
    private final EnumMap<Direction, Boolean> inputFirewallCache = new EnumMap<>(Direction.class);
    private boolean crossDimensionUnlocked;

    public WirelessEuBroadcasterMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier);
    }

    @Override
    protected NotifiableEnergyContainer createEnergyContainer(Object... ignored) {
        return new AmplifyingLaserContainer(this, GTValues.V[getTier()]);
    }

    private AmplifyingLaserContainer energy() {
        return (AmplifyingLaserContainer) energyContainer;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemote() && broadcasterSubscription == null) {
            broadcasterSubscription = subscribeServerTick(this::tickBroadcaster);
        }
    }

    @Override
    public void onUnload() {
        if (broadcasterSubscription != null) {
            unsubscribe(broadcasterSubscription);
            broadcasterSubscription = null;
        }
        super.onUnload();
    }

    @Override
    public InteractionResult tryToOpenUI(Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult upgradeResult = handleUpgradeInteraction(player, hand);
        if (upgradeResult != InteractionResult.PASS) {
            return upgradeResult;
        }
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            if (player.level().isClientSide) {
                return InteractionResult.SUCCESS;
            }
            if (isCrossDimensionUnlocked()) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_already"));
            } else if (isOwner(player)) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_remaining",
                        remainingUpgradeCircuits(), tierName(getTier())));
            } else {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_owner"));
            }
            return InteractionResult.CONSUME;
        }
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        MenuProvider provider = new net.minecraft.world.SimpleMenuProvider(
                (containerId, inventory, ignored) -> new WirelessBroadcasterMenu(containerId, inventory, this),
                Component.translatable(getDefinition().getDescriptionId()));
        NetworkHooks.openScreen(serverPlayer, provider, buffer -> buffer.writeBlockPos(getPos()));
        sendStateTo(serverPlayer);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult onUse(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                   BlockHitResult hitResult) {
        return handleUpgradeInteraction(player, hand);
    }

    private InteractionResult handleUpgradeInteraction(Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown() || !isUpgradeCircuit(player.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            insertUpgradeCircuits(serverPlayer, hand);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public ModularUI createUI(Player player) {
        // The interaction path above opens the native selection menu. This satisfies LDLib's UI contract for tools
        // that query a machine UI without invoking the interaction path.
        return new ModularUI(200, 120, this, player);
    }

    @Override
    public void onMachinePlaced(@Nullable LivingEntity player, ItemStack stack) {
        if (player instanceof Player actualPlayer) {
            setOwnerUUID(actualPlayer.getUUID());
            markDirty();
        }
        if (stack.hasTag() && stack.getTag().getBoolean("WirelessEuFullTest")) {
            energy().changeEnergy(energy().getCapacityEu().subtract(energy().getStoredEu()));
        }
    }

    @Override
    public void onMachineRemoved() {
        if (getLevel() == null || getLevel().isClientSide || upgradeCircuits.isEmpty()) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>();
        addUpgradeCircuitDrops(drops);
        upgradeCircuits.clear();
        for (ItemStack stack : drops) {
            Containers.dropItemStack(getLevel(), getPos().getX() + 0.5D, getPos().getY() + 0.5D,
                    getPos().getZ() + 0.5D, stack);
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putInt("RoundRobinIndex", roundRobinIndex);

        ListTag connectionTags = new ListTag();
        for (WirelessTarget target : connections.values()) {
            connectionTags.add(target.save());
        }
        tag.put("WirelessConnections", connectionTags);

        if (forDrop) {
            tag.remove("CrossDimensionUnlocked");
            tag.remove("UpgradeCircuits");
            tag.remove("WirelessRemoteConnections");
            return;
        }

        tag.putBoolean("CrossDimensionUnlocked", crossDimensionUnlocked);
        ListTag circuitTags = new ListTag();
        for (ItemStack stack : upgradeCircuits) {
            circuitTags.add(stack.save(new CompoundTag()));
        }
        tag.put("UpgradeCircuits", circuitTags);

        ListTag remoteTags = new ListTag();
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            remoteTags.add(target.save());
        }
        tag.put("WirelessRemoteConnections", remoteTags);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        roundRobinIndex = Math.max(0, tag.getInt("RoundRobinIndex"));
        connections.clear();
        ListTag connectionTags = tag.getList("WirelessConnections", Tag.TAG_COMPOUND);
        for (Tag entry : connectionTags) {
            WirelessTarget target = WirelessTarget.load((CompoundTag) entry, getTier(), getVoltage());
            connections.put(target.pos().asLong(), target);
        }
        crossDimensionUnlocked = tag.getBoolean("CrossDimensionUnlocked");
        upgradeCircuits.clear();
        ListTag circuitTags = tag.getList("UpgradeCircuits", Tag.TAG_COMPOUND);
        for (Tag entry : circuitTags) {
            ItemStack stack = ItemStack.of((CompoundTag) entry);
            if (!stack.isEmpty() && isUpgradeCircuit(stack)) {
                upgradeCircuits.add(stack);
            }
        }
        crossDimensionUnlocked = crossDimensionUnlocked || installedCircuitCount() >= UPGRADE_CIRCUIT_COUNT;
        remoteConnections.clear();
        ListTag remoteTags = tag.getList("WirelessRemoteConnections", Tag.TAG_COMPOUND);
        for (Tag entry : remoteTags) {
            WirelessRemoteTarget target = WirelessRemoteTarget.load((CompoundTag) entry);
            if (target != null) {
                remoteConnections.put(new RemoteTargetKey(target.dimension(), target.pos().asLong()), target);
            }
        }
    }

    public boolean isCrossDimensionUnlocked() {
        return crossDimensionUnlocked;
    }

    public int remainingUpgradeCircuits() {
        return crossDimensionUnlocked ? 0 : Math.max(0, UPGRADE_CIRCUIT_COUNT - installedCircuitCount());
    }

    public boolean insertUpgradeCircuits(ServerPlayer player, InteractionHand hand) {
        if (!isOwner(player)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_owner"));
            return false;
        }
        if (crossDimensionUnlocked) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_already"));
            return false;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!isUpgradeCircuit(held)) {
            return false;
        }
        int inserted = Math.min(held.getCount(), remainingUpgradeCircuits());
        ItemStack saved = held.copy();
        saved.setCount(inserted);
        addInstalledCircuitStack(saved);
        held.shrink(inserted);
        crossDimensionUnlocked = remainingUpgradeCircuits() == 0;
        markDirty();
        if (crossDimensionUnlocked) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_success", tierName(getTier())));
        } else {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.upgrade_inserted", inserted,
                    remainingUpgradeCircuits(), tierName(getTier())));
        }
        return true;
    }

    public void addUpgradeCircuitDrops(Collection<ItemStack> drops) {
        for (ItemStack stack : upgradeCircuits) {
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
    }

    public boolean isUpgradeCircuit(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        TagKey<Item> requiredTag = circuitTag();
        if (stack.is(requiredTag) || ForgeRegistries.ITEMS.tags().getTag(requiredTag).contains(stack.getItem())) {
            return true;
        }
        // KubeJS/pack-provided tags can use an equivalent TagKey instance; compare the synced tag location too.
        ResourceLocation requiredLocation = requiredTag.location();
        return stack.getTags().anyMatch(tag -> requiredLocation.equals(tag.location()));
    }

    private TagKey<Item> circuitTag() {
        String tierId = tierName(getTier()).toLowerCase(Locale.ROOT);
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("gtceu", "circuits/" + tierId));
    }

    public void handleAction(ServerPlayer player, WirelessEuNetwork.ServerAction action,
                             Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
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
            case SCAN -> scanTargets();
            case MIGRATE_TO_HIGHER -> migrateToHigherBroadcaster(player, selectedTargets);
            case CONNECT_SELECTED -> {
                if (!selectedTargets.isEmpty()) {
                    BlockPos conflictingBroadcaster = findConflictingBroadcaster();
                    if (conflictingBroadcaster != null) {
                        WirelessEuNetwork.sendConnectionRejected(player, getPos(),
                                WirelessEuNetwork.ConnectionRejection.BROADCASTER_LIMIT);
                        sendConflictingBroadcasterMessage(player, conflictingBroadcaster);
                    } else {
                        connectTargets(selectedTargets);
                    }
                }
            }
            case DISCONNECT_SELECTED -> disconnectTargets(selectedTargets);
        }
        sendStateTo(player);
    }

    public void sendStateTo(ServerPlayer player) {
        WirelessEuNetwork.sendState(player, this);
    }

    public boolean isOwner(Player player) {
        return getOwnerUUID() != null && getOwnerUUID().equals(player.getUUID());
    }

    public BigInteger getStoredEu() {
        return energy().getStoredEu();
    }

    public BigInteger getCapacityEu() {
        return energy().getCapacityEu();
    }

    public BigInteger getConnectedLoadEuPerTick() {
        BigInteger load = BigInteger.ZERO;
        for (WirelessTarget target : connections.values()) {
            load = load.add(BigInteger.valueOf(target.voltage()).multiply(BigInteger.valueOf(target.amperage())));
        }
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            load = load.add(BigInteger.valueOf(target.voltage()).multiply(BigInteger.valueOf(target.amperage())));
        }
        return load;
    }

    public static boolean isPotentialLinkTarget(Level level, BlockPos targetPos) {
        if (level == null || !level.hasChunkAt(targetPos) || isExternalWirelessRelay(level, targetPos)) {
            return false;
        }
        MetaMachine machine = MetaMachine.getMachine(level, targetPos);
        if (!(machine instanceof ITieredMachine)
                || machine instanceof MultiblockControllerMachine
                || machine instanceof WirelessEuBroadcasterMachine) {
            return false;
        }
        return resolveReceiver(level, targetPos) != null;
    }

    public BigInteger getInputPerSecond() {
        return inputPerSecond;
    }

    public BigInteger getFillTimeSeconds() {
        BigInteger remaining = getCapacityEu().subtract(getStoredEu());
        if (remaining.signum() <= 0) {
            return BigInteger.ZERO;
        }
        if (inputPerSecond.signum() <= 0) {
            return NO_INPUT_SECONDS;
        }
        return remaining.add(inputPerSecond).subtract(BigInteger.ONE).divide(inputPerSecond);
    }

    public BigInteger getDepletionTimeSeconds() {
        BigInteger outputEuPerTick = getConnectedLoadEuPerTick();
        if (bufferIncreasing || outputEuPerTick.signum() <= 0) {
            return INFINITE_SECONDS;
        }
        if (getStoredEu().signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger outputPerSecond = outputEuPerTick.multiply(BigInteger.valueOf(20L));
        return getStoredEu().add(outputPerSecond).subtract(BigInteger.ONE).divide(outputPerSecond);
    }

    public List<WirelessTarget> getScanResults() {
        return List.copyOf(scanResults);
    }

    public List<WirelessTarget> getConnections() {
        return List.copyOf(connections.values());
    }

    public List<WirelessRemoteTarget> getRemoteConnections() {
        return List.copyOf(remoteConnections.values());
    }

    public List<WirelessEuNetwork.HigherBroadcasterInfo> getHigherBroadcasters(ServerPlayer player) {
        return findHigherBroadcasters().stream()
                .filter(machine -> machine.isOwner(player))
                .map(machine -> new WirelessEuNetwork.HigherBroadcasterInfo(machine.getPos(), machine.getTier(),
                        (long) machine.getPos().distSqr(getPos())))
                .toList();
    }

    private void migrateToHigherBroadcaster(ServerPlayer player,
                                             Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
        WirelessEuNetwork.TargetReference selected = selectedTargets.isEmpty() ? null
                : selectedTargets.iterator().next();
        WirelessEuBroadcasterMachine destination = selected == null || getLevel() == null
                || !getLevel().dimension().location().equals(selected.dimension()) ? null
                : findHigherBroadcasters().stream().filter(machine -> machine.getPos().equals(selected.pos()))
                .findFirst().orElse(null);
        if (destination == null) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_not_found"));
            return;
        }
        if (!destination.isOwner(player)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_owner"));
            return;
        }
        if (crossDimensionUnlocked && !destination.crossDimensionUnlocked) {
            WirelessEuNetwork.sendConnectionRejected(player, getPos(),
                    WirelessEuNetwork.ConnectionRejection.MIGRATION_WIRELESS_UPGRADE_REQUIRED);
            return;
        }
        BigInteger stored = getStoredEu();
        BigInteger free = destination.getCapacityEu().subtract(destination.getStoredEu());
        if (stored.compareTo(free) > 0) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_capacity"));
            return;
        }
        destination.connections.putAll(connections);
        destination.remoteConnections.putAll(remoteConnections);
        destination.upgradeCircuits.addAll(copyCircuits());
        destination.crossDimensionUnlocked |= crossDimensionUnlocked;
        if (stored.signum() > 0) {
            destination.energy().changeEnergy(stored);
            energy().changeEnergy(stored.negate());
        }
        connections.clear();
        remoteConnections.clear();
        upgradeCircuits.clear();
        crossDimensionUnlocked = false;
        markDirty();
        destination.markDirty();
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_success"));
    }

    private List<ItemStack> copyCircuits() {
        List<ItemStack> copies = new ArrayList<>(upgradeCircuits.size());
        for (ItemStack stack : upgradeCircuits) {
            copies.add(stack.copy());
        }
        return copies;
    }

    private List<WirelessEuBroadcasterMachine> findHigherBroadcasters() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) return List.of();
        List<WirelessEuBroadcasterMachine> result = new ArrayList<>();
        int minChunkX = Math.floorDiv(getPos().getX() - RANGE, 16);
        int maxChunkX = Math.floorDiv(getPos().getX() + RANGE, 16);
        int minChunkZ = Math.floorDiv(getPos().getZ() - RANGE, 16);
        int maxChunkZ = Math.floorDiv(getPos().getZ() + RANGE, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) continue;
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                MetaMachine machine = MetaMachine.getMachine(serverLevel, blockEntity.getBlockPos());
                if (machine instanceof WirelessEuBroadcasterMachine candidate && candidate != this
                        && candidate.getTier() > getTier() && isInRange(candidate.getPos())) {
                    result.add(candidate);
                }
            }
        }
        result.sort(Comparator.comparingDouble(machine -> machine.getPos().distSqr(getPos())));
        return result;
    }

    public long getVoltage() {
        return GTValues.V[getTier()];
    }

    void recordInput(BigInteger acceptedEnergy) {
        pendingAmplifiedInput = pendingAmplifiedInput.add(acceptedEnergy);
    }

    public boolean handleBindingCardUse(ServerPlayer player, ItemStack card) {
        if (!isOwner(player)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_card_owner"));
            return false;
        }
        ResourceLocation dimension = getLevel() == null ? null : getLevel().dimension().location();
        if (dimension == null) {
            return false;
        }
        EnergyBindingCardItem.Binding binding = EnergyBindingCardItem.getBinding(card);
        WirelessEuCommands.rememberTeleport(player, dimension, getPos());
        if (binding != null && binding.dimension().equals(dimension) && binding.pos().equals(getPos())) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.binding_card_bound")
                    .append(locationText(WirelessEuCommands.teleportCommand(getPos()),
                            "chat.wireless_eu.broadcaster_location_hover", getPos())));
            return true;
        }
        boolean rebound = binding != null;
        EnergyBindingCardItem.bind(card, dimension, getPos());
        player.sendSystemMessage(Component.translatable(rebound
                        ? "chat.wireless_eu.binding_card_rebound" : "chat.wireless_eu.binding_card_bound")
                .append(locationText(WirelessEuCommands.teleportCommand(getPos()),
                        "chat.wireless_eu.broadcaster_location_hover", getPos())));
        return true;
    }

    /**
     * Connects one target of a player carrying a binding card. The connection is written exactly like a manual one,
     * so the GUI, the load display and the migration keep treating it the same; the returned status tells the caller
     * what to message and when to keep the target queued.
     */
    public AutoConnectResult autoConnectTarget(Player player, Level targetLevel, BlockPos targetPos) {
        if (!isOwner(player)) {
            return new AutoConnectResult(AutoConnectStatus.NOT_OWNER, null);
        }
        if (targetLevel == null || !isLoaded(targetLevel, targetPos)) {
            return new AutoConnectResult(AutoConnectStatus.NOT_TARGET, null);
        }
        boolean sameLevel = getLevel() != null && targetLevel.dimension().equals(getLevel().dimension());
        boolean inRange = sameLevel && isInRange(targetPos);
        if (!inRange && !crossDimensionUnlocked) {
            return new AutoConnectResult(AutoConnectStatus.NEEDS_UPGRADE, null);
        }
        ResourceLocation dimension = targetLevel.dimension().location();
        if (sameLevel && connections.containsKey(targetPos.asLong())
                || remoteConnections.containsKey(new RemoteTargetKey(dimension, targetPos.asLong()))) {
            return new AutoConnectResult(AutoConnectStatus.ALREADY, null);
        }
        if (!isPotentialLinkTarget(targetLevel, targetPos)) {
            return new AutoConnectResult(AutoConnectStatus.NOT_TARGET, null);
        }
        if (findConflictingBroadcaster() != null) {
            return new AutoConnectResult(AutoConnectStatus.BROADCASTER_LIMIT, null);
        }
        WirelessTarget target = createTarget(targetLevel, targetPos, false);
        if (target == null) {
            return new AutoConnectResult(AutoConnectStatus.TIER_TOO_HIGH, null);
        }
        if (inRange) {
            connections.put(target.pos().asLong(), target);
        } else {
            remoteConnections.put(new RemoteTargetKey(dimension, target.pos().asLong()),
                    new WirelessRemoteTarget(dimension, target.pos(), target.name(), target.kind(), target.tier(),
                            target.voltage(), target.amperage()));
        }
        markDirty();
        syncOpenMenus();
        return new AutoConnectResult(AutoConnectStatus.CONNECTED, target.name());
    }

    public enum AutoConnectStatus {
        CONNECTED,
        ALREADY,
        NEEDS_UPGRADE,
        TIER_TOO_HIGH,
        NOT_TARGET,
        NOT_OWNER,
        BROADCASTER_LIMIT
    }

    public static final class AutoConnectResult {
        public final AutoConnectStatus status;
        public final String targetName;

        AutoConnectResult(AutoConnectStatus status, String targetName) {
            this.status = status;
            this.targetName = targetName;
        }
    }

    /**
     * Battery buffers are valid wireless output targets, but their stored EU must not be
     * allowed to come back through the GT energy network and get amplified again.
     *
     * GTCEu does not pass the original producer to IEnergyContainer#acceptEnergyFromNetwork.
     * The input side therefore identifies the adjacent cable network and rejects input from
     * any active battery-buffer output on that network. This leaves the broadcaster's wireless
     * output path unchanged while closing the duplication loop.
     */
    boolean isInputBlocked(Direction side) {
        if (side == null || getLevel() == null || getLevel().isClientSide) {
            return false;
        }

        long tick = getOffsetTimer();
        if (tick != inputFirewallSampleTick) {
            inputFirewallSampleTick = tick;
            inputFirewallCache.clear();
        }
        return inputFirewallCache.computeIfAbsent(side, this::findBatteryBufferOutput);
    }

    private boolean findBatteryBufferOutput(Direction broadcasterSide) {
        Level level = getLevel();
        BlockPos adjacentPos = getPos().relative(broadcasterSide);
        MetaMachine adjacentMachine = MetaMachine.getMachine(level, adjacentPos);
        if (adjacentMachine instanceof BatteryBufferMachine batteryBuffer) {
            Direction batteryOutputSide = broadcasterSide.getOpposite();
            return batteryBuffer.getFrontFacing() == batteryOutputSide
                    && batteryBuffer.energyContainer.outputsEnergy(batteryOutputSide);
        }

        if (!(level instanceof ServerLevel serverLevel)
                || !(level.getBlockEntity(adjacentPos) instanceof CableBlockEntity)) {
            return false;
        }

        EnergyNet energyNet = LevelEnergyNet.getOrCreate(serverLevel).getNetFromPos(adjacentPos);
        if (energyNet == null) {
            return false;
        }
        List<EnergyRoutePath> routes = energyNet.getNetData(adjacentPos);
        if (routes == null) {
            return false;
        }
        for (EnergyRoutePath route : routes) {
            BlockPos pipePos = route.getTargetPipePos();
            Direction routeFacing = route.getTargetFacing();
            if (isBatteryBufferOutputAt(serverLevel, pipePos.relative(routeFacing), routeFacing.getOpposite())
                    || isBatteryBufferOutputAt(serverLevel, pipePos.relative(routeFacing.getOpposite()), routeFacing)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBatteryBufferOutputAt(ServerLevel level, BlockPos position, Direction outputSide) {
        if (!isLoaded(position)) {
            return false;
        }
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (!(machine instanceof BatteryBufferMachine batteryBuffer)) {
            return false;
        }
        return batteryBuffer.getFrontFacing() == outputSide
                && batteryBuffer.energyContainer.outputsEnergy(outputSide);
    }

    private void tickBroadcaster() {
        long tick = getOffsetTimer();
        updateInputRate(tick);
        broadcastEnergy();
        if (tick % 20L == 0L) {
            syncOpenMenus();
        }
    }

    private void updateInputRate(long tick) {
        if (lastInputSampleTick < 0L) {
            lastInputSampleTick = tick;
            lastEnergySample = getStoredEu();
            return;
        }
        long elapsedTicks = tick - lastInputSampleTick;
        if (elapsedTicks >= 20L) {
            inputPerSecond = pendingAmplifiedInput.multiply(BigInteger.valueOf(20L))
                    .divide(BigInteger.valueOf(elapsedTicks));
            pendingAmplifiedInput = BigInteger.ZERO;
            lastInputSampleTick = tick;
            BigInteger storedEnergy = getStoredEu();
            bufferIncreasing = storedEnergy.compareTo(lastEnergySample) > 0;
            lastEnergySample = storedEnergy;
        }
    }

    private void broadcastEnergy() {
        if (connections.isEmpty() && remoteConnections.isEmpty()) {
            return;
        }
        AmplifyingLaserContainer container = energy();
        if (container.getStoredEu().signum() <= 0) {
            return;
        }

        List<BroadcastTarget> targets = new ArrayList<>(connections.size() + remoteConnections.size());
        for (WirelessTarget target : connections.values()) {
            targets.add(new BroadcastTarget(target, null));
        }
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            targets.add(new BroadcastTarget(null, target));
        }
        BigInteger remainingEu = container.getStoredEu();
        int startingIndex = Math.floorMod(roundRobinIndex, targets.size());
        boolean changedConnections = false;

        for (int offset = 0; offset < targets.size() && remainingEu.signum() > 0; offset++) {
            BroadcastTarget target = targets.get((startingIndex + offset) % targets.size());
            Level targetLevel = target.remote() == null ? getLevel() : getServerLevel(target.remote().dimension());
            BlockPos targetPos = target.pos();
            Receiver receiver = resolveReceiver(targetLevel, targetPos);
            long targetVoltage = receiver == null ? 0L : receiver.container().getInputVoltage();
            if (receiver == null || targetVoltage <= 0L || targetVoltage > getVoltage()) {
                if (isLoaded(targetLevel, targetPos)) {
                    if (target.remote() == null) {
                        connections.remove(targetPos.asLong());
                    } else {
                        remoteConnections.remove(new RemoteTargetKey(target.remote().dimension(),
                                targetPos.asLong()));
                    }
                    changedConnections = true;
                }
                continue;
            }

            long availableAmperage = remainingEu.divide(BigInteger.valueOf(targetVoltage))
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            if (availableAmperage <= 0L) {
                break;
            }
            long attemptedAmperage = Math.min(target.amperage(), availableAmperage);
            long acceptedAmperage = receiver.container().acceptEnergyFromNetwork(receiver.side(), targetVoltage,
                    attemptedAmperage);
            if (acceptedAmperage > 0) {
                BigInteger transferredEu = BigInteger.valueOf(acceptedAmperage)
                        .multiply(BigInteger.valueOf(targetVoltage));
                container.changeEnergy(transferredEu.negate());
                remainingEu = remainingEu.subtract(transferredEu);
            }
        }

        roundRobinIndex = (startingIndex + 1) % Math.max(1, targets.size());
        if (changedConnections) {
            markDirty();
        }
    }

    private void syncOpenMenus() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof WirelessBroadcasterMenu menu && menu.getMachine() == this) {
                sendStateTo(player);
            }
        }
    }

    private void scanTargets() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        List<WirelessTarget> found = new ArrayList<>();
        int minChunkX = Math.floorDiv(getPos().getX() - RANGE, 16);
        int maxChunkX = Math.floorDiv(getPos().getX() + RANGE, 16);
        int minChunkZ = Math.floorDiv(getPos().getZ() - RANGE, 16);
        int maxChunkZ = Math.floorDiv(getPos().getZ() + RANGE, 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    WirelessTarget target = createTarget(blockEntity.getBlockPos());
                    if (target != null) {
                        found.add(target);
                    }
                }
            }
        }

        found.sort(Comparator.comparingDouble((WirelessTarget target) -> target.pos().distSqr(getPos()))
                .thenComparing(WirelessTarget::name));
        scanResults = List.copyOf(found);
    }

    private void connectTargets(Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
        if (selectedTargets.isEmpty() || getLevel() == null) {
            return;
        }

        for (WirelessTarget target : scanResults) {
            long position = target.pos().asLong();
            WirelessTarget refreshedTarget = createTarget(target.pos());
            WirelessEuNetwork.TargetReference reference = new WirelessEuNetwork.TargetReference(
                    getLevel().dimension().location(), target.pos());
            if (selectedTargets.contains(reference) && !connections.containsKey(position) && refreshedTarget != null) {
                connections.put(refreshedTarget.pos().asLong(), refreshedTarget);
            }
        }
        markDirty();
    }

    private BlockPos findConflictingBroadcaster() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) {
            return null;
        }

        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        int minChunkX = Math.floorDiv(getPos().getX() - RANGE, 16);
        int maxChunkX = Math.floorDiv(getPos().getX() + RANGE, 16);
        int minChunkZ = Math.floorDiv(getPos().getZ() - RANGE, 16);
        int maxChunkZ = Math.floorDiv(getPos().getZ() + RANGE, 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos position = blockEntity.getBlockPos();
                    if (!isInRange(position)) {
                        continue;
                    }
                    MetaMachine machine = MetaMachine.getMachine(serverLevel, position);
                    if (machine instanceof WirelessEuBroadcasterMachine broadcaster && broadcaster != this
                            && broadcaster.getTier() == getTier()) {
                        double distance = position.distSqr(getPos());
                        if (distance < closestDistance) {
                            closest = position.immutable();
                            closestDistance = distance;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private void sendConflictingBroadcasterMessage(ServerPlayer player, BlockPos position) {
        if (getLevel() != null) {
            WirelessEuCommands.rememberTeleport(player, getLevel().dimension().location(), position);
        }
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.broadcaster_conflict")
                .append(locationText(WirelessEuCommands.teleportCommand(position),
                        "chat.wireless_eu.broadcaster_location_hover", position)));
    }

    private void sendTargetLocation(ServerPlayer player,
                                    Collection<WirelessEuNetwork.TargetReference> targets) {
        for (WirelessEuNetwork.TargetReference reference : targets) {
            ServerLevel targetLevel = getServerLevel(player, reference.dimension());
            BlockPos position = reference.pos();
            if (targetLevel == null || !targetLevel.hasChunkAt(position)) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.target_unavailable"));
                continue;
            }
            if (!isKnownTarget(reference) || createTarget(targetLevel, position, false) == null) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.target_not_found"));
                continue;
            }
            WirelessEuCommands.rememberTeleport(player, reference.dimension(), position);
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.target_location")
                    .append(locationText(WirelessEuCommands.teleportTargetCommand(reference.dimension(), position),
                            "chat.wireless_eu.target_location_hover", position)));
        }
    }

    private void sendHigherBroadcasterLocation(ServerPlayer player,
                                               Collection<WirelessEuNetwork.TargetReference> targets) {
        if (targets.isEmpty()) {
            return;
        }
        WirelessEuNetwork.TargetReference requested = targets.iterator().next();
        WirelessEuBroadcasterMachine target = findHigherBroadcasters().stream()
                .filter(machine -> machine.getPos().equals(requested.pos()))
                .findFirst().orElse(null);
        if (target == null) {
            return;
        }
        BlockPos position = target.getPos();
        WirelessEuCommands.rememberTeleport(player, requested.dimension(), position);
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.broadcaster_location")
                .append(locationText(WirelessEuCommands.teleportCommand(position),
                        "chat.wireless_eu.broadcaster_location_hover", position)));
    }

    public static Component locationText(String command, String hoverKey, BlockPos position) {
        return Component.literal("[" + position.getX() + ", " + position.getY() + ", " + position.getZ() + "]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(hoverKey))));
    }

    private boolean isKnownTarget(WirelessEuNetwork.TargetReference reference) {
        if (getLevel() == null) {
            return false;
        }
        if (getLevel().dimension().location().equals(reference.dimension())) {
            long rawPosition = reference.pos().asLong();
            return connections.containsKey(rawPosition)
                    || remoteConnections.containsKey(new RemoteTargetKey(reference.dimension(), rawPosition))
                    || scanResults.stream().anyMatch(target -> target.pos().equals(reference.pos()));
        }
        return remoteConnections.containsKey(new RemoteTargetKey(reference.dimension(), reference.pos().asLong()));
    }

    private void disconnectTargets(Collection<WirelessEuNetwork.TargetReference> selectedTargets) {
        boolean changed = false;
        ResourceLocation currentDimension = getLevel() == null ? null : getLevel().dimension().location();
        for (WirelessEuNetwork.TargetReference target : selectedTargets) {
            if (currentDimension != null && currentDimension.equals(target.dimension())) {
                changed |= connections.remove(target.pos().asLong()) != null;
            }
            changed |= remoteConnections.remove(new RemoteTargetKey(target.dimension(), target.pos().asLong())) != null;
        }
        if (changed) {
            markDirty();
        }
    }

    private WirelessTarget createTarget(BlockPos targetPos) {
        return createTarget(getLevel(), targetPos, true);
    }

    private WirelessTarget createTarget(Level targetLevel, BlockPos targetPos, boolean requireRange) {
        if (targetLevel == null || (targetLevel.dimension().equals(getLevel().dimension()) && targetPos.equals(getPos()))
                || !isLoaded(targetLevel, targetPos) || isExternalWirelessRelay(targetLevel, targetPos)) {
            return null;
        }
        if (requireRange && (!targetLevel.dimension().equals(getLevel().dimension()) || !isInRange(targetPos))) {
            return null;
        }

        MetaMachine machine = MetaMachine.getMachine(targetLevel, targetPos);
        if (!(machine instanceof ITieredMachine tieredMachine)
                || tieredMachine.getTier() > getTier()
                || machine instanceof MultiblockControllerMachine
                || machine instanceof WirelessEuBroadcasterMachine) {
            return null;
        }

        Receiver receiver = resolveReceiver(targetLevel, targetPos);
        long targetVoltage = receiver == null ? 0L : receiver.container().getInputVoltage();
        if (targetVoltage <= 0L || targetVoltage > getVoltage()) {
            return null;
        }

        TargetKind kind;
        if (receiver.laser()) {
            kind = TargetKind.LASER_STORAGE;
        } else if (machine instanceof com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine) {
            kind = TargetKind.ENERGY_STORAGE;
        } else {
            kind = TargetKind.SINGLEBLOCK_MACHINE;
        }
        String name = machine.getDefinition().getDescriptionId();
        return new WirelessTarget(targetPos, name, kind, tieredMachine.getTier(), targetVoltage,
                Math.max(1L, receiver.container().getInputAmperage()));
    }

    /**
     * Sky of Grind ships gtmthings wireless energy/laser hatches. Feeding them would let a player chain
     * broadcaster -> wireless hatch -> another broadcaster and multiply EU through the 2.5x input amplifier.
     */
    private static boolean isExternalWirelessRelay(Level level, BlockPos position) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock());
        return "gtmthings".equals(id.getNamespace()) && id.getPath().contains("wireless");
    }

    private static Receiver resolveReceiver(Level level, BlockPos targetPos) {
        if (level == null || !isLoaded(level, targetPos)) {
            return null;
        }

        for (Direction side : Direction.values()) {
            ILaserContainer laser = GTCapabilityHelper.getLaser(level, targetPos, side);
            if (laser != null && laser.inputsEnergy(side)) {
                return new Receiver(laser, side, true);
            }
            IEnergyContainer energy = GTCapabilityHelper.getEnergyContainer(level, targetPos, side);
            if (energy != null && energy != IEnergyContainer.DEFAULT && energy.inputsEnergy(side)) {
                return new Receiver(energy, side, false);
            }
        }
        return null;
    }

    private boolean isLoaded(BlockPos pos) {
        return isLoaded(getLevel(), pos);
    }

    private static boolean isLoaded(Level level, BlockPos pos) {
        return level != null && level.hasChunkAt(pos);
    }

    private boolean isInRange(BlockPos pos) {
        BlockPos origin = getPos();
        return Math.abs(pos.getX() - origin.getX()) <= RANGE
                && Math.abs(pos.getY() - origin.getY()) <= RANGE
                && Math.abs(pos.getZ() - origin.getZ()) <= RANGE;
    }

    private ServerLevel getServerLevel(ResourceLocation dimension) {
        return getLevel() instanceof ServerLevel serverLevel
                ? serverLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension)) : null;
    }

    private ServerLevel getServerLevel(ServerPlayer player, ResourceLocation dimension) {
        return player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private int installedCircuitCount() {
        return upgradeCircuits.stream().mapToInt(ItemStack::getCount).sum();
    }

    private void addInstalledCircuitStack(ItemStack inserted) {
        for (ItemStack installed : upgradeCircuits) {
            if (ItemStack.isSameItemSameTags(installed, inserted)) {
                installed.grow(inserted.getCount());
                return;
            }
        }
        upgradeCircuits.add(inserted);
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

    public static String formatDuration(BigInteger seconds) {
        if (seconds.equals(INFINITE_SECONDS)) {
            return Component.translatable("gui.wireless_eu.duration_infinite").getString();
        }
        if (seconds.signum() < 0) {
            return Component.translatable("gui.wireless_eu.duration_none").getString();
        }
        if (seconds.signum() == 0) {
            return Component.translatable("gui.wireless_eu.duration_zero").getString();
        }
        BigInteger hours = seconds.divide(BigInteger.valueOf(3600L));
        long minutes = seconds.remainder(BigInteger.valueOf(3600L)).longValue() / 60L;
        long remainingSeconds = seconds.remainder(BigInteger.valueOf(60L)).longValue();
        if (hours.signum() > 0) {
            return Component.translatable("gui.wireless_eu.duration_hours", formatCompactEu(hours), minutes).getString();
        }
        if (minutes > 0L) {
            return Component.translatable("gui.wireless_eu.duration_minutes", minutes, remainingSeconds).getString();
        }
        return Component.translatable("gui.wireless_eu.duration_seconds", remainingSeconds).getString();
    }

    private record BroadcastTarget(WirelessTarget local, WirelessRemoteTarget remote) {
        private BlockPos pos() {
            return local == null ? remote.pos() : local.pos();
        }

        private long amperage() {
            return local == null ? remote.amperage() : local.amperage();
        }
    }

    private record RemoteTargetKey(ResourceLocation dimension, long pos) {
    }

    private record Receiver(IEnergyContainer container, Direction side, boolean laser) {
    }
}
