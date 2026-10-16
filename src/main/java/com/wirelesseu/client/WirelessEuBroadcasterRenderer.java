package com.wirelesseu.client;

import codechicken.lib.render.BlockRenderer.BlockFace;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Matrix4;
import codechicken.lib.vec.TransformationList;
import codechicken.lib.vec.Vertex5;
import codechicken.lib.vec.uv.IconTransformation;
import com.wirelesseu.WirelessEuMod;
import com.wirelesseu.machine.WirelessEuMachines;
import gregtech.api.GTValues;
import gregtech.client.renderer.CubeRendererState;
import gregtech.client.renderer.cclop.LightMapOperation;
import gregtech.client.renderer.texture.Textures;
import gregtech.client.utils.BloomEffectUtil;
import gregtech.common.ConfigHolder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.ArrayUtils;

@SideOnly(Side.CLIENT)
public final class WirelessEuBroadcasterRenderer {
    private static final String[] FACES = {"bottom", "top", "front", "rear", "side", "side"};
    private static final TextureAtlasSprite[][] SPRITES = new TextureAtlasSprite[GTValues.MAX + 1][6];
    private static final ThreadLocal<BlockFace> FACE = ThreadLocal.withInitial(BlockFace::new);
    private static TextureAtlasSprite emissive;

    private WirelessEuBroadcasterRenderer() {
    }

    @SubscribeEvent
    public static void registerTextures(TextureStitchEvent.Pre event) {
        for (int i = 0; i < WirelessEuMachines.TIERS.length; i++) {
            int tier = WirelessEuMachines.TIERS[i];
            for (EnumFacing side : EnumFacing.VALUES) {
                SPRITES[tier][side.getIndex()] = event.getMap().registerSprite(new ResourceLocation(
                        WirelessEuMod.MOD_ID, "blocks/broadcaster/" + WirelessEuMachines.TIER_IDS[i]
                        + "/" + FACES[side.getIndex()]));
            }
        }
        emissive = event.getMap().registerSprite(new ResourceLocation(
                WirelessEuMod.MOD_ID, "blocks/broadcaster/front_emissive"));
    }

    public static TextureAtlasSprite particle(int tier) {
        return SPRITES[tier][EnumFacing.WEST.getIndex()];
    }

    public static void render(int tier, EnumFacing front, CCRenderState state, Matrix4 translation,
                              IVertexOperation[] pipeline) {
        CubeRendererState cubeState = Textures.RENDER_STATE.get();
        for (EnumFacing side : EnumFacing.VALUES) {
            // Keep geometry, normals and culling in world space; rotate only texture coordinates.
            if (cubeState != null && !cubeState.shouldSideBeRendered(side, Cuboid6.full)) {
                continue;
            }
            BlockFace face = FACE.get().loadCuboidFace(Cuboid6.full, side.getIndex());
            for (Vertex5 vertex : face.verts) {
                BroadcasterOrientation.setUv(vertex, side, front);
            }
            EnumFacing localSide = BroadcasterOrientation.localSide(side, front);
            draw(face, SPRITES[tier][localSide.getIndex()], BlockRenderLayer.CUTOUT_MIPPED,
                    state, translation, pipeline, cubeState);
            if (side == front && ConfigHolder.client.machinesEmissiveTextures) {
                draw(face, emissive, BloomEffectUtil.getEffectiveBloomLayer(), state, translation,
                        ArrayUtils.add(pipeline, new LightMapOperation(240, 240)), cubeState);
            }
        }
    }

    private static void draw(BlockFace face, TextureAtlasSprite sprite, BlockRenderLayer layer,
                             CCRenderState state, Matrix4 translation, IVertexOperation[] pipeline,
                             CubeRendererState cubeState) {
        if (cubeState != null && cubeState.layer != null && cubeState.layer != layer) {
            return;
        }
        state.setPipeline(face, 0, face.verts.length, ArrayUtils.addAll(pipeline,
                new TransformationList(translation), new IconTransformation(sprite)));
        state.render();
    }
}
