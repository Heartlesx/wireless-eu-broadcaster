package com.wirelesseu.menu;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public final class WirelessBroadcasterMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final WirelessEuBroadcasterMachine machine;

    public WirelessBroadcasterMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public WirelessBroadcasterMenu(int containerId, Inventory inventory, WirelessEuBroadcasterMachine machine) {
        this(containerId, inventory, machine.getPos());
    }

    private WirelessBroadcasterMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(WirelessMenuRegistry.BROADCASTER.get(), containerId);
        this.pos = pos;
        MetaMachine metaMachine = MetaMachine.getMachine(inventory.player.level(), pos);
        this.machine = metaMachine instanceof WirelessEuBroadcasterMachine broadcaster ? broadcaster : null;
    }

    public BlockPos getPos() {
        return pos;
    }

    public WirelessEuBroadcasterMachine getMachine() {
        return machine;
    }

    @Override
    public boolean stillValid(Player player) {
        return machine != null && !machine.isInValid()
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
