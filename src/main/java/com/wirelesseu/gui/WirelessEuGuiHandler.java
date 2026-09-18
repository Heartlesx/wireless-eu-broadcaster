package com.wirelesseu.gui;

import com.wirelesseu.client.WirelessBroadcasterScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

public final class WirelessEuGuiHandler implements IGuiHandler {
    public static final int BROADCASTER = 0;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return id == BROADCASTER ? new WirelessEuContainer(new BlockPos(x, y, z)) : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return id == BROADCASTER ? new WirelessBroadcasterScreen(new BlockPos(x, y, z)) : null;
    }
}
