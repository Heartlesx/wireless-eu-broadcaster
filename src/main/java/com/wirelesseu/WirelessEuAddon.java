package com.wirelesseu;

import com.gregtechceu.gtceu.api.addon.GTAddon;
import com.gregtechceu.gtceu.api.addon.IGTAddon;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.data.recipe.GTCraftingComponents;
import com.gregtechceu.gtceu.data.recipe.misc.MetaTileEntityLoader;
import com.wirelesseu.machine.WirelessEuMachines;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraftforge.common.Tags;

import java.util.function.Consumer;

@GTAddon
public final class WirelessEuAddon implements IGTAddon {
    @Override
    public GTRegistrate getRegistrate() {
        return WirelessEuMachines.REGISTRATE;
    }

    @Override
    public void initializeAddon() {
    }

    @Override
    public void addRecipes(Consumer<FinishedRecipe> provider) {
        MetaTileEntityLoader.registerMachineRecipe(provider, WirelessEuMachines.BROADCASTERS,
                "WTW", "WMW",
                'M', GTCraftingComponents.HULL,
                'W', GTCraftingComponents.WIRE_HEX,
                'T', Tags.Items.CHESTS_WOODEN);
    }

    @Override
    public String addonModId() {
        return WirelessEuMod.MOD_ID;
    }
}
