package com.wirelesseu.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Headless checks for the broadcaster artwork and the static block models. No Minecraft or OpenGL session is started.
 *
 * The 1.12.2 build needed a UV-orientation check because it rotated texture coordinates at render time; 1.20.1 uses
 * blockstate rotation instead, so this check replaces it with a model-mapping check.
 */
public final class BroadcasterTextureSelfCheck {
    private static final String[] TIERS = {
            "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv", "uev", "uiv", "uxv", "opv", "max"
    };
    private static final String[] FACES = {"front", "top", "side", "rear", "bottom"};
    private static final String[][] MODEL_FACES = {
            {"down", "bottom"}, {"up", "top"}, {"north", "front"}, {"south", "rear"},
            {"east", "side"}, {"west", "side"}, {"particle", "side"}
    };

    public static void main(String[] args) throws Exception {
        File assetRoot = resolve("src/main/resources/assets/wireless_eu");
        checkTextures(new File(assetRoot, "textures/block/broadcaster"));
        checkModels(assetRoot);
        System.out.println("Broadcaster self-check: 70 opaque 16x16 faces with approved hashes and 14 model mappings passed.");
    }

    private static void checkTextures(File root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int approvedCount = 0;
        for (String entry : Files.readAllLines(resolve("src/test/resources/broadcaster-v05-sha256.txt").toPath(),
                StandardCharsets.UTF_8)) {
            if (entry.isBlank()) {
                continue;
            }
            String expected = entry.substring(0, 64);
            File image = new File(root, entry.substring(66));
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest(Files.readAllBytes(image.toPath()))) {
                actual.append(String.format("%02x", value & 0xFF));
            }
            require(expected.equals(actual.toString()), "Does not match approved v05: " + image);
            BufferedImage buffered = ImageIO.read(image);
            require(buffered != null && buffered.getWidth() == 16 && buffered.getHeight() == 16,
                    "Wrong dimensions: " + image);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    require((buffered.getRGB(x, y) >>> 24) == 255, "Transparent pixel: " + image + " (" + x + "," + y + ")");
                }
            }
            approvedCount++;
        }
        require(approvedCount == 70, "Incomplete approved face set: " + approvedCount);
        for (String tier : TIERS) {
            for (String side : FACES) {
                File image = new File(root, tier + "/" + side + ".png");
                require(image.isFile(), "Missing texture: " + image);
            }
        }
    }

    private static void checkModels(File assetRoot) throws Exception {
        for (String tier : TIERS) {
            String id = tier + "_wireless_eu_broadcaster";
            JsonObject model = JsonParser.parseString(Files.readString(
                    new File(assetRoot, "models/block/machine/" + id + ".json").toPath())).getAsJsonObject();
            require("gtceu:machine".equals(model.get("loader").getAsString()), "Wrong loader: " + id);
            require(("wireless_eu:" + id).equals(model.get("machine").getAsString()), "Wrong machine id: " + id);
            JsonObject textures = model.getAsJsonObject("variants").getAsJsonObject("")
                    .getAsJsonObject("model").getAsJsonObject("textures");
            for (String[] face : MODEL_FACES) {
                String expected = "wireless_eu:block/broadcaster/" + tier + "/" + face[1];
                require(expected.equals(textures.get(face[0]).getAsString()),
                        "Wrong " + face[0] + " texture for " + id);
            }
            File textureDir = new File(assetRoot, "textures/block/broadcaster/" + tier);
            for (String face : FACES) {
                require(new File(textureDir, face + ".png").isFile(), "Missing " + id + " texture " + face);
            }
            JsonObject blockstate = JsonParser.parseString(Files.readString(
                    new File(assetRoot, "blockstates/" + id + ".json").toPath())).getAsJsonObject();
            require(blockstate.getAsJsonObject("variants").size() == 6, "Incomplete facing variants: " + id);
        }
    }

    private static File resolve(String relative) {
        File fromProjectRoot = new File(relative);
        if (fromProjectRoot.exists()) {
            return fromProjectRoot;
        }
        return new File("../" + relative);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
