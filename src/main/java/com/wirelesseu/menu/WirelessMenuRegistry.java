package com.wirelesseu.menu;

import com.wirelesseu.WirelessEuMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WirelessMenuRegistry {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, WirelessEuMod.MOD_ID);

    public static final RegistryObject<MenuType<WirelessBroadcasterMenu>> BROADCASTER = MENUS.register(
            "wireless_eu_broadcaster",
            () -> IForgeMenuType.create(WirelessBroadcasterMenu::new));

    private WirelessMenuRegistry() {
    }

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
