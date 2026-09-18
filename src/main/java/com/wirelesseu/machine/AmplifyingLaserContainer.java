package com.wirelesseu.machine;

import gregtech.api.capability.impl.LaserContainerHandler;
import net.minecraft.util.EnumFacing;

/** Accepts normal GT EU and GT laser input, retaining 2.5 times the accepted EU. */
final class AmplifyingLaserContainer extends LaserContainerHandler {
    private final WirelessEuBroadcasterMachine broadcaster;

    AmplifyingLaserContainer(WirelessEuBroadcasterMachine broadcaster, long capacity, long voltage) {
        super(broadcaster, capacity, voltage, 256L, 0L, 0L);
        this.broadcaster = broadcaster;
    }

    @Override
    public long acceptEnergyFromNetwork(EnumFacing side, long voltage, long amperage) {
        if (voltage <= 0L || amperage <= 0L || broadcaster.isInputBlocked(side)) {
            return 0L;
        }
        long remaining = getEnergyCapacity() - getEnergyStored();
        long rawCapacity = remaining / 5L * 2L;
        long accepted = super.acceptEnergyFromNetwork(side, voltage,
                Math.min(amperage, rawCapacity / voltage));
        if (accepted <= 0L) {
            return accepted;
        }
        long raw = accepted * voltage;
        long extra = raw + raw / 2L;
        changeEnergy(Math.min(extra, getEnergyCapacity() - getEnergyStored()));
        broadcaster.recordInput(raw + extra);
        return accepted;
    }
}
