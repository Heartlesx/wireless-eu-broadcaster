package com.wirelesseu.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class WirelessEuConfig {
    private static final ForgeConfigSpec.BooleanValue ENERGY_AMPLIFICATION;
    private static final ForgeConfigSpec.BooleanValue AUTO_CONNECT;
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("general");
        ENERGY_AMPLIFICATION = builder
                .comment("Multiply incoming EU by 2.5. When false, incoming EU is stored at 1x.",
                        "Applies to normal EU and laser input. Restart required; the server config wins in multiplayer.")
                .define("enableEnergyAmplification", true);
        AUTO_CONNECT = builder
                .comment("Automatically connect compatible GT machines placed by a player who carries an Energy",
                        "Binding Card bound to a broadcaster. Restart required; the server config wins in multiplayer.")
                .define("enableAutoConnect", true);
        builder.pop();
        SPEC = builder.build();
    }

    private WirelessEuConfig() {
    }

    public static boolean energyAmplification() {
        return ENERGY_AMPLIFICATION.get();
    }

    public static boolean autoConnect() {
        return AUTO_CONNECT.get();
    }
}
