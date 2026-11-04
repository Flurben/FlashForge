package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.replay.EntryType;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Streams one gzip segment to disk on its own thread. Callers (netty and the client thread) only enqueue,
 * so compression and IO never hitch the game. A sync flush every few seconds keeps a crashed recording usable.
 */
final class SegmentWriter {
    private static final byte[] POISON = new byte[0];
    private static final long FLUSH_INTERVAL_MS = 5000;

    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final AtomicBoolean failed = new AtomicBoolean();

    SegmentWriter(Path file) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16, true) {
            {
                def.setLevel(Deflater.BEST_SPEED);
            }
        };
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gzip, 1 << 18));
        this.thread = new Thread(() -> run(out), "Flashback Reforged Segment Writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void run(DataOutputStream out) {
        long lastFlush = System.currentTimeMillis();
        try (out) {
            while (true) {
                byte[] entry = this.queue.take();
                if (entry == POISON) break;
                out.write(entry);
                long now = System.currentTimeMillis();
                if (this.queue.isEmpty() && now - lastFlush > FLUSH_INTERVAL_MS) {
                    out.flush();
                    lastFlush = now;
                }
            }
        } catch (IOException | InterruptedException e) {
            this.failed.set(true);
            FlashbackReforged.LOGGER.error("Failed to write replay segment", e);
        }
    }

    boolean failed() {
        return this.failed.get();
    }

    void entry(EntryType type, byte[] payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 6);
        bytes.write(type.ordinal());
        int value = payload.length;
        while ((value & ~0x7F) != 0) {
            bytes.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        bytes.write(value);
        bytes.write(payload, 0, payload.length);
        this.queue.add(bytes.toByteArray());
    }

    void close() {
        this.queue.add(POISON);
        try {
            this.thread.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
