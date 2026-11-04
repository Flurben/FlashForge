package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * The picture shown beside a replay in the browser. Reading a whole frame back from the graphics card stalls the game until
 * the card has caught up, which with shaders took 150 ms. So the card shrinks the frame itself and copies the small result
 * in the background, and the bytes are collected once a fence says they're ready, a frame or two later.
 */
public final class Thumbnails {
    public static final String FILE = "icon.png";
    private static final int WIDTH = 128, HEIGHT = 72;

    private Thumbnails() {}

    /** One capture in progress. Render thread only. */
    static final class Capture {
        private final int framebuffer;
        private final int colorBuffer;
        private final int pixelBuffer;
        private long fence;

        /** Must run with the frame to capture in the target. */
        Capture(RenderTarget source) {
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);

            this.framebuffer = GL30.glGenFramebuffers();
            this.colorBuffer = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.colorBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, WIDTH, HEIGHT);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.framebuffer);
            GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, this.colorBuffer);

            // Crops to the picture's shape from the middle of the frame.
            int w = source.width, h = source.height;
            int cropW = Math.min(w, h * WIDTH / HEIGHT);
            int cropH = cropW * HEIGHT / WIDTH;
            int x = (w - cropW) / 2, y = (h - cropH) / 2;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
            GL30.glBlitFramebuffer(x, y, x + cropW, y + cropH, 0, 0, WIDTH, HEIGHT, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

            this.pixelBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.pixelBuffer);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, WIDTH * HEIGHT * 4L, GL15.GL_STREAM_READ);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.framebuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            this.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }

        /** @return the pixels, bottom row first, once they're ready. Waits for them if told to. */
        byte[] poll(boolean wait) {
            int status = GL32.glClientWaitSync(this.fence, wait ? GL32.GL_SYNC_FLUSH_COMMANDS_BIT : 0, wait ? 2_000_000_000L : 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) return null;
            byte[] pixels = new byte[WIDTH * HEIGHT * 4];
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.pixelBuffer);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (mapped != null) {
                mapped.get(pixels);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            release();
            return pixels;
        }

        private void release() {
            GL32.glDeleteSync(this.fence);
            GL15.glDeleteBuffers(this.pixelBuffer);
            GL30.glDeleteFramebuffers(this.framebuffer);
            GL30.glDeleteRenderbuffers(this.colorBuffer);
        }
    }

    /** Off the game thread. */
    static void write(byte[] pixels, Path dir) {
        try (NativeImage image = new NativeImage(WIDTH, HEIGHT, false)) {
            for (int y = 0; y < HEIGHT; y++) {
                int row = (HEIGHT - 1 - y) * WIDTH * 4;
                for (int x = 0; x < WIDTH; x++) {
                    int i = row + x * 4;
                    // The game leaves meaningless alpha in its framebuffer.
                    image.setPixelRGBA(x, y, 0xFF000000 | (pixels[i + 2] & 0xFF) << 16 | (pixels[i + 1] & 0xFF) << 8 | (pixels[i] & 0xFF));
                }
            }
            image.writeToFile(dir.resolve(FILE));
        } catch (IOException e) {
            FlashbackReforged.LOGGER.warn("Could not write replay thumbnail", e);
        }
    }
}
