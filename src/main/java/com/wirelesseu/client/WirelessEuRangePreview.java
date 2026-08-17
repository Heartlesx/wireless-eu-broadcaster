package com.wirelesseu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = WirelessEuMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WirelessEuRangePreview {
    private static BlockPos previewOrigin;
    private static ResourceKey<Level> previewDimension;

    private WirelessEuRangePreview() {
    }

    public static void toggle(BlockPos pos) {
        if (pos.equals(previewOrigin)) {
            previewOrigin = null;
            previewDimension = null;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            previewOrigin = pos.immutable();
            previewDimension = minecraft.level.dimension();
        }
    }

    public static boolean isEnabled(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        return pos.equals(previewOrigin) && minecraft.level != null && minecraft.level.dimension().equals(previewDimension);
    }

    @SubscribeEvent
    public static void renderRange(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || previewOrigin == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(previewDimension)) {
            return;
        }

        Vec3 cameraPosition = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());
        int range = WirelessEuBroadcasterMachine.RANGE;

        poseStack.pushPose();
        poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        LevelRenderer.renderLineBox(poseStack, vertexConsumer,
                previewOrigin.getX() - range, previewOrigin.getY() - range, previewOrigin.getZ() - range,
                previewOrigin.getX() + range + 1.0D, previewOrigin.getY() + range + 1.0D,
                previewOrigin.getZ() + range + 1.0D, 0.15F, 1.0F, 0.25F, 0.95F);
        bufferSource.endBatch(RenderType.lines());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }
}
