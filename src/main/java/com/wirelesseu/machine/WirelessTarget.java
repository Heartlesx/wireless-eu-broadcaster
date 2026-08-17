package com.wirelesseu.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public record WirelessTarget(BlockPos pos, String name, TargetKind kind, int tier, long voltage, long amperage) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putString("Name", name);
        tag.putByte("Kind", (byte) kind.ordinal());
        tag.putInt("Tier", tier);
        tag.putLong("Voltage", voltage);
        tag.putLong("Amperage", amperage);
        return tag;
    }

    public static WirelessTarget load(CompoundTag tag, int fallbackTier, long fallbackVoltage) {
        int ordinal = Math.max(0, Math.min(TargetKind.values().length - 1, tag.getByte("Kind")));
        return new WirelessTarget(
                BlockPos.of(tag.getLong("Pos")),
                tag.getString("Name"),
                TargetKind.values()[ordinal],
                tag.contains("Tier") ? tag.getInt("Tier") : fallbackTier,
                Math.max(1L, tag.contains("Voltage") ? tag.getLong("Voltage") : fallbackVoltage),
                Math.max(1L, tag.getLong("Amperage")));
    }
}
