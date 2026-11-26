package com.wirelesseu;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.config.WirelessEuConfig;
import com.wirelesseu.event.AutoConnectHandler;
import com.wirelesseu.item.WirelessEuItems;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import com.wirelesseu.menu.WirelessMenuRegistry;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(WirelessEuMod.MOD_ID)
public final class WirelessEuMod {
    public static final String MOD_ID = "wireless_eu";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static boolean enableEnergyAmplification = true;
    public static boolean enableAutoConnect = true;

    public WirelessEuMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, WirelessEuConfig.SPEC);
        WirelessEuItems.register(modBus);
        WirelessEuMachines.register(modBus);
        WirelessMenuRegistry.register(modBus);
        WirelessEuNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WirelessEuMod::handleUpgradeInteraction);
        MinecraftForge.EVENT_BUS.register(AutoConnectHandler.class);
        modBus.addListener(WirelessEuMod::onConfigLoad);
    }

    private static void onConfigLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == WirelessEuConfig.SPEC) {
            enableEnergyAmplification = WirelessEuConfig.energyAmplification();
            enableAutoConnect = WirelessEuConfig.autoConnect();
        }
    }

    private static void handleUpgradeInteraction(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !event.getEntity().isShiftKeyDown()
                || !(MetaMachine.getMachine(event.getLevel(), event.getPos())
                instanceof WirelessEuBroadcasterMachine broadcaster)
                || !broadcaster.isUpgradeCircuit(event.getItemStack())) {
            return;
        }
        if (broadcaster.getOwnerUUID() == null) {
            broadcaster.setOwnerUUID(event.getEntity().getUUID());
            broadcaster.markDirty();
        }
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            broadcaster.insertUpgradeCircuits(serverPlayer, event.getHand());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }
}
