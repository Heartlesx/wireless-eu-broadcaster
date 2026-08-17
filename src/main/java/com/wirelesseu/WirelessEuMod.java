package com.wirelesseu;

import com.wirelesseu.machine.WirelessEuMachines;
import com.wirelesseu.menu.WirelessMenuRegistry;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(WirelessEuMod.MOD_ID)
public final class WirelessEuMod {
    public static final String MOD_ID = "wireless_eu";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public WirelessEuMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        WirelessEuMachines.register(modBus);
        WirelessMenuRegistry.register(modBus);
        WirelessEuNetwork.register();
    }
}
