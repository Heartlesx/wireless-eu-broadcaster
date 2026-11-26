package com.wirelesseu.network;

import com.wirelesseu.machine.TargetKind;
import com.wirelesseu.machine.WirelessRemoteTarget;
import com.wirelesseu.machine.WirelessTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientBroadcasterState {
    private static final Map<StateKey, State> STATES = new ConcurrentHashMap<>();
    private static final Map<StateKey, WirelessEuNetwork.ConnectionRejection> CONNECTION_REJECTIONS =
            new ConcurrentHashMap<>();

    private ClientBroadcasterState() {
    }

    public static State get(ResourceLocation dimension, BlockPos pos) {
        return STATES.getOrDefault(new StateKey(dimension, pos), State.EMPTY);
    }

    public static void put(State state) {
        STATES.put(new StateKey(state.dimension(), state.pos()), state);
    }

    public static void putConnectionRejection(ResourceLocation dimension, BlockPos pos,
                                              WirelessEuNetwork.ConnectionRejection rejection) {
        CONNECTION_REJECTIONS.put(new StateKey(dimension, pos), rejection);
    }

    public static WirelessEuNetwork.ConnectionRejection takeConnectionRejection(ResourceLocation dimension,
                                                                               BlockPos pos) {
        return CONNECTION_REJECTIONS.remove(new StateKey(dimension, pos));
    }

    public record State(ResourceLocation dimension, BlockPos pos, boolean canConfigure,
                        BigInteger storedEu, BigInteger capacityEu,
                        long voltage, BigInteger inputEuPerSecond, BigInteger fillTimeSeconds,
                        BigInteger depletionTimeSeconds, BigInteger connectedLoadEuPerTick,
                        List<WirelessTarget> scanResults, List<WirelessTarget> connections,
                        List<WirelessRemoteTarget> remoteConnections,
                        Set<WirelessEuNetwork.TargetReference> connectedTargets,
                        List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters) {
        public static final State EMPTY = new State(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                BlockPos.ZERO, false, BigInteger.ZERO, BigInteger.ZERO, 0L, BigInteger.ZERO,
                BigInteger.valueOf(-1L), BigInteger.valueOf(-1L), BigInteger.ZERO,
                List.<WirelessTarget>of(), List.<WirelessTarget>of(), List.<WirelessRemoteTarget>of(),
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

    private record StateKey(ResourceLocation dimension, BlockPos pos) {
    }
}
