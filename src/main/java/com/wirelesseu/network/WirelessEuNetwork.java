package com.wirelesseu.network;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.client.ClientBroadcasterState;
import com.wirelesseu.gui.WirelessEuContainer;
import com.wirelesseu.machine.TargetKind;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import com.wirelesseu.machine.WirelessRemoteTarget;
import com.wirelesseu.machine.WirelessTarget;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Terminal screen channel: the client sends actions, the server answers with a full state snapshot or a rejection.
 * This mirrors the 1.20.1 screen protocol without a menu container payload channel.
 */
public final class WirelessEuNetwork {
    private static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(WirelessEuMod.MOD_ID);
    private static int nextPacketId;

    private WirelessEuNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(StateHandler.class, StateMessage.class, nextPacketId++, Side.CLIENT);
        CHANNEL.registerMessage(RejectedHandler.class, RejectedMessage.class, nextPacketId++, Side.CLIENT);
        CHANNEL.registerMessage(ActionHandler.class, ActionMessage.class, nextPacketId++, Side.SERVER);
    }

    public static void sendState(EntityPlayerMP player, WirelessEuBroadcasterMachine broadcaster) {
        CHANNEL.sendTo(new StateMessage(broadcaster, player), player);
    }

    public static void sendConnectionRejected(EntityPlayerMP player, BlockPos pos, ConnectionRejection rejection) {
        CHANNEL.sendTo(new RejectedMessage(pos, rejection), player);
    }

    public static void sendAction(BlockPos pos, ServerAction action, List<TargetReference> selectedTargets) {
        CHANNEL.sendToServer(new ActionMessage(pos, action, selectedTargets));
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
        INCREASE_OUTPUT_LIMIT,
        OUTPUT_FULL,
        BROADCASTER_LIMIT,
        MIGRATION_WIRELESS_UPGRADE_REQUIRED
    }

    /** Dimension plus position, because the same coordinates can exist in several dimensions. */
    public static final class TargetReference {
        public final int dimension;
        public final BlockPos pos;

        public TargetReference(int dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TargetReference)) {
                return false;
            }
            TargetReference reference = (TargetReference) other;
            return dimension == reference.dimension && pos.equals(reference.pos);
        }

        @Override
        public int hashCode() {
            return 31 * dimension + pos.hashCode();
        }
    }

    public static final class HigherBroadcasterInfo {
        public final BlockPos pos;
        public final int tier;
        public final long distanceSquared;

        public HigherBroadcasterInfo(BlockPos pos, int tier, long distanceSquared) {
            this.pos = pos;
            this.tier = tier;
            this.distanceSquared = distanceSquared;
        }
    }

    public static final class StateMessage implements IMessage {
        private BroadcasterState state;

        public StateMessage() {
        }

        StateMessage(WirelessEuBroadcasterMachine broadcaster, EntityPlayerMP player) {
            Set<TargetReference> connected = new HashSet<>();
            for (WirelessTarget target : broadcaster.getConnections()) {
                connected.add(new TargetReference(broadcaster.getWorld().provider.getDimension(), target.pos));
            }
            for (WirelessRemoteTarget target : broadcaster.getRemoteConnections()) {
                connected.add(new TargetReference(target.dimension, target.pos));
            }
            this.state = new BroadcasterState(broadcaster.getWorld().provider.getDimension(), broadcaster.getPos(),
                    broadcaster.isOwner(player), broadcaster.getConfiguredOutputAmperage(),
                    broadcaster.getEnergyStored(), broadcaster.getEnergyCapacity(), broadcaster.getVoltage(),
                    broadcaster.getInputPerSecond(), broadcaster.getFillTimeSeconds(),
                    broadcaster.getDepletionTimeSeconds(), broadcaster.getConnectedLoadEuPerTick(),
                    broadcaster.getScanResults(), broadcaster.getConnections(), broadcaster.getRemoteConnections(),
                    connected, broadcaster.getHigherBroadcasters());
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int dimension = buf.readInt();
            BlockPos pos = BlockPos.fromLong(buf.readLong());
            boolean canConfigure = buf.readBoolean();
            int outputAmperage = buf.readInt();
            long storedEu = buf.readLong();
            long capacityEu = buf.readLong();
            long voltage = buf.readLong();
            long inputEuPerSecond = buf.readLong();
            long fillTimeSeconds = buf.readLong();
            long depletionTimeSeconds = buf.readLong();
            long connectedLoadEuPerTick = buf.readLong();
            List<WirelessTarget> scanResults = readTargets(buf);
            List<WirelessTarget> connections = readTargets(buf);
            List<WirelessRemoteTarget> remoteConnections = readRemoteTargets(buf);
            List<HigherBroadcasterInfo> higherBroadcasters = readHigherBroadcasters(buf);
            Set<TargetReference> connectedTargets = new HashSet<>();
            for (WirelessTarget target : connections) {
                connectedTargets.add(new TargetReference(dimension, target.pos));
            }
            for (WirelessRemoteTarget target : remoteConnections) {
                connectedTargets.add(new TargetReference(target.dimension, target.pos));
            }
            this.state = new BroadcasterState(dimension, pos, canConfigure, outputAmperage, storedEu, capacityEu,
                    voltage, inputEuPerSecond, fillTimeSeconds, depletionTimeSeconds, connectedLoadEuPerTick,
                    scanResults, connections, remoteConnections, connectedTargets, higherBroadcasters);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(state.dimension);
            buf.writeLong(state.pos.toLong());
            buf.writeBoolean(state.canConfigure);
            buf.writeInt(state.outputAmperage);
            buf.writeLong(state.storedEu);
            buf.writeLong(state.capacityEu);
            buf.writeLong(state.voltage);
            buf.writeLong(state.inputEuPerSecond);
            buf.writeLong(state.fillTimeSeconds);
            buf.writeLong(state.depletionTimeSeconds);
            buf.writeLong(state.connectedLoadEuPerTick);
            writeTargets(buf, state.scanResults);
            writeTargets(buf, state.connections);
            writeRemoteTargets(buf, state.remoteConnections);
            writeHigherBroadcasters(buf, state.higherBroadcasters);
        }

        private static void writeTargets(ByteBuf buf, List<WirelessTarget> targets) {
            buf.writeInt(targets.size());
            for (WirelessTarget target : targets) {
                buf.writeLong(target.pos.toLong());
                ByteBufUtils.writeUTF8String(buf, target.name);
                buf.writeByte(target.kind.ordinal());
                buf.writeInt(target.tier);
                buf.writeLong(target.voltage);
                buf.writeLong(target.amperage);
            }
        }

        private static List<WirelessTarget> readTargets(ByteBuf buf) {
            int count = Math.min(4096, buf.readInt());
            List<WirelessTarget> targets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                BlockPos pos = BlockPos.fromLong(buf.readLong());
                String name = ByteBufUtils.readUTF8String(buf);
                TargetKind kind = kindFromByte(buf.readByte());
                int tier = Math.max(0, buf.readInt());
                long voltage = Math.max(1L, buf.readLong());
                long amperage = Math.max(1L, buf.readLong());
                targets.add(new WirelessTarget(pos, name, kind, tier, voltage, amperage));
            }
            return targets;
        }

        private static void writeRemoteTargets(ByteBuf buf, List<WirelessRemoteTarget> targets) {
            buf.writeInt(targets.size());
            for (WirelessRemoteTarget target : targets) {
                buf.writeInt(target.dimension);
                buf.writeLong(target.pos.toLong());
                ByteBufUtils.writeUTF8String(buf, target.name);
                buf.writeByte(target.kind.ordinal());
                buf.writeInt(target.tier);
                buf.writeLong(target.voltage);
                buf.writeLong(target.amperage);
            }
        }

        private static List<WirelessRemoteTarget> readRemoteTargets(ByteBuf buf) {
            int count = Math.min(4096, buf.readInt());
            List<WirelessRemoteTarget> targets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int dimension = buf.readInt();
                BlockPos pos = BlockPos.fromLong(buf.readLong());
                String name = ByteBufUtils.readUTF8String(buf);
                TargetKind kind = kindFromByte(buf.readByte());
                int tier = Math.max(0, buf.readInt());
                long voltage = Math.max(1L, buf.readLong());
                long amperage = Math.max(1L, buf.readLong());
                targets.add(new WirelessRemoteTarget(dimension, pos, name, kind, tier, voltage, amperage));
            }
            return targets;
        }

        private static void writeHigherBroadcasters(ByteBuf buf, List<HigherBroadcasterInfo> broadcasters) {
            buf.writeInt(broadcasters.size());
            for (HigherBroadcasterInfo info : broadcasters) {
                buf.writeLong(info.pos.toLong());
                buf.writeInt(info.tier);
                buf.writeLong(info.distanceSquared);
            }
        }

        private static List<HigherBroadcasterInfo> readHigherBroadcasters(ByteBuf buf) {
            int count = Math.min(64, buf.readInt());
            List<HigherBroadcasterInfo> broadcasters = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                broadcasters.add(new HigherBroadcasterInfo(BlockPos.fromLong(buf.readLong()), buf.readInt(),
                        buf.readLong()));
            }
            return broadcasters;
        }

        private static TargetKind kindFromByte(byte ordinal) {
            int clamped = Math.max(0, Math.min(TargetKind.values().length - 1, ordinal));
            return TargetKind.values()[clamped];
        }
    }

    public static final class StateHandler implements IMessageHandler<StateMessage, IMessage> {
        @Override
        public IMessage onMessage(final StateMessage message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                final BroadcasterState state = message.state;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        ClientBroadcasterState.put(state);
                    }
                });
            }
            return null;
        }
    }

    public static final class RejectedMessage implements IMessage {
        private BlockPos pos;
        private ConnectionRejection rejection;

        public RejectedMessage() {
        }

        RejectedMessage(BlockPos pos, ConnectionRejection rejection) {
            this.pos = pos;
            this.rejection = rejection;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.pos = BlockPos.fromLong(buf.readLong());
            int ordinal = Math.max(0, Math.min(ConnectionRejection.values().length - 1, buf.readInt()));
            this.rejection = ConnectionRejection.values()[ordinal];
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(pos.toLong());
            buf.writeInt(rejection.ordinal());
        }
    }

    public static final class RejectedHandler implements IMessageHandler<RejectedMessage, IMessage> {
        @Override
        public IMessage onMessage(final RejectedMessage message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                final BlockPos pos = message.pos;
                final ConnectionRejection rejection = message.rejection;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        ClientBroadcasterState.putConnectionRejection(pos, rejection);
                    }
                });
            }
            return null;
        }
    }

    public static final class ActionMessage implements IMessage {
        private BlockPos pos;
        private ServerAction action;
        private List<TargetReference> selectedTargets;

        public ActionMessage() {
        }

        ActionMessage(BlockPos pos, ServerAction action, List<TargetReference> selectedTargets) {
            this.pos = pos;
            this.action = action;
            this.selectedTargets = selectedTargets;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.pos = BlockPos.fromLong(buf.readLong());
            int ordinal = Math.max(0, Math.min(ServerAction.values().length - 1, buf.readInt()));
            this.action = ServerAction.values()[ordinal];
            int count = Math.min(4096, buf.readInt());
            this.selectedTargets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                this.selectedTargets.add(new TargetReference(buf.readInt(), BlockPos.fromLong(buf.readLong())));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(pos.toLong());
            buf.writeInt(action.ordinal());
            buf.writeInt(selectedTargets.size());
            for (TargetReference reference : selectedTargets) {
                buf.writeInt(reference.dimension);
                buf.writeLong(reference.pos.toLong());
            }
        }
    }

    public static final class ActionHandler implements IMessageHandler<ActionMessage, IMessage> {
        @Override
        public IMessage onMessage(final ActionMessage message, MessageContext ctx) {
            if (ctx.side != Side.SERVER) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final ServerAction action = message.action;
            final List<TargetReference> selectedTargets = message.selectedTargets;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (!(player.openContainer instanceof WirelessEuContainer)) {
                        return;
                    }
                    if (!((WirelessEuContainer) player.openContainer).getPos().equals(pos)) {
                        return;
                    }
                    WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(player.world, pos);
                    if (machine == null) {
                        return;
                    }
                    machine.handleAction(player, action, selectedTargets);
                }
            });
            return null;
        }
    }
}
