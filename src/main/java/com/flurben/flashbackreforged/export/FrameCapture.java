package com.flurben.flashbackreforged.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Reads frames from the game's framebuffer through a ring of pixel buffer objects. The GPU copies in the background, so
 * the frame in the current slot only comes back a few frames later and the render thread doesn't wait on every read.
 */
final class FrameCapture {
    private static final int SLOTS = 3;

    private final int[] buffers = new int[SLOTS];
    private final boolean[] filled = new boolean[SLOTS];
    private final int width;
    private final int height;
    private final int size;
    private int index;

    FrameCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.size = width * height * 4;
        for (int i = 0; i < SLOTS; i++) {
            this.buffers[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, this.size, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /** Starts reading the target. @return the oldest completed frame, or null while the ring is still filling. */
    byte[] capture(RenderTarget target) {
        byte[] result = this.filled[this.index] ? read(this.index) : null;
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[this.index]);
        GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        this.filled[this.index] = true;
        this.index = (this.index + 1) % SLOTS;
        return result;
    }

    /** Returns any frames still in flight, oldest first. */
    byte[] drainNext() {
        for (int i = 0; i < SLOTS; i++) {
            int slot = (this.index + i) % SLOTS;
            if (this.filled[slot]) return read(slot);
        }
        return null;
    }

    private byte[] read(int slot) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[slot]);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        byte[] out = new byte[this.size];
        if (mapped != null) {
            mapped.get(out);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        this.filled[slot] = false;
        return out;
    }

    void close() {
        for (int buffer : this.buffers) GL15.glDeleteBuffers(buffer);
    }
}
