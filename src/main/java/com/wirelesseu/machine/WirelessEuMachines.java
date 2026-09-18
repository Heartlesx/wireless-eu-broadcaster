package com.wirelesseu.machine;

import com.wirelesseu.WirelessEuMod;
import gregtech.api.GTValues;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.registry.MTEManager;
import gregtech.api.metatileentity.registry.MTEManager.MTERegistryEvent;
import gregtech.api.metatileentity.registry.MTERegistry;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeBuilder;
import gregtech.api.recipes.RecipeMap;
import gregtech.common.metatileentities.MetaTileEntities;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** GT 1.12.2 registration is kept separate from normal Forge item registration. */
public final class WirelessEuMachines {
    public static final int[] TIERS = {
            GTValues.LV, GTValues.MV, GTValues.HV, GTValues.EV, GTValues.IV,
            GTValues.LuV, GTValues.ZPM, GTValues.UV, GTValues.UHV, GTValues.UEV,
            GTValues.UIV, GTValues.UXV, GTValues.OpV, GTValues.MAX
    };
    public static final String[] TIER_IDS = {
            "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv", "uev",
            "uiv", "uxv", "opv", "max"
    };
    public static final WirelessEuBroadcasterMachine[] BROADCASTERS = new WirelessEuBroadcasterMachine[GTValues.MAX + 1];
    private static final Set<Integer> COPIED_TIERS = new HashSet<>();
    private static MTERegistry registry;
    private static boolean registered;

    private WirelessEuMachines() {
    }

    @SubscribeEvent
    public static void registerMetaTileEntities(MTERegistryEvent event) {
        if (registered) {
            return;
        }
        registered = true;
        registry = MTEManager.getInstance().createRegistry(WirelessEuMod.MOD_ID);
        registry.setBlock(new gregtech.api.block.machines.BlockMachine());
        for (int index = 0; index < TIERS.length; index++) {
            int tier = TIERS[index];
            ResourceLocation id = new ResourceLocation(WirelessEuMod.MOD_ID,
                    TIER_IDS[index] + "_wireless_eu_broadcaster");
            WirelessEuBroadcasterMachine machine = new WirelessEuBroadcasterMachine(id, tier);
            BROADCASTERS[tier] = machine;
            registry.register(2000 + index, id, machine);
        }
        WirelessEuMod.LOGGER.info("Registered {} GT Lite wireless EU broadcasters", TIERS.length);
    }

