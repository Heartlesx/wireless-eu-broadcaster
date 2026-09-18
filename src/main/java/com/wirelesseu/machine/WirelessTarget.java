package com.wirelesseu.machine;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

public final class WirelessTarget {
    public final BlockPos pos;
    public final String name;
    public final TargetKind kind;
    public final int tier;
    public final long voltage;
    public final long amperage;

    public WirelessTarget(BlockPos pos, String name, TargetKind kind, int tier, long voltage, long amperage) {
        this.pos = pos;
        this.name = name;
        this.kind = kind;
        this.tier = tier;
        this.voltage = voltage;
        this.amperage = amperage;
    }

    public NBTTagCompound save() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("Pos", pos.toLong());
        tag.setString("Name", name);
        tag.setByte("Kind", (byte) kind.ordinal());
        tag.setInteger("Tier", tier);
        tag.setLong("Voltage", voltage);
        tag.setLong("Amperage", amperage);
        return tag;
    }

    public static WirelessTarget load(NBTTagCompound tag, int fallbackTier, long fallbackVoltage) {
        int ordinal = Math.max(0, Math.min(TargetKind.values().length - 1, tag.getByte("Kind")));
        return new WirelessTarget(BlockPos.fromLong(tag.getLong("Pos")), tag.getString("Name"),
                TargetKind.values()[ordinal], tag.hasKey("Tier") ? tag.getInteger("Tier") : fallbackTier,
                Math.max(1L, tag.hasKey("Voltage") ? tag.getLong("Voltage") : fallbackVoltage),
                Math.max(1L, tag.getLong("Amperage")));
    }
}
