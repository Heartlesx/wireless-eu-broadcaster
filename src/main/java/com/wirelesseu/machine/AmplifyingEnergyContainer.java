package com.wirelesseu.machine;

import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import net.minecraft.core.Direction;

/** Receives normal EU packets but retains 2.5 times their accepted EU value. */
final class AmplifyingEnergyContainer extends NotifiableEnergyContainer {
    private final WirelessEuBroadcasterMachine broadcaster;

    AmplifyingEnergyContainer(WirelessEuBroadcasterMachine broadcaster, long capacity, long voltage, long amperage) {
        super(broadcaster, capacity, voltage, amperage, 0L, 0L);
        this.broadcaster = broadcaster;
    }

    @Override
    public long acceptEnergyFromNetwork(Direction side, long voltage, long amperage) {
        if (voltage <= 0 || amperage <= 0) {
            return 0;
        }

        long remainingStorage = getEnergyCapacity() - getEnergyStored();
        // The base container stores one raw EU packet. Reserve room for the extra 1.5x first.
        long rawEnergyCapacity = remainingStorage / 5L * 2L;
        long capacityLimitedAmperage = rawEnergyCapacity / voltage;
        if (capacityLimitedAmperage <= 0) {
            return 0;
        }

        long acceptedAmperage = super.acceptEnergyFromNetwork(side, voltage,
                Math.min(amperage, capacityLimitedAmperage));
        if (acceptedAmperage <= 0) {
            return acceptedAmperage;
        }

        long rawEnergy = acceptedAmperage * voltage;
        long extraEnergy = rawEnergy + rawEnergy / 2L;
        long retainedExtraEnergy = changeEnergy(extraEnergy);
        broadcaster.recordAmplifiedInput(rawEnergy + retainedExtraEnergy);
        return acceptedAmperage;
    }
}
