package com.wirelesseu.network;

import com.wirelesseu.machine.TargetKind;
import com.wirelesseu.machine.WirelessRemoteTarget;
import com.wirelesseu.machine.WirelessTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientBroadcasterState {
    private static final Map<BlockPos, State> STATES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, WirelessEuNetwork.ConnectionRejection> CONNECTION_REJECTIONS =
            new ConcurrentHashMap<>();

    private ClientBroadcasterState() {
    }

    public static State get(BlockPos pos) {
        return STATES.getOrDefault(pos, State.EMPTY);
    }

    public static void put(State state) {
        STATES.put(state.pos(), state);
    }

    public static void putConnectionRejection(BlockPos pos, WirelessEuNetwork.ConnectionRejection rejection) {
        CONNECTION_REJECTIONS.put(pos, rejection);
    }

    public static WirelessEuNetwork.ConnectionRejection takeConnectionRejection(BlockPos pos) {
        return CONNECTION_REJECTIONS.remove(pos);
    }

    public record State(ResourceLocation dimension, BlockPos pos, boolean canConfigure, int outputAmperage,
                        long storedEu, long capacityEu,
                        long voltage, long inputEuPerSecond, long fillTimeSeconds, long depletionTimeSeconds,
                        long connectedLoadEuPerTick,
                        List<WirelessTarget> scanResults, List<WirelessRemoteTarget> remoteConnections,
                        Set<WirelessEuNetwork.TargetReference> connectedTargets,
                        List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters) {
        public static final State EMPTY = new State(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                BlockPos.ZERO, false, 4, 0L, 0L, 0L, 0L, -1L, 0L, 0L,
                List.<WirelessTarget>of(), List.<WirelessRemoteTarget>of(),
                Set.<WirelessEuNetwork.TargetReference>of(), List.<WirelessEuNetwork.HigherBroadcasterInfo>of());

        public boolean isConnected(WirelessEuNetwork.TargetReference target) {
            return connectedTargets.contains(target);
        }
    }

    public record TargetView(WirelessEuNetwork.TargetReference reference, String name, TargetKind kind, int tier,
                             long voltage, long amperage, boolean remote) {
        public static TargetView local(ResourceLocation dimension, WirelessTarget target) {
            return new TargetView(new WirelessEuNetwork.TargetReference(dimension, target.pos()), target.name(),
                    target.kind(), target.tier(), target.voltage(), target.amperage(), false);
        }

        public static TargetView remote(WirelessRemoteTarget target) {
            return new TargetView(new WirelessEuNetwork.TargetReference(target.dimension(), target.pos()),
                    target.name(), target.kind(), target.tier(), target.voltage(), target.amperage(), true);
        }
    }
}
