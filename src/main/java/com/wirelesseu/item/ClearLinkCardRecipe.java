package com.wirelesseu.item;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;

/** Clears a bound link card without changing the original card item. */
public final class ClearLinkCardRecipe extends ShapelessRecipes {
    public ClearLinkCardRecipe() {
        super("wireless_eu.clear_link_card", new ItemStack(WirelessEuItems.LINK_CARD), NonNullList.create());
    }

    @Override
    public boolean matches(InventoryCrafting inventory, World world) {
        int cards = 0;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() != WirelessEuItems.LINK_CARD || ++cards > 1) {
                return false;
            }
        }
        return cards == 1;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inventory) {
        return new ItemStack(WirelessEuItems.LINK_CARD);
    }
}
