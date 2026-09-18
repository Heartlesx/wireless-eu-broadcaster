package com.wirelesseu.gui;

import com.wirelesseu.machine.WirelessEuMachines;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.math.BlockPos;

/**
 * Empty container that keeps the broadcaster terminal a normal Forge GUI: it owns the window id, closes when the
 * player walks away, and lets the server verify that the player really has this screen open.
 */
public final class WirelessEuContainer extends Container {
    private final BlockPos pos;

    public WirelessEuContainer(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return WirelessEuMachines.getMachine(player.world, pos) != null
                && player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }
}
