package com.wirelesseu.network;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.TargetKind;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessRemoteTarget;
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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class WirelessEuNetwork {
    public record HigherBroadcasterInfo(BlockPos pos, int tier, long distanceSquared) {}
    public record TargetReference(ResourceLocation dimension, BlockPos pos) {}
    private static final String PROTOCOL_VERSION = "10";
    private static final int MAX_TARGETS = 4096;
    private static final int MAX_HIGHER_BROADCASTERS = 64;
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

    public static void sendAction(BlockPos pos, ServerAction action,
                                  Collection<TargetReference> selectedTargets) {
        CHANNEL.sendToServer(new ActionPacket(pos, action, List.copyOf(selectedTargets)));
    }

    public static void sendState(ServerPlayer player, WirelessEuBroadcasterMachine broadcaster) {
        StatePacket packet = new StatePacket(
                broadcaster.getLevel().dimension().location(),
                broadcaster.getPos(),
                broadcaster.isOwner(player),
                broadcaster.getStoredEu().toString(),
                broadcaster.getCapacityEu().toString(),
                broadcaster.getVoltage(),
                broadcaster.getInputPerSecond().toString(),
                broadcaster.getFillTimeSeconds().toString(),
                broadcaster.getDepletionTimeSeconds().toString(),
                broadcaster.getConnectedLoadEuPerTick().toString(),
                broadcaster.getScanResults(),
                broadcaster.getConnections(),
                broadcaster.getRemoteConnections(),
                broadcaster.getHigherBroadcasters(player));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendConnectionRejected(ServerPlayer player, BlockPos pos, ConnectionRejection rejection) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ConnectionRejectedPacket(pos, rejection));
    }

    public enum ServerAction {
        SCAN,
        CONNECT_SELECTED,
        DISCONNECT_SELECTED,
        LOCATE_TARGET,
        MIGRATE_TO_HIGHER,
        LOCATE_BROADCASTER
    }

    public enum ConnectionRejection {
        BROADCASTER_LIMIT,
        MIGRATION_WIRELESS_UPGRADE_REQUIRED
    }

    private record ActionPacket(BlockPos pos, ServerAction action, List<TargetReference> selectedTargets) {
        private static void encode(ActionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeVarInt(packet.action.ordinal());
            buffer.writeVarInt(Math.min(MAX_TARGETS, packet.selectedTargets.size()));
            for (int index = 0; index < packet.selectedTargets.size() && index < MAX_TARGETS; index++) {
                TargetReference target = packet.selectedTargets.get(index);
                buffer.writeResourceLocation(target.dimension());
                buffer.writeBlockPos(target.pos());
            }
        }

        private static ActionPacket decode(FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int ordinal = buffer.readVarInt();
            ServerAction action = ServerAction.values()[Math.max(0,
                    Math.min(ServerAction.values().length - 1, ordinal))];
            int count = Math.min(MAX_TARGETS, buffer.readVarInt());
            List<TargetReference> selectedTargets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                selectedTargets.add(new TargetReference(buffer.readResourceLocation(), buffer.readBlockPos()));
            }
            return new ActionPacket(pos, action, selectedTargets);
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
                    machine.handleAction(player, packet.action, packet.selectedTargets);
                }
            });
            context.setPacketHandled(true);
        }
    }

    private record StatePacket(ResourceLocation dimension, BlockPos pos, boolean canConfigure,
                               String storedEu, String capacityEu,
                               long voltage, String inputEuPerSecond, String fillTimeSeconds,
                               String depletionTimeSeconds, String connectedLoadEuPerTick,
                               List<WirelessTarget> scanResults, List<WirelessTarget> connections,
                               List<WirelessRemoteTarget> remoteConnections,
                               List<HigherBroadcasterInfo> higherBroadcasters) {
        private static void encode(StatePacket packet, FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(packet.dimension);
            buffer.writeBlockPos(packet.pos);
            buffer.writeBoolean(packet.canConfigure);
            buffer.writeUtf(packet.storedEu, 128);
            buffer.writeUtf(packet.capacityEu, 128);
            buffer.writeLong(packet.voltage);
            buffer.writeUtf(packet.inputEuPerSecond, 128);
            buffer.writeUtf(packet.fillTimeSeconds, 128);
            buffer.writeUtf(packet.depletionTimeSeconds, 128);
            buffer.writeUtf(packet.connectedLoadEuPerTick, 128);
            writeTargets(buffer, packet.scanResults);
            writeTargets(buffer, packet.connections);
            writeRemoteTargets(buffer, packet.remoteConnections);
            buffer.writeVarInt(Math.min(MAX_HIGHER_BROADCASTERS, packet.higherBroadcasters.size()));
            for (int index = 0; index < packet.higherBroadcasters.size() && index < MAX_HIGHER_BROADCASTERS;
                 index++) {
                HigherBroadcasterInfo info = packet.higherBroadcasters.get(index);
                buffer.writeBlockPos(info.pos());
                buffer.writeVarInt(info.tier());
                buffer.writeLong(info.distanceSquared());
            }
        }

        private static StatePacket decode(FriendlyByteBuf buffer) {
            ResourceLocation dimension = buffer.readResourceLocation();
            BlockPos pos = buffer.readBlockPos();
            boolean canConfigure = buffer.readBoolean();
            String storedEu = buffer.readUtf(128);
            String capacityEu = buffer.readUtf(128);
            long voltage = buffer.readLong();
            String inputEuPerSecond = buffer.readUtf(128);
            String fillTimeSeconds = buffer.readUtf(128);
            String depletionTimeSeconds = buffer.readUtf(128);
            String connectedLoadEuPerTick = buffer.readUtf(128);
            return new StatePacket(dimension, pos, canConfigure, storedEu, capacityEu, voltage,
                    inputEuPerSecond, fillTimeSeconds, depletionTimeSeconds, connectedLoadEuPerTick,
                    readTargets(buffer), readTargets(buffer), readRemoteTargets(buffer), readHigherBroadcasters(buffer));
        }

        private static void handle(StatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                Set<TargetReference> connectedTargets = new HashSet<>();
                for (WirelessTarget target : packet.connections) {
                    connectedTargets.add(new TargetReference(packet.dimension, target.pos()));
                }
                for (WirelessRemoteTarget target : packet.remoteConnections) {
                    connectedTargets.add(new TargetReference(target.dimension(), target.pos()));
                }
                ClientBroadcasterState.put(new ClientBroadcasterState.State(packet.dimension, packet.pos,
                        packet.canConfigure,
                        parse(packet.storedEu), parse(packet.capacityEu), packet.voltage,
                        parse(packet.inputEuPerSecond), parse(packet.fillTimeSeconds),
                        parse(packet.depletionTimeSeconds), parse(packet.connectedLoadEuPerTick),
                        List.copyOf(packet.scanResults), List.copyOf(packet.connections),
                        List.copyOf(packet.remoteConnections), Set.copyOf(connectedTargets),
                        List.copyOf(packet.higherBroadcasters)));
            });
            context.setPacketHandled(true);
        }

        private static BigInteger parse(String value) {
            try {
                return new BigInteger(value);
            } catch (NumberFormatException exception) {
                return BigInteger.ZERO;
            }
        }

        private static List<HigherBroadcasterInfo> readHigherBroadcasters(FriendlyByteBuf buffer) {
            int count = Math.min(MAX_HIGHER_BROADCASTERS, buffer.readVarInt());
            List<HigherBroadcasterInfo> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(new HigherBroadcasterInfo(buffer.readBlockPos(), buffer.readVarInt(), buffer.readLong()));
            }
            return result;
        }

        private static void writeTargets(FriendlyByteBuf buffer, List<WirelessTarget> targets) {
            int count = Math.min(MAX_TARGETS, targets.size());
            buffer.writeVarInt(count);
            for (int index = 0; index < count; index++) {
                WirelessTarget target = targets.get(index);
                buffer.writeLong(target.pos().asLong());
                buffer.writeUtf(target.name(), 256);
                buffer.writeVarInt(target.kind().ordinal());
                buffer.writeVarInt(target.tier());
                buffer.writeLong(target.voltage());
                buffer.writeLong(target.amperage());
            }
        }

        private static List<WirelessTarget> readTargets(FriendlyByteBuf buffer) {
            int count = Math.min(MAX_TARGETS, buffer.readVarInt());
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

        private static void writeRemoteTargets(FriendlyByteBuf buffer, List<WirelessRemoteTarget> targets) {
            int count = Math.min(MAX_TARGETS, targets.size());
            buffer.writeVarInt(count);
            for (int index = 0; index < count; index++) {
                WirelessRemoteTarget target = targets.get(index);
                buffer.writeResourceLocation(target.dimension());
                buffer.writeBlockPos(target.pos());
                buffer.writeUtf(target.name(), 256);
                buffer.writeVarInt(target.kind().ordinal());
                buffer.writeVarInt(target.tier());
                buffer.writeLong(target.voltage());
                buffer.writeLong(target.amperage());
            }
        }

        private static List<WirelessRemoteTarget> readRemoteTargets(FriendlyByteBuf buffer) {
            int count = Math.min(MAX_TARGETS, buffer.readVarInt());
            List<WirelessRemoteTarget> targets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                ResourceLocation dimension = buffer.readResourceLocation();
                BlockPos pos = buffer.readBlockPos();
                String name = buffer.readUtf(256);
                int kindOrdinal = buffer.readVarInt();
                TargetKind kind = TargetKind.values()[Math.max(0, Math.min(TargetKind.values().length - 1, kindOrdinal))];
                int tier = Math.max(0, buffer.readVarInt());
                long voltage = Math.max(1L, buffer.readLong());
                long amperage = Math.max(1L, buffer.readLong());
                targets.add(new WirelessRemoteTarget(dimension, pos, name, kind, tier, voltage, amperage));
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
            context.enqueueWork(() -> {
                if (net.minecraft.client.Minecraft.getInstance().level == null) {
                    return;
                }
                ClientBroadcasterState.putConnectionRejection(
                        net.minecraft.client.Minecraft.getInstance().level.dimension().location(),
                        packet.pos, packet.rejection);
            });
            context.setPacketHandled(true);
        }
    }
}
