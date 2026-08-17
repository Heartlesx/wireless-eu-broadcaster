package com.wirelesseu.machine;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.ILaserContainer;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.TieredEnergyMachine;
import com.gregtechceu.gtceu.api.machine.feature.ITieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.wirelesseu.command.WirelessEuCommands;
import com.wirelesseu.menu.WirelessBroadcasterMenu;
import com.wirelesseu.network.WirelessEuNetwork;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WirelessEuBroadcasterMachine extends TieredEnergyMachine implements IUIMachine {
    public static final int RANGE = 384;
    public static final int DEFAULT_OUTPUT_AMPERAGE = 65_536;
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
    }

    public void handleAction(ServerPlayer player, WirelessEuNetwork.ServerAction action,
                             int requestedAmperage, Collection<Long> selectedPositions) {
        if (action == WirelessEuNetwork.ServerAction.LOCATE_TARGET) {
            sendTargetLocation(player, selectedPositions);
            sendStateTo(player);
            return;
        }
        if (!isOwner(player)) {
            sendStateTo(player);
            return;
        }

        switch (action) {
            case SCAN -> scanTargets();
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
        return load;
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

    public long getVoltage() {
        return GTValues.V[getTier()];
    }

    void recordAmplifiedInput(long acceptedEnergy) {
        pendingAmplifiedInput += acceptedEnergy;
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
        if (connections.isEmpty() || energyContainer.getEnergyStored() <= 0L) {
            return;
        }

        List<WirelessTarget> targets = new ArrayList<>(connections.values());
        long remainingEu = saturatingMultiply(configuredOutputAmperage, getVoltage());
        int startingIndex = Math.floorMod(roundRobinIndex, targets.size());
        boolean changedConnections = false;

        for (int offset = 0; offset < targets.size() && remainingEu > 0L; offset++) {
            WirelessTarget target = targets.get((startingIndex + offset) % targets.size());
            Receiver receiver = resolveReceiver(target.pos());
            long targetVoltage = receiver == null ? 0L : receiver.container().getInputVoltage();
            if (receiver == null || targetVoltage <= 0L || targetVoltage > getVoltage()) {
                if (isLoaded(target.pos())) {
                    connections.remove(target.pos().asLong());
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
        if (targetPos.equals(getPos()) || !isInRange(targetPos) || !isLoaded(targetPos)) {
            return null;
        }

        MetaMachine machine = MetaMachine.getMachine(Objects.requireNonNull(getLevel()), targetPos);
        if (!(machine instanceof ITieredMachine tieredMachine)
                || tieredMachine.getTier() > getTier()
                || machine instanceof MultiblockControllerMachine
                || machine instanceof WirelessEuBroadcasterMachine) {
            return null;
        }

        Receiver receiver = resolveReceiver(targetPos);
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

    private Receiver resolveReceiver(BlockPos targetPos) {
        Level level = getLevel();
        if (level == null || !isLoaded(targetPos)) {
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
        Level level = getLevel();
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
        return BigDecimal.valueOf(euPerTick).divide(BigDecimal.valueOf(sourceVoltage)).stripTrailingZeros()
                .toPlainString() + "A";
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

    private record Receiver(IEnergyContainer container, Direction side, boolean laser) {
    }
}
