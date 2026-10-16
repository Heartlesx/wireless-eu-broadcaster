package com.wirelesseu.client;

import codechicken.lib.render.BlockRenderer.BlockFace;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Vertex5;
import net.minecraft.util.EnumFacing;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Headless checks against CCL's face UV convention; no Minecraft or OpenGL session is started. */
public final class BroadcasterRenderSelfCheck {
    private static final String[] TIERS = {
            "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv", "uev", "uiv", "uxv", "opv", "max"
    };

    public static void main(String[] args) throws Exception {
        int corners = 0;
        for (EnumFacing front : EnumFacing.VALUES) {
            Set<EnumFacing> mapped = new HashSet<>();
            for (EnumFacing local : EnumFacing.VALUES) {
                BlockFace face = new BlockFace().loadCuboidFace(Cuboid6.full, local.getIndex());
                EnumFacing worldSide = rotate(local, front);
                require(BroadcasterOrientation.localSide(worldSide, front) == local, "Wrong face for " + front);
                mapped.add(worldSide);
                for (Vertex5 vertex : face.verts) {
                    double expectedU = vertex.uv.u;
                    double expectedV = local == EnumFacing.DOWN ? 1 - vertex.uv.v : vertex.uv.v;
                    Vertex5 rotated = rotate(vertex, front);
                    BroadcasterOrientation.setUv(rotated, worldSide, front);
                    require(rotated.uv.u == expectedU && rotated.uv.v == expectedV,
                            "Mirrored/rotated UV at " + front + "/" + local + ": " + rotated);
                    corners++;
                }
            }
            require(mapped.size() == 6, "Faces overlap for " + front);
        }
        checkTextures();
        System.out.println("Broadcaster self-check: 6 orientations, " + corners
                + " UV corners, 70 opaque 32x32 faces, shared emissive alignment passed.");
    }

    private static Vertex5 rotate(Vertex5 vertex, EnumFacing front) {
        double x = vertex.vec.x;
        double y = vertex.vec.y;
        double z = vertex.vec.z;
        switch (front) {
            case SOUTH: return new Vertex5(1 - x, y, 1 - z, 0, 0);
            case WEST: return new Vertex5(z, y, 1 - x, 0, 0);
            case EAST: return new Vertex5(1 - z, y, x, 0, 0);
            case UP: return new Vertex5(x, 1 - z, y, 0, 0);
            case DOWN: return new Vertex5(x, z, 1 - y, 0, 0);
            default: return vertex.copy();
        }
    }

    private static EnumFacing rotate(EnumFacing side, EnumFacing front) {
        Vertex5 point = rotate(new Vertex5(0.5 + side.getXOffset() * 0.5,
                0.5 + side.getYOffset() * 0.5, 0.5 + side.getZOffset() * 0.5, 0, 0), front);
        return EnumFacing.getFacingFromVector((float) (point.vec.x - 0.5),
                (float) (point.vec.y - 0.5), (float) (point.vec.z - 0.5));
    }

    private static void checkTextures() throws Exception {
        File root = new File("src/main/resources/assets/wireless_eu/textures/blocks/broadcaster");
        BufferedImage mask = ImageIO.read(new File(root, "front_emissive.png"));
        require(mask.getWidth() == 32 && mask.getHeight() == 32, "Wrong mask dimensions");
        int lit = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                if ((mask.getRGB(x, y) >>> 24) != 0) lit++;
            }
        }
        require(lit > 0 && lit < 100, "Emissive region must stay small");
        for (String tier : TIERS) {
            for (String side : new String[]{"front", "top", "side", "rear", "bottom"}) {
                BufferedImage image = ImageIO.read(new File(root, tier + "/" + side + ".png"));
                require(image.getWidth() == 32 && image.getHeight() == 32, "Wrong dimensions: " + tier + "/" + side);
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 32; x++) {
                        require((image.getRGB(x, y) >>> 24) == 255, "Transparent casing: " + tier + "/" + side);
                        if (side.equals("front") && (mask.getRGB(x, y) >>> 24) != 0) {
                            require(image.getRGB(x, y) == mask.getRGB(x, y), "Emissive mismatch: " + tier);
                        }
                    }
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
