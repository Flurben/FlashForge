package com.flurben.flashforge.record;

import com.flurben.flashforge.FlashForge;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.Path;

/** The picture shown beside a replay in the browser. */
public final class Thumbnails {
    public static final String FILE = "icon.png";
    private static final int WIDTH = 128, HEIGHT = 72;

    private Thumbnails() {}

    /** Takes ownership of the image. Scaling and writing happen off the game thread. */
    static void save(NativeImage image, Path dir) {
        Util.ioPool().execute(() -> {
            try (image; NativeImage small = new NativeImage(WIDTH, HEIGHT, false)) {
                int w = image.getWidth(), h = image.getHeight();
                int cropW = Math.min(w, h * WIDTH / HEIGHT);
                int cropH = cropW * HEIGHT / WIDTH;
                image.resizeSubRectTo((w - cropW) / 2, (h - cropH) / 2, cropW, cropH, small);
                small.writeToFile(dir.resolve(FILE));
            } catch (IOException e) {
                FlashForge.LOGGER.warn("Could not write replay thumbnail", e);
            }
        });
    }
}
