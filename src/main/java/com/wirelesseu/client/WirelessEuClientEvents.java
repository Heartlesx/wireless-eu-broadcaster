package com.wirelesseu.client;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.menu.WirelessMenuRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = WirelessEuMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WirelessEuClientEvents {
    private WirelessEuClientEvents() {
    }

    @SubscribeEvent
    public static void registerMenuScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(WirelessMenuRegistry.BROADCASTER.get(),
                WirelessBroadcasterScreen::new));
    }
}
