package com.wirelesseu;

import com.wirelesseu.machine.WirelessEuMachines;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.wirelesseu.item.WirelessEuItems;
import com.wirelesseu.menu.WirelessMenuRegistry;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(WirelessEuMod.MOD_ID)
public final class WirelessEuMod {
    public static final String MOD_ID = "wireless_eu";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public WirelessEuMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        WirelessEuItems.register(modBus);
        WirelessEuMachines.register(modBus);
        WirelessMenuRegistry.register(modBus);
        WirelessEuNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WirelessEuMod::handleUpgradeInteraction);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, WirelessEuMod::handleBlockBreak);
    }

    private static void handleUpgradeInteraction(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !event.getEntity().isShiftKeyDown()
                || !(MetaMachine.getMachine(event.getLevel(), event.getPos())
                instanceof WirelessEuBroadcasterMachine broadcaster)
                || !broadcaster.isUpgradeCircuit(event.getItemStack())) {
            return;
        }
        if (broadcaster.getOwnerUUID() == null) {
            broadcaster.setOwnerUUID(event.getEntity().getUUID());
            broadcaster.markDirty();
        }
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            broadcaster.insertUpgradeCircuits(serverPlayer, event.getHand());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    private static void handleBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        if (com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(level, event.getPos())
                instanceof WirelessEuBroadcasterMachine broadcaster) {
            java.util.List<ItemStack> drops = new java.util.ArrayList<>();
            broadcaster.addUpgradeCircuitDrops(drops);
            for (ItemStack stack : drops) {
                Containers.dropItemStack(level, event.getPos().getX() + 0.5D, event.getPos().getY() + 0.5D,
                        event.getPos().getZ() + 0.5D, stack);
            }
        }
    }
}
