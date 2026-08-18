package com.wirelesseu.network;

import com.wirelesseu.machine.WirelessTarget;
import net.minecraft.core.BlockPos;

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

    public record State(BlockPos pos, boolean canConfigure, int outputAmperage, long storedEu, long capacityEu,
                        long voltage, long inputEuPerSecond, long fillTimeSeconds, long depletionTimeSeconds,
                        long connectedLoadEuPerTick,
                        List<WirelessTarget> scanResults, Set<Long> connectedPositions,
                        List<WirelessEuNetwork.HigherBroadcasterInfo> higherBroadcasters) {
        public static final State EMPTY = new State(BlockPos.ZERO, false, 4, 0L, 0L, 0L, 0L, -1L, 0L, 0L,
                List.of(), Set.of(), List.of());

        public boolean isConnected(WirelessTarget target) {
            return connectedPositions.contains(target.pos().asLong());
        }
    }
}
