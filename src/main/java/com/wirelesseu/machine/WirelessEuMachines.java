package com.wirelesseu.machine;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.wirelesseu.WirelessEuMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.IEventBus;

public final class WirelessEuMachines {
    private static final int[] TIERS = {
            GTValues.LV, GTValues.MV, GTValues.HV, GTValues.EV, GTValues.IV,
            GTValues.LuV, GTValues.ZPM, GTValues.UV, GTValues.UHV, GTValues.UEV, GTValues.MAX
    };
    private static final String[] IDS = {
            "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv", "uev", "max"
    };

    public static final GTRegistrate REGISTRATE = GTRegistrate.create(WirelessEuMod.MOD_ID);
    public static final MachineDefinition[] BROADCASTERS = new MachineDefinition[GTValues.TIER_COUNT];
    private static boolean machinesRegistered;

    private static void registerMachines(GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition> event) {
        if (machinesRegistered) {
            return;
        }
        machinesRegistered = true;
        for (int index = 0; index < TIERS.length; index++) {
            int tier = TIERS[index];
            String id = IDS[index] + "_wireless_eu_broadcaster";
            BROADCASTERS[tier] = REGISTRATE.machine(id, holder -> new WirelessEuBroadcasterMachine(holder, tier))
                    .tier(tier)
                    .rotationState(RotationState.ALL)
                    // This pack initializes GTMachineModels after addon constructors. Static resources below supply
                    // the battery-buffer model without forcing that registry during mod construction.
                    .blockModel((context, provider) -> {
                    })
                    .appearanceBlock(() -> GTMachines.BATTERY_BUFFER_16[tier].getBlock())
                    .tooltips(
                            Component.literal("无线 EU 广播器"),
                            Component.literal("同维度 384 格范围"),
                            Component.literal("输入 EU 放大为 2.5 倍"))
                    .register();
        }
    }

    private WirelessEuMachines() {
    }

    public static void register(IEventBus modBus) {
        REGISTRATE.registerRegistrate();
        modBus.addGenericListener(MachineDefinition.class, WirelessEuMachines::registerMachines);
    }
}
