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
        private static final String KEY_CONNECTIONS = "Connections";
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
            data.putLong(KEY_STORED, broadcaster.energyContainer.getEnergyStored());
            data.putLong(KEY_CAPACITY, broadcaster.energyContainer.getEnergyCapacity());
            data.putLong(KEY_CONNECTIONS, broadcaster.getConnections().size());
            data.putLong(KEY_ETA, broadcaster.getFillTimeSeconds());
            data.putLong(KEY_DEPLETION_ETA, broadcaster.getDepletionTimeSeconds());
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.getBoolean(KEY_IS_BROADCASTER)) {
                return;
            }
            tooltip.add(Component.literal("连接数量：" + data.getLong(KEY_CONNECTIONS)));
            tooltip.add(Component.literal("EU 缓存：" + WirelessEuBroadcasterMachine.formatEu(data.getLong(KEY_STORED))
                    + " / " + WirelessEuBroadcasterMachine.formatEu(data.getLong(KEY_CAPACITY))));
            tooltip.add(Component.literal("充满所需时间："
                    + WirelessEuBroadcasterMachine.formatDuration(data.getLong(KEY_ETA))));
            tooltip.add(Component.literal("预计耗尽时间："
                    + WirelessEuBroadcasterMachine.formatDuration(data.getLong(KEY_DEPLETION_ETA))));
        }
    }
}
