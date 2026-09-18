package com.wirelesseu.client;

import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Draws the 384 block scan cube of one broadcaster.
 *
 * <p>1.12.2 clips the world at {@code renderDistance * 32} blocks (1.20.1 clips at twice that), while the far corner
 * of this cube sits ~667 blocks away, so with the vanilla projection only fragments of the cube survive. The far
 * plane is therefore pushed out for this one draw. That makes the depth values of these lines disagree with the
 * terrain depth buffer, so the cube is drawn without the depth test - it stays complete and continuous, at the cost
 * of showing through terrain.</p>
 */
public final class WirelessEuRangePreview {
    private static final float LINE_WIDTH = 2.0F;
    private static final float RED = 0.15F;
    private static final float GREEN = 1.0F;
    private static final float BLUE = 0.25F;
    private static final float ALPHA = 0.95F;
    private static final double EXTENDED_FAR = 8192.0D;

    private static BlockPos previewOrigin;
    private static int previewDimension = Integer.MIN_VALUE;

    private WirelessEuRangePreview() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(WirelessEuRangePreview.class);
    }

    public static void toggle(BlockPos pos) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (pos.equals(previewOrigin) && minecraft.world != null
                && minecraft.world.provider.getDimension() == previewDimension) {
            previewOrigin = null;
            previewDimension = Integer.MIN_VALUE;
            return;
        }
        previewOrigin = pos.toImmutable();
        previewDimension = minecraft.world == null ? Integer.MIN_VALUE : minecraft.world.provider.getDimension();
    }

    public static boolean isEnabled(BlockPos pos) {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft.world != null && pos.equals(previewOrigin)
                && minecraft.world.provider.getDimension() == previewDimension;
    }

    @SubscribeEvent
    public static void renderRange(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (previewOrigin == null || minecraft.world == null
                || minecraft.world.provider.getDimension() != previewDimension) {
            return;
        }
        Entity camera = minecraft.getRenderViewEntity();
        if (camera == null) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        double cameraX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double cameraY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double cameraZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        int range = WirelessEuBroadcasterMachine.RANGE;
        double minX = previewOrigin.getX() - range;
        double minY = previewOrigin.getY() - range;
        double minZ = previewOrigin.getZ() - range;
        double maxX = previewOrigin.getX() + range + 1.0D;
        double maxY = previewOrigin.getY() + range + 1.0D;
        double maxZ = previewOrigin.getZ() + range + 1.0D;

        // Everything is wrapped in try/finally and every matrix operation sets the matrix mode first, so a corrupted
        // GL matrix mode left behind by another mod can neither unbalance our push/pop nor leave state behind.
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        boolean extendedProjection = false;
        try {
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.translate(-cameraX, -cameraY, -cameraZ);
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            // Fog is already disabled by renderWorldPass before this event and must stay disabled afterwards: the
            // HUD is drawn ~2000 units away and would otherwise be turned into the fog colour.
            GlStateManager.disableFog();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);
            GlStateManager.glLineWidth(LINE_WIDTH);
            extendedProjection = pushExtendedProjection();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            addEdge(buffer, minX, minY, minZ, minX, maxY, minZ);
            addEdge(buffer, maxX, minY, minZ, maxX, maxY, minZ);
            addEdge(buffer, maxX, minY, maxZ, maxX, maxY, maxZ);
            addEdge(buffer, minX, minY, maxZ, minX, maxY, maxZ);
            addEdge(buffer, minX, minY, minZ, maxX, minY, minZ);
            addEdge(buffer, maxX, minY, minZ, maxX, minY, maxZ);
            addEdge(buffer, maxX, minY, maxZ, minX, minY, maxZ);
            addEdge(buffer, minX, minY, maxZ, minX, minY, minZ);
            addEdge(buffer, minX, maxY, minZ, maxX, maxY, minZ);
            addEdge(buffer, maxX, maxY, minZ, maxX, maxY, maxZ);
            addEdge(buffer, maxX, maxY, maxZ, minX, maxY, maxZ);
            addEdge(buffer, minX, maxY, maxZ, minX, maxY, minZ);
            tessellator.draw();
        } finally {
            popExtendedProjection(extendedProjection);
            GlStateManager.glLineWidth(1.0F);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
        }
    }

    /** Rebuilds the current projection with the same fov/aspect/near but a far plane that covers the whole cube. */
    private static boolean pushExtendedProjection() {
        FloatBuffer projection = BufferUtils.createFloatBuffer(16);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        float nearFarA = projection.get(10);
        float nearFarB = projection.get(14);
        double denominator = (double) nearFarA * (double) nearFarA - 1.0D;
        if (denominator == 0.0D) {
            return false;
        }
        double depthRange = -2.0D * nearFarB / denominator;
        double sum = -nearFarA * depthRange;
        double near = (sum - depthRange) / 2.0D;
        double far = near + depthRange;
        if (near <= 0.0D || far <= near) {
            return false;
        }
        double extendedFar = Math.max(far, EXTENDED_FAR);
        projection.put(10, (float) ((extendedFar + near) / (near - extendedFar)));
        projection.put(14, (float) (2.0D * extendedFar * near / (near - extendedFar)));
        projection.rewind();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GL11.glLoadMatrix(projection);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        return true;
    }

    private static void popExtendedProjection(boolean pushed) {
        if (!pushed) {
            return;
        }
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
    }

    private static void addEdge(BufferBuilder buffer, double x1, double y1, double z1, double x2, double y2,
                                double z2) {
        buffer.pos(x1, y1, z1).color(RED, GREEN, BLUE, ALPHA).endVertex();
        buffer.pos(x2, y2, z2).color(RED, GREEN, BLUE, ALPHA).endVertex();
    }
}
