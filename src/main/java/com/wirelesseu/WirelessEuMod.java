package com.wirelesseu;

import com.wirelesseu.client.WirelessEuRangePreview;
import com.wirelesseu.command.WirelessEuCommand;
import com.wirelesseu.gui.WirelessEuGuiHandler;
import com.wirelesseu.item.WirelessEuItems;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLInterModComms;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Keep VERSION in sync with mod_version in gradle.properties: "${version}" only gets expanded in mcmod.info, the
// annotation value is compiled as-is and FML reports it in the mod list and the client join handshake.
@Mod(modid = WirelessEuMod.MOD_ID, name = "Wireless EU", version = WirelessEuMod.VERSION,
        dependencies = "required-after:gregtech;required-after:gtlitecore;after:theoneprobe")
public final class WirelessEuMod {
    public static final String MOD_ID = "wireless_eu";
    public static final String VERSION = "2.1.1-forge-1.12.2";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static WirelessEuMod instance;

    public WirelessEuMod() {
        instance = this;
        WirelessEuItems.register();
        MinecraftForge.EVENT_BUS.register(WirelessEuMachines.class);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        WirelessEuNetwork.register();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new WirelessEuGuiHandler());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) {
            WirelessEuRangePreview.register();
        }
        FMLInterModComms.sendFunctionMessage("theoneprobe", "getTheOneProbe",
                "com.wirelesseu.integration.WirelessEuTopPlugin");
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // Early enough to be seen by JEI/HEI (they build their recipe list at load complete) and late enough that
        // GT already registered the 16x battery buffer recipe in the recipe registry event.
        WirelessEuMachines.copyBatteryBufferRecipes();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new WirelessEuCommand());
        // Safety net: retry the tiers that had no source recipe while loading; already copied tiers are skipped.
        WirelessEuMachines.copyBatteryBufferRecipes();
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        WirelessEuMachines.copyBatteryBufferRecipes();
    }

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getWorld().isRemote || !event.getEntityPlayer().isSneaking()) {
            return;
        }
        WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(event.getWorld(), event.getPos());
        if (machine == null) {
            return;
        }
        if (event.getItemStack().isEmpty()) {
            machine.sendUpgradeRemainingMessage(event.getEntityPlayer());
            event.setCanceled(true);
            event.setCancellationResult(EnumActionResult.SUCCESS);
            return;
        }
        if (!machine.isUpgradeCircuit(event.getItemStack())) {
            return;
        }
        machine.insertUpgradeCircuits(event.getEntityPlayer(), event.getHand());
        event.setCanceled(true);
        event.setCancellationResult(EnumActionResult.SUCCESS);
    }

    public static WirelessEuBroadcasterMachine getMachine(World world, BlockPos pos) {
        return WirelessEuMachines.getMachine(world, pos);
    }

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(event.getWorld(), event.getPos());
        if (machine == null) {
            return;
        }
        java.util.List<ItemStack> drops = new java.util.ArrayList<>();
        machine.addUpgradeCircuitDrops(drops);
        for (ItemStack stack : drops) {
            event.getWorld().spawnEntity(new EntityItem(event.getWorld(), event.getPos().getX() + 0.5D,
                    event.getPos().getY() + 0.5D, event.getPos().getZ() + 0.5D, stack));
        }
    }
}
