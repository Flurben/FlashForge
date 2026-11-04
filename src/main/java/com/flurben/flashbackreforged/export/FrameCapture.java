package com.flurben.flashbackreforged.export;

import com.flurben.flashbackreforged.perf.Perf;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Reads frames from the game's framebuffer through pixel buffers, which the graphics card fills in the background. A filled
 * buffer stays mapped while the writer thread copies from it, so the render thread never touches the pixels: it only makes
 * the OpenGL calls, which have to happen on this thread. If every buffer is busy the render thread waits, which is what
 * keeps a slow encoder from being outrun.
 */
final class FrameCapture {
    private static final int SLOTS = 5;

    private static final class Slot {
        final int buffer;
        /** Non-zero while the graphics card is still filling this. */
        long fence;
        /** True from handing this to the writer until the mapping is released. */
        boolean mapped;
        volatile boolean copied;

        Slot(int buffer) {
            this.buffer = buffer;
        }
    }

    private final ArrayDeque<Slot> free = new ArrayDeque<>();
    /** Oldest first, which is also the order frames reach the writer. */
    private final ArrayDeque<Slot> busy = new ArrayDeque<>();
    private final FrameWriter writer;
    private final int width;
    private final int height;

    FrameCapture(int width, int height, FrameWriter writer) {
        this.width = width;
        this.height = height;
        this.writer = writer;
        for (int i = 0; i < SLOTS; i++) {
            int buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL15.GL_STREAM_READ);
            this.free.add(new Slot(buffer));
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /** Starts reading the target. The frame reaches the writer later, in order. */
    void capture(RenderTarget target) {
        advance(false);
        if (this.free.isEmpty()) {
            long start = Perf.start();
            while (this.free.isEmpty()) advance(true);
            Perf.end("export.waitForFreeBuffer", start);
        }
        Slot slot = this.free.poll();
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.buffer);
        GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
        this.busy.addLast(slot);
    }

    /** Hands over every frame still in flight and waits until the writer has copied them. */
    void drain() {
        while (!this.busy.isEmpty()) advance(true);
    }

    /** Moves frames along as far as they can go. Told to wait, it pauses if nothing could move. */
    private void advance(boolean wait) {
        boolean moved = false;

        // Copied by the writer, so the oldest buffers can be used again.
        while (!this.busy.isEmpty() && this.busy.peekFirst().mapped && this.busy.peekFirst().copied) {
            Slot slot = this.busy.pollFirst();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.buffer);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            slot.mapped = false;
            slot.copied = false;
            this.free.addLast(slot);
            moved = true;
        }

        // Filled by the graphics card, so they can go to the writer, oldest first.
        Slot firstFilling = null;
        for (Slot slot : this.busy) {
            if (slot.mapped) continue;
            int status = GL32.glClientWaitSync(slot.fence, 0, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                firstFilling = slot;
                break;
            }
            handOver(slot);
            moved = true;
        }
        if (!wait || moved) return;

        // Nothing could move. If the writer has nothing of ours, then it's the graphics card being waited on.
        Slot oldest = this.busy.peekFirst();
        if (oldest != null && oldest == firstFilling) {
            GL32.glClientWaitSync(oldest.fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
        } else {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handOver(Slot slot) {
        GL32.glDeleteSync(slot.fence);
        slot.fence = 0;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.buffer);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        slot.mapped = true;
        if (mapped == null) {
            // No frame to deliver, but the buffer still needs to come back.
            slot.copied = true;
            return;
        }
        this.writer.submit(mapped, () -> slot.copied = true);
    }

    /** The writer must have stopped reading from these by now. */
    void close() {
        for (Slot slot : this.busy) {
            if (slot.fence != 0) GL32.glDeleteSync(slot.fence);
            GL15.glDeleteBuffers(slot.buffer);
        }
        for (Slot slot : this.free) GL15.glDeleteBuffers(slot.buffer);
        this.busy.clear();
        this.free.clear();
    }
}
