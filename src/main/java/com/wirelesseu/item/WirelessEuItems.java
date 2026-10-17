package com.wirelesseu.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.init.Items;

import com.wirelesseu.WirelessEuMod;

public final class WirelessEuItems {
    public static EnergyBindingCardItem ENERGY_BINDING_CARD;
    private static boolean registered;

    private WirelessEuItems() {
    }

    public static void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(WirelessEuItems.class);
        }
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        if (ENERGY_BINDING_CARD == null) {
            ENERGY_BINDING_CARD = new EnergyBindingCardItem();
            event.getRegistry().register(ENERGY_BINDING_CARD);
        }
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        if (ENERGY_BINDING_CARD == null) {
            return;
        }
        ResourceLocation bindingCardId = new ResourceLocation(WirelessEuMod.MOD_ID, "energy_binding_card");
        event.getRegistry().register(new ShapedOreRecipe(bindingCardId, new ItemStack(ENERGY_BINDING_CARD),
                "DED", "EEE", "GSG", 'D', Items.DIAMOND, 'E', Items.ENDER_EYE,
                'G', Items.EMERALD, 'S', Items.STICK).setRegistryName(bindingCardId));
    }
}
