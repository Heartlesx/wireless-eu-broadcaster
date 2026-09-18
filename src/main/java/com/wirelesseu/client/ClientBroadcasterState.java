package com.wirelesseu.client;

import com.wirelesseu.network.BroadcasterState;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** Client mirror of the broadcaster snapshots pushed by the server. */
public final class ClientBroadcasterState {
    private static final Map<BlockPos, BroadcasterState> STATES = new HashMap<>();
    private static final Map<BlockPos, WirelessEuNetwork.ConnectionRejection> REJECTIONS = new HashMap<>();

    private ClientBroadcasterState() {
    }

    public static BroadcasterState get(BlockPos pos) {
        BroadcasterState state = STATES.get(pos);
        return state == null ? BroadcasterState.EMPTY : state;
    }

    public static void put(BroadcasterState state) {
        STATES.put(state.pos, state);
    }

    public static void putConnectionRejection(BlockPos pos, WirelessEuNetwork.ConnectionRejection rejection) {
        REJECTIONS.put(pos, rejection);
    }

    public static WirelessEuNetwork.ConnectionRejection takeConnectionRejection(BlockPos pos) {
        return REJECTIONS.remove(pos);
    }
}
