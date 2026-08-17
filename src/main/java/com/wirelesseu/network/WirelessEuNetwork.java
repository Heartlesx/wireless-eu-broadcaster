package com.wirelesseu.network;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.TargetKind;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessTarget;
import com.wirelesseu.menu.WirelessBroadcasterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class WirelessEuNetwork {
    private static final String PROTOCOL_VERSION = "5";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(WirelessEuMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);
    private static int nextPacketId;

    private WirelessEuNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextPacketId++, ActionPacket.class, ActionPacket::encode, ActionPacket::decode,
                ActionPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, StatePacket.class, StatePacket::encode, StatePacket::decode,
                StatePacket::handle);
        CHANNEL.registerMessage(nextPacketId++, ConnectionRejectedPacket.class, ConnectionRejectedPacket::encode,
                ConnectionRejectedPacket::decode, ConnectionRejectedPacket::handle);
    }

    public static void sendAction(BlockPos pos, ServerAction action, int requestedAmperage,
                                  Collection<Long> selectedPositions) {
        CHANNEL.sendToServer(new ActionPacket(pos, action, requestedAmperage, List.copyOf(selectedPositions)));
    }

    public static void sendState(ServerPlayer player, WirelessEuBroadcasterMachine broadcaster) {
        StatePacket packet = new StatePacket(
                broadcaster.getPos(),
                broadcaster.isOwner(player),
                broadcaster.getConfiguredOutputAmperage(),
                broadcaster.energyContainer.getEnergyStored(),
                broadcaster.energyContainer.getEnergyCapacity(),
                broadcaster.getVoltage(),
                broadcaster.getInputPerSecond(),
                broadcaster.getFillTimeSeconds(),
                broadcaster.getDepletionTimeSeconds(),
                broadcaster.getConnectedLoadEuPerTick(),
                broadcaster.getScanResults(),
                broadcaster.getConnections());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendConnectionRejected(ServerPlayer player, BlockPos pos, ConnectionRejection rejection) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ConnectionRejectedPacket(pos, rejection));
    }

    public enum ServerAction {
        SCAN,
        SET_OUTPUT_AMPERAGE,
        CONNECT_SELECTED,
        DISCONNECT_SELECTED,
        LOCATE_TARGET
    }

    public enum ConnectionRejection {
        INCREASE_OUTPUT_LIMIT,
        OUTPUT_FULL,
        BROADCASTER_LIMIT
    }

    private record ActionPacket(BlockPos pos, ServerAction action, int requestedAmperage,
                                List<Long> selectedPositions) {
        private static void encode(ActionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeVarInt(packet.action.ordinal());
            buffer.writeVarInt(packet.requestedAmperage);
            buffer.writeVarInt(packet.selectedPositions.size());
            for (long selectedPosition : packet.selectedPositions) {
                buffer.writeLong(selectedPosition);
            }
        }

        private static ActionPacket decode(FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int ordinal = buffer.readVarInt();
            ServerAction action = ServerAction.values()[Math.max(0, Math.min(ServerAction.values().length - 1, ordinal))];
            int requestedAmperage = buffer.readVarInt();
            int count = Math.min(4096, buffer.readVarInt());
            List<Long> selectedPositions = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                selectedPositions.add(buffer.readLong());
            }
            return new ActionPacket(pos, action, requestedAmperage, selectedPositions);
        }

        private static void handle(ActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !(player.containerMenu instanceof WirelessBroadcasterMenu menu)
                        || !menu.getPos().equals(packet.pos)) {
                    return;
                }
                WirelessEuBroadcasterMachine machine = menu.getMachine();
                if (machine != null) {
                    machine.handleAction(player, packet.action, packet.requestedAmperage, packet.selectedPositions);
                }
            });
            context.setPacketHandled(true);
        }
    }

    private record StatePacket(BlockPos pos, boolean canConfigure, int outputAmperage, long storedEu, long capacityEu,
                               long voltage, long inputEuPerSecond, long fillTimeSeconds, long depletionTimeSeconds,
                               long connectedLoadEuPerTick,
                               List<WirelessTarget> scanResults, List<WirelessTarget> connections) {
        private static void encode(StatePacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeBoolean(packet.canConfigure);
            buffer.writeVarInt(packet.outputAmperage);
            buffer.writeLong(packet.storedEu);
            buffer.writeLong(packet.capacityEu);
            buffer.writeLong(packet.voltage);
            buffer.writeLong(packet.inputEuPerSecond);
            buffer.writeLong(packet.fillTimeSeconds);
            buffer.writeLong(packet.depletionTimeSeconds);
            buffer.writeLong(packet.connectedLoadEuPerTick);
            writeTargets(buffer, packet.scanResults);
            writeTargets(buffer, packet.connections);
        }

        private static StatePacket decode(FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            boolean canConfigure = buffer.readBoolean();
            int outputAmperage = buffer.readVarInt();
            long storedEu = buffer.readLong();
            long capacityEu = buffer.readLong();
            long voltage = buffer.readLong();
            long inputEuPerSecond = buffer.readLong();
            long fillTimeSeconds = buffer.readLong();
            long depletionTimeSeconds = buffer.readLong();
            long connectedLoadEuPerTick = buffer.readLong();
            return new StatePacket(pos, canConfigure, outputAmperage, storedEu, capacityEu, voltage, inputEuPerSecond,
                    fillTimeSeconds, depletionTimeSeconds, connectedLoadEuPerTick, readTargets(buffer), readTargets(buffer));
        }

        private static void handle(StatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                Set<Long> connectedPositions = new HashSet<>();
                for (WirelessTarget target : packet.connections) {
                    connectedPositions.add(target.pos().asLong());
                }
                ClientBroadcasterState.put(new ClientBroadcasterState.State(packet.pos, packet.canConfigure,
                        packet.outputAmperage, packet.storedEu, packet.capacityEu, packet.voltage, packet.inputEuPerSecond,
                        packet.fillTimeSeconds, packet.depletionTimeSeconds, packet.connectedLoadEuPerTick,
                        List.copyOf(packet.scanResults),
                        Set.copyOf(connectedPositions)));
            });
            context.setPacketHandled(true);
        }

        private static void writeTargets(FriendlyByteBuf buffer, List<WirelessTarget> targets) {
            buffer.writeVarInt(targets.size());
            for (WirelessTarget target : targets) {
                buffer.writeLong(target.pos().asLong());
                buffer.writeUtf(target.name(), 256);
                buffer.writeVarInt(target.kind().ordinal());
                buffer.writeVarInt(target.tier());
                buffer.writeLong(target.voltage());
                buffer.writeLong(target.amperage());
            }
        }

        private static List<WirelessTarget> readTargets(FriendlyByteBuf buffer) {
            int count = Math.min(4096, buffer.readVarInt());
            List<WirelessTarget> targets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                BlockPos pos = BlockPos.of(buffer.readLong());
                String name = buffer.readUtf(256);
                int kindOrdinal = buffer.readVarInt();
                TargetKind kind = TargetKind.values()[Math.max(0, Math.min(TargetKind.values().length - 1, kindOrdinal))];
                int tier = Math.max(0, buffer.readVarInt());
                long voltage = Math.max(1L, buffer.readLong());
                long amperage = Math.max(1L, buffer.readLong());
                targets.add(new WirelessTarget(pos, name, kind, tier, voltage, amperage));
            }
            return targets;
        }
    }

    private record ConnectionRejectedPacket(BlockPos pos, ConnectionRejection rejection) {
        private static void encode(ConnectionRejectedPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeVarInt(packet.rejection.ordinal());
        }

        private static ConnectionRejectedPacket decode(FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int ordinal = buffer.readVarInt();
            ConnectionRejection rejection = ConnectionRejection.values()[Math.max(0,
                    Math.min(ConnectionRejection.values().length - 1, ordinal))];
            return new ConnectionRejectedPacket(pos, rejection);
        }

        private static void handle(ConnectionRejectedPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> ClientBroadcasterState.putConnectionRejection(packet.pos, packet.rejection));
            context.setPacketHandled(true);
        }
    }
}
