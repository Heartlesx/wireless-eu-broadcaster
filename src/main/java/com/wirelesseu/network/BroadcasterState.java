package com.wirelesseu.network;

import com.wirelesseu.machine.WirelessRemoteTarget;
import com.wirelesseu.machine.WirelessTarget;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/** Snapshot of one broadcaster that the server sends to the client screen. */
public final class BroadcasterState {
    public static final BroadcasterState EMPTY = new BroadcasterState(0, BlockPos.ORIGIN, false,
            BigInteger.ZERO, BigInteger.ZERO, 0L, BigInteger.ZERO,
            BigInteger.valueOf(-1L), BigInteger.ZERO, BigInteger.ZERO,
            Collections.<WirelessTarget>emptyList(), Collections.<WirelessTarget>emptyList(),
            Collections.<WirelessRemoteTarget>emptyList(),
            Collections.<WirelessEuNetwork.TargetReference>emptySet(),
            Collections.<WirelessEuNetwork.HigherBroadcasterInfo>emptyList());

    public final int dimension;
    public final BlockPos pos;
    public final boolean canConfigure;
    public final BigInteger storedEu;
    public final BigInteger capacityEu;
    public final long voltage;
    public final BigInteger inputEuPerSecond;
    public final BigInteger fillTimeSeconds;
    public final BigInteger depletionTimeSeconds;
    public final BigInteger connectedLoadEuPerTick;
    public final List<WirelessTarget> scanResults;
    public final List<WirelessTarget> connections;
    public final List<WirelessRemoteTarget> remoteConnections;
    public final Set<WirelessEuNetwork.TargetReference> connectedTargets;
    public final List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters;

    public BroadcasterState(int dimension, BlockPos pos, boolean canConfigure, BigInteger storedEu,
                            BigInteger capacityEu, long voltage, BigInteger inputEuPerSecond, BigInteger fillTimeSeconds,
                            BigInteger depletionTimeSeconds, BigInteger connectedLoadEuPerTick, List<WirelessTarget> scanResults,
                            List<WirelessTarget> connections, List<WirelessRemoteTarget> remoteConnections,
                            Set<WirelessEuNetwork.TargetReference> connectedTargets,
                            List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters) {
        this.dimension = dimension;
        this.pos = pos;
        this.canConfigure = canConfigure;
        this.storedEu = storedEu;
        this.capacityEu = capacityEu;
        this.voltage = voltage;
        this.inputEuPerSecond = inputEuPerSecond;
        this.fillTimeSeconds = fillTimeSeconds;
        this.depletionTimeSeconds = depletionTimeSeconds;
        this.connectedLoadEuPerTick = connectedLoadEuPerTick;
        this.scanResults = scanResults;
        this.connections = connections;
        this.remoteConnections = remoteConnections;
        this.connectedTargets = connectedTargets;
        this.higherBroadcasters = higherBroadcasters;
    }

    public boolean isConnected(WirelessEuNetwork.TargetReference reference) {
        return connectedTargets.contains(reference);
    }
}
