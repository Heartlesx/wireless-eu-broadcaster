package com.wirelesseu.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public record WirelessRemoteTarget(ResourceLocation dimension, BlockPos pos, String name, TargetKind kind,
                                   int tier, long voltage, long amperage) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putLong("Pos", pos.asLong());
        tag.putString("Name", name);
        tag.putByte("Kind", (byte) kind.ordinal());
        tag.putInt("Tier", tier);
        tag.putLong("Voltage", voltage);
        tag.putLong("Amperage", amperage);
        return tag;
    }

    public static WirelessRemoteTarget load(CompoundTag tag) {
        if (!tag.contains("Dimension", Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.parse(tag.getString("Dimension"));
        int ordinal = Math.max(0, Math.min(TargetKind.values().length - 1, tag.getByte("Kind")));
        return new WirelessRemoteTarget(dimension, BlockPos.of(tag.getLong("Pos")), tag.getString("Name"),
                TargetKind.values()[ordinal], Math.max(0, tag.getInt("Tier")),
                Math.max(1L, tag.getLong("Voltage")), Math.max(1L, tag.getLong("Amperage")));
    }
}
