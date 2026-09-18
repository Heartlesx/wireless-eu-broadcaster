package com.wirelesseu.network;

import com.wirelesseu.machine.WirelessRemoteTarget;
import com.wirelesseu.machine.WirelessTarget;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Snapshot of one broadcaster that the server sends to the client screen. */
public final class BroadcasterState {
    public static final BroadcasterState EMPTY = new BroadcasterState(0, BlockPos.ORIGIN, false, 65536, 0L, 0L, 0L, 0L,
            -1L, 0L, 0L, Collections.<WirelessTarget>emptyList(), Collections.<WirelessTarget>emptyList(),
            Collections.<WirelessRemoteTarget>emptyList(),
            Collections.<WirelessEuNetwork.TargetReference>emptySet(),
            Collections.<WirelessEuNetwork.HigherBroadcasterInfo>emptyList());

    public final int dimension;
    public final BlockPos pos;
    public final boolean canConfigure;
    public final int outputAmperage;
    public final long storedEu;
    public final long capacityEu;
    public final long voltage;
    public final long inputEuPerSecond;
    public final long fillTimeSeconds;
    public final long depletionTimeSeconds;
    public final long connectedLoadEuPerTick;
    public final List<WirelessTarget> scanResults;
    public final List<WirelessTarget> connections;
    public final List<WirelessRemoteTarget> remoteConnections;
    public final Set<WirelessEuNetwork.TargetReference> connectedTargets;
    public final List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters;

    public BroadcasterState(int dimension, BlockPos pos, boolean canConfigure, int outputAmperage, long storedEu,
                            long capacityEu, long voltage, long inputEuPerSecond, long fillTimeSeconds,
                            long depletionTimeSeconds, long connectedLoadEuPerTick, List<WirelessTarget> scanResults,
                            List<WirelessTarget> connections, List<WirelessRemoteTarget> remoteConnections,
                            Set<WirelessEuNetwork.TargetReference> connectedTargets,
                            List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters) {
        this.dimension = dimension;
        this.pos = pos;
        this.canConfigure = canConfigure;
        this.outputAmperage = outputAmperage;
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
