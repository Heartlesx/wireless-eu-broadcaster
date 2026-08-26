package com.wirelesseu.item;

import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs;
import com.wirelesseu.WirelessEuMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

public final class WirelessEuItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            WirelessEuMod.MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, WirelessEuMod.MOD_ID);
    public static final RegistryObject<Item> LINK_CARD = ITEMS.register("link_card",
            () -> new LinkCardItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<RecipeSerializer<ClearLinkCardRecipe>> CLEAR_LINK_CARD =
            RECIPE_SERIALIZERS.register("clear_link_card",
                    () -> new SimpleCraftingRecipeSerializer<>(ClearLinkCardRecipe::new));

    private WirelessEuItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
        modBus.addListener(WirelessEuItems::addToMachineCreativeTab);
    }

    private static void addToMachineCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == GTCreativeModeTabs.MACHINE.get()) {
            event.accept(LINK_CARD.get());
        }
    }
}
