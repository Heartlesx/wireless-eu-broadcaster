package com.wirelesseu.item;

import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs;
import com.wirelesseu.WirelessEuMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WirelessEuItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            WirelessEuMod.MOD_ID);
    public static final RegistryObject<Item> ENERGY_BINDING_CARD = ITEMS.register("energy_binding_card",
            () -> new EnergyBindingCardItem(new Item.Properties().stacksTo(1)));

    private WirelessEuItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(WirelessEuItems::addToMachineCreativeTab);
    }

    private static void addToMachineCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == GTCreativeModeTabs.MACHINE.get()) {
            event.accept(ENERGY_BINDING_CARD.get());
        }
    }
}
