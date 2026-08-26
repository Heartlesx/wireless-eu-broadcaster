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
import com.gregtechceu.gtceu.api.machine.feature.ITieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.machine.electric.BatteryBufferMachine;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import com.gregtechceu.gtceu.common.pipelike.cable.LevelEnergyNet;
import com.wirelesseu.command.WirelessEuCommands;
import com.wirelesseu.item.LinkCardItem;
import com.wirelesseu.menu.WirelessBroadcasterMenu;
import com.wirelesseu.network.WirelessEuNetwork;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class WirelessEuBroadcasterMachine extends TieredEnergyMachine implements IUIMachine, IInteractedMachine {
    public static final int RANGE = 384;
    public static final int DEFAULT_OUTPUT_AMPERAGE = 65_536;
    public static final int UPGRADE_CIRCUIT_COUNT = 32;
    public static final int LINK_BATCH_SIZE = 32;
    // 256A at the machine voltage for 15 minutes: 256 * 20 ticks/s * 900 s.
    private static final long BUFFER_VOLTAGE_MULTIPLIER = 4_608_000L;
    private static final long INPUT_AMPERAGE_LIMIT = 256L;
    private static final long[] COMPACT_EU_DIVISORS = {
            1L, 1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000L, 1_000_000_000_000_000L
    };
    private static final String[] COMPACT_EU_SUFFIXES = {"", "K", "M", "G", "T", "P"};

    private final Map<Long, WirelessTarget> connections = new LinkedHashMap<>();
    private List<WirelessTarget> scanResults = List.of();
    private int configuredOutputAmperage = DEFAULT_OUTPUT_AMPERAGE;
    private int roundRobinIndex;
    private long pendingAmplifiedInput;
    private long inputPerSecond;
    private long lastInputSampleTick = -1L;
    private long lastEnergySample = -1L;
    private boolean bufferIncreasing;
    private TickableSubscription broadcasterSubscription;
    private long inputFirewallSampleTick = Long.MIN_VALUE;
    private final EnumMap<Direction, Boolean> inputFirewallCache = new EnumMap<>(Direction.class);
    private boolean crossDimensionUnlocked;
    private final List<ItemStack> upgradeCircuits = new ArrayList<>();
    private final Map<RemoteTargetKey, WirelessRemoteTarget> remoteConnections = new LinkedHashMap<>();

    public WirelessEuBroadcasterMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier);
    }

    @Override
    protected NotifiableEnergyContainer createEnergyContainer(Object... ignored) {
        long voltage = GTValues.V[getTier()];
        return new AmplifyingEnergyContainer(this, voltage * BUFFER_VOLTAGE_MULTIPLIER, voltage,
                INPUT_AMPERAGE_LIMIT);
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
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putInt("ConfiguredOutputAmperage", configuredOutputAmperage);
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
        configuredOutputAmperage = DEFAULT_OUTPUT_AMPERAGE;
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

    public boolean handleLinkCardUse(ServerPlayer player, ItemStack card, boolean sneaking) {
        if (!isOwner(player)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_owner"));
            return true;
        }
        if (!crossDimensionUnlocked) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_not_upgraded"));
            return true;
        }
        if (sneaking) {
            LinkCardItem.Binding existing = LinkCardItem.getBinding(card);
            if (existing != null && !isSameBinding(existing)) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_bound_other"));
                return true;
            }
            LinkCardItem.bind(card, getLevel().dimension().location(), getPos());
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_bound"));
            return true;
        }

        LinkCardItem.Binding binding = LinkCardItem.getBinding(card);
        if (binding == null) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_unbound"));
            return true;
        }
        if (!isSameBinding(binding)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_bound_other"));
            return true;
        }
        commitLinkCard(player, card);
        return true;
    }

    public void collectLinkTarget(ServerPlayer player, ItemStack card, ServerLevel targetLevel, BlockPos targetPos) {
        if (!isOwner(player)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_owner"));
            return;
        }
        if (!crossDimensionUnlocked) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_not_upgraded"));
            return;
        }
        LinkCardItem.Binding binding = LinkCardItem.getBinding(card);
        if (binding == null || !isSameBinding(binding)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_card_bound_other"));
            return;
        }
        boolean sameDimension = targetLevel.dimension().equals(getLevel().dimension());
        if (sameDimension && isInRange(targetPos)) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_in_range"));
            return;
        }
        List<LinkCardItem.PendingTarget> pending = LinkCardItem.getPendingTargets(card);
        if (pending.size() >= LINK_BATCH_SIZE) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_limit"));
            return;
        }
        ResourceLocation dimension = targetLevel.dimension().location();
        RemoteTargetKey key = new RemoteTargetKey(dimension, targetPos.asLong());
        if (remoteConnections.containsKey(key) || pending.stream()
                .anyMatch(entry -> entry.dimension().equals(dimension) && entry.pos().equals(targetPos))) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_duplicate"));
            return;
        }
        if (createTarget(targetLevel, targetPos, false) == null) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_invalid"));
            return;
        }
        LinkCardItem.addPendingTarget(card, dimension, targetPos);
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_added",
                LinkCardItem.getPendingTargets(card).size(), LINK_BATCH_SIZE));
    }

    private void commitLinkCard(ServerPlayer player, ItemStack card) {
        List<LinkCardItem.PendingTarget> pending = LinkCardItem.getPendingTargets(card);
        if (pending.isEmpty()) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_commit_empty"));
            return;
        }

        List<WirelessRemoteTarget> additions = new ArrayList<>();
        long newLoad = 0L;
        for (LinkCardItem.PendingTarget entry : pending) {
            ServerLevel targetLevel = getServerLevel(player, entry.dimension());
            if (targetLevel == null || !targetLevel.hasChunkAt(entry.pos())) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_unloaded"));
                return;
            }
            boolean sameDimension = targetLevel.dimension().equals(getLevel().dimension());
            if (sameDimension && isInRange(entry.pos())) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_in_range"));
                return;
            }
            WirelessTarget target = createTarget(targetLevel, entry.pos(), false);
            if (target == null) {
                player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_target_invalid"));
                return;
            }
            RemoteTargetKey key = new RemoteTargetKey(entry.dimension(), entry.pos().asLong());
            if (remoteConnections.containsKey(key) || additions.stream().anyMatch(existing ->
                    existing.dimension().equals(entry.dimension()) && existing.pos().equals(entry.pos()))) {
                continue;
            }
            WirelessRemoteTarget remote = new WirelessRemoteTarget(entry.dimension(), entry.pos(), target.name(),
                    target.kind(), target.tier(), target.voltage(), target.amperage());
            additions.add(remote);
            newLoad = saturatingAdd(newLoad, saturatingMultiply(remote.voltage(), remote.amperage()));
        }
        long outputLimitEuPerTick = saturatingMultiply(configuredOutputAmperage, getVoltage());
        if (newLoad > outputLimitEuPerTick - Math.min(outputLimitEuPerTick, getConnectedLoadEuPerTick())) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_commit_capacity"));
            return;
        }
        for (WirelessRemoteTarget remote : additions) {
            remoteConnections.put(new RemoteTargetKey(remote.dimension(), remote.pos().asLong()), remote);
        }
        LinkCardItem.clearPendingTargets(card);
        markDirty();
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.link_commit_success", additions.size()));
    }

    private boolean isSameBinding(LinkCardItem.Binding binding) {
        return getLevel() != null && getLevel().dimension().location().equals(binding.dimension())
                && getPos().equals(binding.pos());
    }

    private ServerLevel getServerLevel(ServerPlayer player, ResourceLocation dimension) {
        return player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private ServerLevel getServerLevel(ResourceLocation dimension) {
        return getLevel() instanceof ServerLevel serverLevel
                ? serverLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension)) : null;
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
                             int requestedAmperage, Collection<Long> selectedPositions) {
        if (action == WirelessEuNetwork.ServerAction.LOCATE_TARGET) {
            sendTargetLocation(player, selectedPositions);
            sendStateTo(player);
            return;
        }
        if (action == WirelessEuNetwork.ServerAction.LOCATE_BROADCASTER) {
            sendHigherBroadcasterLocation(player, selectedPositions);
            sendStateTo(player);
            return;
        }
        if (!isOwner(player)) {
            sendStateTo(player);
            return;
        }

        switch (action) {
            case SCAN -> scanTargets();
            case MIGRATE_TO_HIGHER -> migrateToHigherBroadcaster(player, selectedPositions);
            case SET_OUTPUT_AMPERAGE -> {
                configuredOutputAmperage = DEFAULT_OUTPUT_AMPERAGE;
            }
            case CONNECT_SELECTED -> {
                if (!selectedPositions.isEmpty()) {
                    BlockPos conflictingBroadcaster = findConflictingBroadcaster();
                    if (conflictingBroadcaster != null) {
                        WirelessEuNetwork.sendConnectionRejected(player, getPos(),
                                WirelessEuNetwork.ConnectionRejection.BROADCASTER_LIMIT);
                        sendConflictingBroadcasterMessage(player, conflictingBroadcaster);
                    } else {
                        WirelessEuNetwork.ConnectionRejection rejection = connectTargets(selectedPositions);
                        if (rejection != null) {
                            WirelessEuNetwork.sendConnectionRejected(player, getPos(), rejection);
                        }
                    }
                }
            }
            case DISCONNECT_SELECTED -> disconnectTargets(selectedPositions);
        }
        sendStateTo(player);
    }

    public void sendStateTo(ServerPlayer player) {
        WirelessEuNetwork.sendState(player, this);
    }

    public boolean isOwner(Player player) {
        return getOwnerUUID() != null && getOwnerUUID().equals(player.getUUID());
    }

    public int getConfiguredOutputAmperage() {
        return configuredOutputAmperage;
    }

    public long getConnectedLoadEuPerTick() {
        long load = 0L;
        for (WirelessTarget target : connections.values()) {
            load = saturatingAdd(load, saturatingMultiply(target.voltage(), target.amperage()));
        }
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            load = saturatingAdd(load, saturatingMultiply(target.voltage(), target.amperage()));
        }
        return load;
    }

    public static boolean isPotentialLinkTarget(Level level, BlockPos targetPos) {
        if (level == null || !level.hasChunkAt(targetPos)) {
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

    public long getInputPerSecond() {
        return inputPerSecond;
    }

    public long getFillTimeSeconds() {
        long remaining = energyContainer.getEnergyCapacity() - energyContainer.getEnergyStored();
        if (remaining <= 0) {
            return 0;
        }
        if (inputPerSecond <= 0) {
            return -1;
        }
        return (remaining + inputPerSecond - 1) / inputPerSecond;
    }

    public long getDepletionTimeSeconds() {
        long outputEuPerTick = Math.min(saturatingMultiply(configuredOutputAmperage, getVoltage()),
                getConnectedLoadEuPerTick());
        if (bufferIncreasing || outputEuPerTick <= 0 || energyContainer.getEnergyStored() <= 0) {
            return 0L;
        }
        long outputPerSecond = saturatingMultiply(outputEuPerTick, 20L);
        return (energyContainer.getEnergyStored() + outputPerSecond - 1L) / outputPerSecond;
    }

    public List<WirelessTarget> getScanResults() {
        return List.copyOf(scanResults);
    }

    public List<WirelessTarget> getConnections() {
        return List.copyOf(connections.values());
    }

    public List<WirelessEuNetwork.HigherBroadcasterInfo> getHigherBroadcasters() {
        return findHigherBroadcasters().stream()
                .map(machine -> new WirelessEuNetwork.HigherBroadcasterInfo(machine.getPos(), machine.getTier(),
                        (long) machine.getPos().distSqr(getPos())))
                .toList();
    }

    private void migrateToHigherBroadcaster(ServerPlayer player, Collection<Long> selectedPositions) {
        WirelessEuBroadcasterMachine destination = selectedPositions.isEmpty() ? null
                : findHigherBroadcasters().stream().filter(machine -> machine.getPos().asLong() == selectedPositions.iterator().next())
                .findFirst().orElse(null);
        if (destination == null) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_not_found"));
            return;
        }
        long stored = energyContainer.getEnergyStored();
        long free = destination.energyContainer.getEnergyCapacity() - destination.energyContainer.getEnergyStored();
        if (stored > free) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_capacity"));
            return;
        }
        if (!remoteConnections.isEmpty() && !destination.crossDimensionUnlocked) {
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_remote_upgrade"));
            return;
        }
        destination.connections.putAll(connections);
        destination.remoteConnections.putAll(remoteConnections);
        if (stored > 0L) {
            destination.energyContainer.changeEnergy(stored);
            energyContainer.changeEnergy(-stored);
        }
        connections.clear();
        remoteConnections.clear();
        markDirty();
        destination.markDirty();
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.migration_success"));
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
                    double candidateDistance = candidate.getPos().distSqr(getPos());
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

    void recordAmplifiedInput(long acceptedEnergy) {
        pendingAmplifiedInput += acceptedEnergy;
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
    boolean isBatteryBufferInputBlocked(Direction side) {
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
            lastEnergySample = energyContainer.getEnergyStored();
            return;
        }
        long elapsedTicks = tick - lastInputSampleTick;
        if (elapsedTicks >= 20L) {
            inputPerSecond = pendingAmplifiedInput * 20L / elapsedTicks;
            pendingAmplifiedInput = 0L;
            lastInputSampleTick = tick;
            long storedEnergy = energyContainer.getEnergyStored();
            bufferIncreasing = storedEnergy > lastEnergySample;
            lastEnergySample = storedEnergy;
        }
    }

    private void broadcastEnergy() {
        if ((connections.isEmpty() && remoteConnections.isEmpty()) || energyContainer.getEnergyStored() <= 0L) {
            return;
        }

        List<BroadcastTarget> targets = new ArrayList<>(connections.size() + remoteConnections.size());
        for (WirelessTarget target : connections.values()) {
            targets.add(new BroadcastTarget(target, null));
        }
        for (WirelessRemoteTarget target : remoteConnections.values()) {
            targets.add(new BroadcastTarget(null, target));
        }
        long remainingEu = saturatingMultiply(configuredOutputAmperage, getVoltage());
        int startingIndex = Math.floorMod(roundRobinIndex, targets.size());
        boolean changedConnections = false;

        for (int offset = 0; offset < targets.size() && remainingEu > 0L; offset++) {
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

            long transferableEu = Math.min(remainingEu, energyContainer.getEnergyStored());
            long availableAmperage = transferableEu / targetVoltage;
            if (availableAmperage <= 0L) {
                break;
            }
            long attemptedAmperage = Math.min(target.amperage(), availableAmperage);
            long acceptedAmperage = receiver.container().acceptEnergyFromNetwork(receiver.side(), targetVoltage,
                    attemptedAmperage);
            if (acceptedAmperage > 0) {
                long transferredEu = saturatingMultiply(acceptedAmperage, targetVoltage);
                energyContainer.removeEnergy(transferredEu);
                remainingEu -= transferredEu;
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

    private WirelessEuNetwork.ConnectionRejection connectTargets(Collection<Long> selectedPositions) {
        if (selectedPositions.isEmpty()) {
            return null;
        }

        List<WirelessTarget> newConnections = new ArrayList<>();
        for (WirelessTarget target : scanResults) {
            long position = target.pos().asLong();
            WirelessTarget refreshedTarget = createTarget(target.pos());
            if (selectedPositions.contains(position) && !connections.containsKey(position) && refreshedTarget != null) {
                newConnections.add(refreshedTarget);
            }
        }

        long newLoadEuPerTick = 0L;
        for (WirelessTarget target : newConnections) {
            newLoadEuPerTick = saturatingAdd(newLoadEuPerTick,
                    saturatingMultiply(target.voltage(), target.amperage()));
        }
        if (newLoadEuPerTick == 0L) {
            return null;
        }
        long outputLimitEuPerTick = saturatingMultiply(configuredOutputAmperage, getVoltage());
        if (getConnectedLoadEuPerTick() > outputLimitEuPerTick - newLoadEuPerTick) {
            return WirelessEuNetwork.ConnectionRejection.OUTPUT_FULL;
        }

        for (WirelessTarget target : newConnections) {
            connections.put(target.pos().asLong(), target);
        }
        markDirty();
        return null;
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
        Component location = Component.literal("[" + position.getX() + ", " + position.getY() + ", "
                        + position.getZ() + "]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                WirelessEuCommands.teleportCommand(position)))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击传送到该广播器"))));
        player.sendSystemMessage(Component.literal("冲突范围内的广播器位置：").append(location));
    }

    private void sendTargetLocation(ServerPlayer player, Collection<Long> positions) {
        for (long rawPosition : positions) {
            BlockPos position = BlockPos.of(rawPosition);
            if (!isInRange(position) || !isLoaded(position)) {
                continue;
            }
            boolean known = connections.containsKey(rawPosition)
                    || scanResults.stream().anyMatch(target -> target.pos().equals(position));
            if (!known || createTarget(position) == null) {
                continue;
            }
            Component location = Component.literal("[" + position.getX() + ", " + position.getY() + ", "
                            + position.getZ() + "]")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    WirelessEuCommands.teleportTargetCommand(position)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("chat.wireless_eu.target_location_hover"))));
            player.sendSystemMessage(Component.translatable("chat.wireless_eu.target_location").append(location));
        }
    }

    private void sendHigherBroadcasterLocation(ServerPlayer player, Collection<Long> positions) {
        if (positions.isEmpty()) {
            return;
        }
        long requestedPosition = positions.iterator().next();
        WirelessEuBroadcasterMachine target = findHigherBroadcasters().stream()
                .filter(machine -> machine.getPos().asLong() == requestedPosition)
                .findFirst().orElse(null);
        if (target == null) {
            return;
        }
        BlockPos position = target.getPos();
        Component location = Component.literal("[" + position.getX() + ", " + position.getY() + ", "
                        + position.getZ() + "]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                WirelessEuCommands.teleportCommand(position)))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("chat.wireless_eu.broadcaster_location_hover"))));
        player.sendSystemMessage(Component.translatable("chat.wireless_eu.broadcaster_location").append(location));
    }

    private void disconnectTargets(Collection<Long> selectedPositions) {
        boolean changed = false;
        for (long selectedPosition : selectedPositions) {
            changed |= connections.remove(selectedPosition) != null;
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
                || !isLoaded(targetLevel, targetPos)) {
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

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    public static String formatAmperageLoad(long euPerTick, long sourceVoltage) {
        if (sourceVoltage <= 0L || euPerTick <= 0L) {
            return "0A";
        }
        String amperage = BigDecimal.valueOf(euPerTick)
                .divide(BigDecimal.valueOf(sourceVoltage), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return amperage + "A";
    }

    public static String formatInputEquivalent(long euPerTick) {
        int tier = -1;
        for (int index = 0; index < GTValues.V.length; index++) {
            if (GTValues.V[index] > euPerTick) {
                break;
            }
            tier = index;
        }
        if (tier < 0) {
            return "0A";
        }
        String amperage = BigDecimal.valueOf(euPerTick).divide(BigDecimal.valueOf(GTValues.V[tier]), 2,
                RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        return amperage + "A " + tierName(tier) + "/t";
    }

    public static String tierName(int tier) {
        return switch (tier) {
            case 0 -> "ULV";
            case 1 -> "LV";
            case 2 -> "MV";
            case 3 -> "HV";
            case 4 -> "EV";
            case 5 -> "IV";
            case 6 -> "LuV";
            case 7 -> "ZPM";
            case 8 -> "UV";
            case 9 -> "UHV";
            case 10 -> "UEV";
            case 11 -> "UIV";
            case 12 -> "UXV";
            case 13 -> "OpV";
            case 14 -> "MAX";
            default -> "T" + tier;
        };
    }

    public static String formatEu(long value) {
        return String.format("%,d", value);
    }

    public static String formatCompactEu(long value) {
        for (int index = COMPACT_EU_DIVISORS.length - 1; index > 0; index--) {
            long divisor = COMPACT_EU_DIVISORS[index];
            if (value >= divisor) {
                long whole = value / divisor;
                long hundredths = value % divisor * 100L / divisor;
                if (hundredths == 0) {
                    return whole + COMPACT_EU_SUFFIXES[index];
                }
                if (hundredths % 10L == 0L) {
                    return whole + "." + hundredths / 10L + COMPACT_EU_SUFFIXES[index];
                }
                return whole + "." + (hundredths < 10L ? "0" : "") + hundredths
                        + COMPACT_EU_SUFFIXES[index];
            }
        }
        return Long.toString(value);
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "无输入";
        }
        if (seconds == 0) {
            return "0分钟";
        }
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0) {
            return hours + "小时" + minutes + "分钟";
        }
        if (minutes > 0) {
            return minutes + "分钟" + remainingSeconds + "秒";
        }
        return remainingSeconds + "秒";
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
