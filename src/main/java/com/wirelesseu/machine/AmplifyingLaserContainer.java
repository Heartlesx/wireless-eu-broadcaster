package com.wirelesseu.machine;

import com.wirelesseu.WirelessEuMod;
import gregtech.api.capability.impl.LaserContainerHandler;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import java.math.BigInteger;

/** Big-integer storage with the long-valued GT EU/laser interface. */
final class AmplifyingLaserContainer extends LaserContainerHandler {
    static final BigInteger CAPACITY = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final WirelessEuBroadcasterMachine broadcaster;
    private BigInteger stored = BigInteger.ZERO;

    AmplifyingLaserContainer(WirelessEuBroadcasterMachine broadcaster, long voltage) {
        super(broadcaster, Long.MAX_VALUE, voltage, Long.MAX_VALUE, 0L, 0L);
        this.broadcaster = broadcaster;
    }

    BigInteger getStoredEu() {
        return stored;
    }

    BigInteger changeEnergy(BigInteger amount) {
        BigInteger before = stored;
        stored = stored.add(amount).max(BigInteger.ZERO).min(CAPACITY);
        if (!stored.equals(before)) {
            broadcaster.markDirty();
            notifyEnergyListener(false);
        }
        return stored.subtract(before);
    }

    @Override
    public long getEnergyStored() {
        return stored.min(LONG_MAX).longValue();
    }

    @Override
    public long getEnergyCanBeInserted() {
        return CAPACITY.subtract(stored).min(LONG_MAX).longValue();
    }

    @Override
    public boolean isOneProbeHidden() {
        return true;
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
    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("BigEnergyStored", stored.toString());
        tag.setLong("EnergyStored", getEnergyStored());
        return tag;
    }

    @Override
    public void deserializeNBT(NBTTagCompound tag) {
        stored = (tag.hasKey("BigEnergyStored", 8) ? new BigInteger(tag.getString("BigEnergyStored"))
                : BigInteger.valueOf(tag.getLong("EnergyStored"))).max(BigInteger.ZERO).min(CAPACITY);
        notifyEnergyListener(true);
    }

    @Override
    public long acceptEnergyFromNetwork(EnumFacing side, long voltage, long amperage) {
        if (voltage <= 0L || amperage <= 0L || (side != null && !inputsEnergy(side))
                || broadcaster.isInputBlocked(side)) {
            return 0L;
        }
        if (voltage > getInputVoltage()) {
            return super.acceptEnergyFromNetwork(side, voltage, amperage);
        }
        BigInteger remaining = CAPACITY.subtract(stored);
        boolean amplify = WirelessEuMod.enableEnergyAmplification;
        BigInteger rawCapacity = amplify ? remaining.multiply(BigInteger.valueOf(2L)).add(BigInteger.ONE)
                .divide(BigInteger.valueOf(5L)) : remaining;
        long accepted = rawCapacity.divide(BigInteger.valueOf(voltage))
                .min(BigInteger.valueOf(amperage)).longValue();
        if (accepted <= 0L) {
            return 0L;
        }
        BigInteger raw = BigInteger.valueOf(accepted).multiply(BigInteger.valueOf(voltage));
        BigInteger gained = amplify ? raw.add(raw).add(raw.divide(BigInteger.valueOf(2L))) : raw;
        broadcaster.recordInput(changeEnergy(gained));
        return accepted;
    }
}