    public static WirelessEuBroadcasterMachine getMachine(World world, net.minecraft.util.math.BlockPos pos) {
        if (world == null) {
            return null;
        }
        net.minecraft.tileentity.TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof MetaTileEntityHolder)) {
            return null;
        }
        MetaTileEntityHolder holder = (MetaTileEntityHolder) tile;
        MetaTileEntity metaTileEntity = holder.getMetaTileEntity();
        return metaTileEntity instanceof WirelessEuBroadcasterMachine ?
                (WirelessEuBroadcasterMachine) metaTileEntity : null;
    }

    /**
     * GT registers the 16x battery buffer as a crafting-table shaped recipe (ModHandler.addShapedRecipe), not as a
     * RecipeMap recipe, so the table recipe is the real source; RecipeMap recipes are copied too in case a pack adds
     * them. Original recipes stay untouched. Already copied tiers are skipped, so calling this again is safe.
     */
    public static void copyBatteryBufferRecipes() {
        if (!registered) {
            WirelessEuMod.LOGGER.warn("Wireless EU: broadcaster recipes skipped, meta tile entities not registered yet");
            return;
        }
        int craftingCopied = 0;
        int machineCopied = 0;
        List<String> pending = new ArrayList<>();
        for (int index = 0; index < TIERS.length; index++) {
            int tier = TIERS[index];
            if (COPIED_TIERS.contains(tier)) {
                continue;
            }
            MetaTileEntity battery = getBatteryBuffer(tier);
            if (battery == null || BROADCASTERS[tier] == null) {
                pending.add(TIER_IDS[index] + ":no-battery-buffer");
                continue;
            }
            ItemStack batteryStack = battery.getStackForm();
            ItemStack broadcasterStack = BROADCASTERS[tier].getStackForm();
            int crafting = copyCraftingTableRecipes(batteryStack, broadcasterStack, TIER_IDS[index]);
            if (crafting == 0) {
                pending.add(TIER_IDS[index] + ":no-crafting-recipe");
                continue;
            }
            COPIED_TIERS.add(tier);
            craftingCopied += crafting;
            machineCopied += copyRecipeMapRecipes(batteryStack, broadcasterStack);
        }
        WirelessEuMod.LOGGER.info("Wireless EU: copied {} crafting-table and {} GT machine recipes, {} of {} tiers done",
                craftingCopied, machineCopied, COPIED_TIERS.size(), TIERS.length);
        if (!pending.isEmpty()) {
            WirelessEuMod.LOGGER.warn("Wireless EU: no 16x battery buffer recipe found for {}", pending);
        }
    }

    private static int copyCraftingTableRecipes(ItemStack batteryStack, ItemStack broadcasterStack, String tierId) {
        List<IRecipe> matches = new ArrayList<>();
        for (IRecipe recipe : ForgeRegistries.RECIPES) {
            if (recipe instanceof IShapedRecipe
                    && containsItem(Collections.singletonList(recipe.getRecipeOutput()), batteryStack)) {
                matches.add(recipe);
            }
        }
        int copied = 0;
        for (IRecipe recipe : matches) {
            IShapedRecipe shaped = (IShapedRecipe) recipe;
            ResourceLocation name = new ResourceLocation(WirelessEuMod.MOD_ID,
                    "broadcaster_" + tierId + (copied == 0 ? "" : "_" + copied));
            copied++;
            if (ForgeRegistries.RECIPES.containsKey(name)) {
                continue;
            }
            ShapedRecipes copy = new ShapedRecipes(recipe.getGroup(), shaped.getRecipeWidth(),
                    shaped.getRecipeHeight(), recipe.getIngredients(), broadcasterStack.copy());
            copy.setRegistryName(name);
            ForgeRegistries.RECIPES.register(copy);
        }
        return copied;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int copyRecipeMapRecipes(ItemStack batteryStack, ItemStack broadcasterStack) {
        int copied = 0;
        for (RecipeMap<?> rawMap : RecipeMap.getRecipeMaps()) {
            List<Recipe> matches = new ArrayList<>();
            for (Recipe recipe : rawMap.getRecipeList()) {
                if (containsItem(recipe.getAllItemOutputs(), batteryStack)) {
                    matches.add(recipe);
                }
            }
            for (Recipe recipe : matches) {
                RecipeBuilder builder = ((RecipeMap) rawMap).recipeBuilder();
                builder.inputIngredients(recipe.getInputs());
                builder.fluidInputs(recipe.getFluidInputs());
                List<ItemStack> outputs = new ArrayList<>();
                for (ItemStack output : recipe.getOutputs()) {
                    outputs.add(containsItem(Collections.singletonList(output), batteryStack)
                            ? broadcasterStack.copy() : output.copy());
                }
                builder.outputs(outputs);
                builder.chancedOutputs(recipe.getChancedOutputs().getChancedEntries())
                        .chancedOutputLogic(recipe.getChancedOutputs().getChancedOutputLogic());
                builder.fluidOutputs(recipe.getFluidOutputs());
                builder.chancedFluidOutputs(recipe.getChancedFluidOutputs().getChancedEntries())
                        .chancedFluidOutputLogic(recipe.getChancedFluidOutputs().getChancedOutputLogic());
                builder.duration(recipe.getDuration());
                builder.EUt(recipe.getEUt());
                builder.buildAndRegister();
                copied++;
            }
        }
        return copied;
    }

    private static MetaTileEntity getBatteryBuffer(int tier) {
        if (MetaTileEntities.BATTERY_BUFFER == null || MetaTileEntities.BATTERY_BUFFER.length <= 2
                || MetaTileEntities.BATTERY_BUFFER[2] == null || MetaTileEntities.BATTERY_BUFFER[2].length <= tier) {
            return null;
        }
        return MetaTileEntities.BATTERY_BUFFER[2][tier];
    }

    private static boolean containsItem(List<ItemStack> outputs, ItemStack expected) {
        for (ItemStack output : outputs) {
            if (output != null && !output.isEmpty() && ItemStack.areItemsEqual(output, expected)) {
                return true;
            }
        }
        return false;
    }
}
