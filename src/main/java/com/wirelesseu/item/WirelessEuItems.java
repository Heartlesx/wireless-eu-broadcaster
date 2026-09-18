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
    public static LinkCardItem LINK_CARD;
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
        if (LINK_CARD == null) {
            LINK_CARD = new LinkCardItem();
            event.getRegistry().register(LINK_CARD);
        }
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        if (LINK_CARD == null) {
            return;
        }
        ResourceLocation linkCardId = new ResourceLocation(WirelessEuMod.MOD_ID, "link_card");
        event.getRegistry().register(new ShapedOreRecipe(linkCardId, new ItemStack(LINK_CARD),
                "DED", "EEE", "GSG", 'D', Items.DIAMOND, 'E', Items.ENDER_EYE,
                'G', Items.EMERALD, 'S', Items.STICK).setRegistryName(linkCardId));
        event.getRegistry().register(new ClearLinkCardRecipe().setRegistryName(
                new ResourceLocation(WirelessEuMod.MOD_ID, "clear_link_card")));
    }
}
