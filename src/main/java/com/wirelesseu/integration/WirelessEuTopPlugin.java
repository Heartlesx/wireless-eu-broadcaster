package com.wirelesseu.integration;

import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.function.Function;

/** Optional TOP provider. The class is only loaded by TOP when that mod is present. */
public final class WirelessEuTopPlugin implements Function<ITheOneProbe, Void> {
    @Override
    public Void apply(ITheOneProbe probe) {
        probe.registerProvider(new Provider());
        return null;
    }

    private static final class Provider implements IProbeInfoProvider {
        @Override
        public String getID() {
            return "wireless_eu:broadcaster";
        }

        @Override
        public void addProbeInfo(ProbeMode mode, IProbeInfo info, EntityPlayer player, World world,
                                 IBlockState state, IProbeHitData hitData) {
            WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(world, hitData.getPos());
            if (machine == null) {
                return;
            }
            info.text("Wireless EU: " + WirelessEuBroadcasterMachine.tierName(machine.getTierValue()));
            info.text("EU: " + WirelessEuBroadcasterMachine.formatEu(machine.getInputPerSecond()) + "/s input");
            info.text("Links: " + (machine.getConnections().size() + machine.getRemoteConnections().size()));
            info.progress(machine.getInputPerSecond(), Math.max(1L, machine.getVoltage() * 256L));
        }
    }
}
