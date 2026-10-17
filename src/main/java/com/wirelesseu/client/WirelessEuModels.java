package com.wirelesseu.client;

import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.item.WirelessEuItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 1.12.2 only resolves an item model when the item is registered here: {@code ModelLoader.setCustomModelResourceLocation}
 * feeds the item mesher, and an item without an entry renders the missing model no matter what sits in
 * {@code assets/<modid>/models/item}. The 1.20.1 original resolved models by registry name automatically, which is
 * why this step was missing after the port and both cards showed the purple/black missing model.
 */
public final class WirelessEuModels {
    private WirelessEuModels() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(WirelessEuModels.class);
        MinecraftForge.EVENT_BUS.register(WirelessEuBroadcasterRenderer.class);
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(WirelessEuItems.ENERGY_BINDING_CARD, 0,
                new ModelResourceLocation(new ResourceLocation(WirelessEuMod.MOD_ID, "energy_binding_card"), "inventory"));
    }
}
