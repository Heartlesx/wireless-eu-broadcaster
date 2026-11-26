package com.wirelesseu.integration.jade;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.math.BigInteger;

@WailaPlugin(WirelessEuMod.MOD_ID)
public final class WirelessEuJadePlugin implements IWailaPlugin {
    private static final BroadcasterProvider PROVIDER = new BroadcasterProvider();

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PROVIDER, MetaMachineBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PROVIDER, MetaMachineBlock.class);
    }

    private static final class BroadcasterProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        private static final String KEY_IS_BROADCASTER = "WirelessEuBroadcaster";
        private static final String KEY_STORED = "StoredEu";
        private static final String KEY_CAPACITY = "CapacityEu";
        private static final String KEY_TIER = "Tier";
        private static final String KEY_INPUT = "InputEuPerSecond";
        private static final String KEY_LOCAL_CONNECTIONS = "LocalConnections";
        private static final String KEY_REMOTE_CONNECTIONS = "RemoteConnections";
        private static final String KEY_ETA = "FillEta";
        private static final String KEY_DEPLETION_ETA = "DepletionEta";

        @Override
        public ResourceLocation getUid() {
            return new ResourceLocation(WirelessEuMod.MOD_ID, "wireless_eu_broadcaster");
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity blockEntity)
                    || !(blockEntity.getMetaMachine() instanceof WirelessEuBroadcasterMachine broadcaster)) {
                return;
            }
            data.putBoolean(KEY_IS_BROADCASTER, true);
            data.putString(KEY_STORED, broadcaster.getStoredEu().toString());
            data.putString(KEY_CAPACITY, broadcaster.getCapacityEu().toString());
            data.putInt(KEY_TIER, broadcaster.getTier());
            data.putString(KEY_INPUT, broadcaster.getInputPerSecond().toString());
            data.putInt(KEY_LOCAL_CONNECTIONS, broadcaster.getConnections().size());
            data.putInt(KEY_REMOTE_CONNECTIONS, broadcaster.getRemoteConnections().size());
            data.putString(KEY_ETA, broadcaster.getFillTimeSeconds().toString());
            data.putString(KEY_DEPLETION_ETA, broadcaster.getDepletionTimeSeconds().toString());
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.getBoolean(KEY_IS_BROADCASTER)) {
                return;
            }
            BigInteger stored = parse(data.getString(KEY_STORED));
            BigInteger capacity = parse(data.getString(KEY_CAPACITY));
            BigInteger inputPerSecond = parse(data.getString(KEY_INPUT));
            tooltip.add(Component.literal("无线 EU 广播器：" + WirelessEuBroadcasterMachine.tierName(data.getInt(KEY_TIER))));
            tooltip.add(Component.literal("EU 缓存：" + WirelessEuBroadcasterMachine.formatEu(stored) + " / "
                    + WirelessEuBroadcasterMachine.formatEu(capacity) + " (" + percent(stored, capacity) + ")"));
            tooltip.add(Component.literal("放大后输入："
                    + WirelessEuBroadcasterMachine.formatCompactEu(inputPerSecond.divide(BigInteger.valueOf(20L)))
                    + " EU/t"));
            tooltip.add(Component.literal("连接数量：" + (data.getInt(KEY_LOCAL_CONNECTIONS)
                    + data.getInt(KEY_REMOTE_CONNECTIONS)) + "（本地 " + data.getInt(KEY_LOCAL_CONNECTIONS)
                    + " / 远程 " + data.getInt(KEY_REMOTE_CONNECTIONS) + "）"));
            tooltip.add(Component.literal("充满所需时间："
                    + WirelessEuBroadcasterMachine.formatDuration(parse(data.getString(KEY_ETA)))));
            tooltip.add(Component.literal("预计耗尽时间："
                    + WirelessEuBroadcasterMachine.formatDuration(parse(data.getString(KEY_DEPLETION_ETA)))));
        }

        private static String percent(BigInteger stored, BigInteger capacity) {
            if (capacity.signum() <= 0) {
                return "0%";
            }
            return stored.multiply(BigInteger.valueOf(100L)).divide(capacity) + "%";
        }

        private static BigInteger parse(String value) {
            try {
                return new BigInteger(value);
            } catch (NumberFormatException exception) {
                return BigInteger.ZERO;
            }
        }
    }
}
