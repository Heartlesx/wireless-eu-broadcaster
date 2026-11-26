package com.wirelesseu.machine;

import com.gregtechceu.gtceu.api.machine.trait.NotifiableLaserContainer;
import com.wirelesseu.WirelessEuMod;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.math.BigInteger;

/**
 * Big-integer storage presented through GTCEu's long-valued EU/laser container interface.
 *
 * GTCEu only accepts long amounts, so every value that crosses the interface is clamped to
 * {@link Long#MAX_VALUE}; the real amount stays in {@link #stored} and never overflows.
 */
final class AmplifyingLaserContainer extends NotifiableLaserContainer {
    static final BigInteger CAPACITY = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger TWO = BigInteger.valueOf(2L);
    private static final BigInteger FIVE = BigInteger.valueOf(5L);
    private final WirelessEuBroadcasterMachine broadcaster;
    private BigInteger stored = BigInteger.ZERO;

    AmplifyingLaserContainer(WirelessEuBroadcasterMachine broadcaster, long voltage) {
        super(broadcaster, Long.MAX_VALUE, voltage, Long.MAX_VALUE, 0L, 0L);
        this.broadcaster = broadcaster;
    }

    BigInteger getStoredEu() {
        return stored;
    }

    BigInteger getCapacityEu() {
        return CAPACITY;
    }

    BigInteger changeEnergy(BigInteger amount) {
        BigInteger before = stored;
        stored = stored.add(amount).max(BigInteger.ZERO).min(CAPACITY);
        if (!stored.equals(before)) {
            broadcaster.markDirty();
            onChanged();
        }
        return stored.subtract(before);
    }

    @Override
    public long getEnergyStored() {
        return stored.min(LONG_MAX).longValue();
    }

    @Override
    public long getEnergyCapacity() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getEnergyCanBeInserted() {
        return CAPACITY.subtract(stored).min(LONG_MAX).longValue();
    }

    @Override
    public void setEnergyStored(long amount) {
        changeEnergy(BigInteger.valueOf(amount).subtract(stored));
    }

    @Override
    public long changeEnergy(long amount) {
        return changeEnergy(BigInteger.valueOf(amount)).longValue();
    }

    @Override
    public long removeEnergy(long amount) {
        return changeEnergy(BigInteger.valueOf(amount).negate()).negate().min(LONG_MAX).longValue();
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putString("BigEnergyStored", stored.toString());
        tag.putLong("EnergyStored", getEnergyStored());
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        stored = (tag.contains("BigEnergyStored")
                ? new BigInteger(tag.getString("BigEnergyStored"))
                : BigInteger.valueOf(tag.getLong("EnergyStored"))).max(BigInteger.ZERO).min(CAPACITY);
        onChanged();
    }

    @Override
    public long acceptEnergyFromNetwork(Direction side, long voltage, long amperage) {
        if (voltage <= 0L || amperage <= 0L || (side != null && !inputsEnergy(side))
                || broadcaster.isInputBlocked(side)) {
            return 0L;
        }
        if (voltage > getInputVoltage()) {
            return 0L;
        }
        BigInteger remaining = CAPACITY.subtract(stored);
        boolean amplify = WirelessEuMod.enableEnergyAmplification;
        BigInteger rawCapacity = amplify ? remaining.multiply(TWO).add(BigInteger.ONE).divide(FIVE) : remaining;
        long accepted = rawCapacity.divide(BigInteger.valueOf(voltage))
                .min(BigInteger.valueOf(amperage)).longValue();
        if (accepted <= 0L) {
            return 0L;
        }
        BigInteger raw = BigInteger.valueOf(accepted).multiply(BigInteger.valueOf(voltage));
        BigInteger gained = amplify ? raw.multiply(TWO).add(raw.divide(TWO)) : raw;
        broadcaster.recordInput(changeEnergy(gained));
        return accepted;
    }
}
