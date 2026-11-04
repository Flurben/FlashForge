package com.flurben.flashbackreforged.record;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.perf.Perf;
import com.flurben.flashbackreforged.replay.EntryType;

import net.minecraft.world.level.ChunkPos;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Does all of a recording's disk work on one thread, in the order it was asked for: opening and closing segments,
 * compression, finding repeated content, and side files. Callers only add to a queue, so none of this holds up the game. A
 * flush every few seconds keeps a crashed recording usable.
 */
final class ReplayWriter {
    /** A packet this large goes in the blob store. */
    private static final int BLOB_MIN_BYTES = 1024;
    /** Mod payloads repeat from snapshot to snapshot, so even small ones are worth storing once. */
    private static final int SNAPSHOT_PAYLOAD_MIN_BYTES = 16;
    private static final long FLUSH_INTERVAL_MS = 5000;
    /** Past this much waiting to be written, callers slow down rather than run the game out of memory. */
    private static final long MAX_QUEUED_BYTES = 512L << 20;

    static final String BLOB_FILE = "blobs.bin";

    private static final Object END = new Object();

    @FunctionalInterface
    interface Job {
        void run() throws Exception;
    }

    /**
     * @param version for a chunk, where to note the place it gets stored
     * @param reuse instead of any bytes, a chunk already stored, followed by the entries that have changed it since
     * @param chunkPos which chunk that is
     */
    private record Entry(EntryType type, byte[] bytes, boolean snapshotPayload, ChunkVersions.Version version, ChunkVersions.Usable reuse, long chunkPos) {}

    private static final byte[] NOTHING = new byte[0];

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Thread thread;
    private final BlobWriter blobs;
    private final ByteArrayOutputStream refBytes = new ByteArrayOutputStream(16);
    private volatile Runnable afterEnd;
    private DataOutputStream segment;
    private long lastFlush = System.currentTimeMillis();

    ReplayWriter(Path dir) throws IOException {
        this.blobs = new BlobWriter(dir.resolve(BLOB_FILE));
        this.thread = new Thread(this::run, "Flashback Reforged Replay Writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    boolean failed() {
        return this.failed.get();
    }

    void entry(EntryType type, byte[] payload) {
        add(new Entry(type, payload, false, null, null, 0), payload.length);
    }

    /** A chunk, whose stored place is noted in the version. */
    void chunk(byte[] payload, ChunkVersions.Version version) {
        add(new Entry(EntryType.PACKET, payload, false, version, null, 0), payload.length);
    }

    /** A chunk that's already stored. Anything it refers to must be ahead of this in the queue. */
    void reuseChunk(ChunkVersions.Usable stored, long chunkPos) {
        add(new Entry(EntryType.BLOB_REF, NOTHING, false, null, stored, chunkPos), 0);
    }

    /** A mod payload inside a snapshot. */
    void snapshotPayload(byte[] payload) {
        add(new Entry(EntryType.PACKET, payload, true, null, null, 0), payload.length);
    }

    /** Runs after everything queued so far. */
    void run(Job job) {
        add(job, 0);
    }

    /** Encodes a packet here rather than on the caller's thread, keeping its place in the order. Only for immutable packets. */
    void packet(net.minecraft.network.protocol.Packet<?> packet) {
        run(() -> {
            byte[] bytes = PacketCodec.encode(packet);
            if (bytes != null && !this.failed.get()) write(new Entry(EntryType.PACKET, bytes, false, null, null, 0));
        });
    }

    void openSegment(Path file) {
        run(() -> {
            closeSegment();
            GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16, true) {
                {
                    this.def.setLevel(Deflater.DEFAULT_COMPRESSION);
                }
            };
            this.segment = new DataOutputStream(new BufferedOutputStream(gzip, 1 << 18));
        });
    }

    /** Finishes everything queued and ends the thread. Returns at once; {@link #await} waits for it. */
    void finish(Runnable after) {
        this.afterEnd = after;
        this.queue.add(END);
    }

    void await(long millis) {
        try {
            this.thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void add(Object item, int bytes) {
        if (Thread.currentThread() != this.thread && this.queuedBytes.get() > MAX_QUEUED_BYTES) waitForRoom();
        long queued = this.queuedBytes.addAndGet(bytes);
        this.queue.add(item);
        Perf.peak("writer.queuedBytes", queued);
    }

    private void waitForRoom() {
        // Bounded, so a stuck disk can cost memory but can't freeze the game.
        for (int i = 0; i < 2000 && this.queuedBytes.get() > MAX_QUEUED_BYTES && !this.failed.get(); i++) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void run() {
        try {
            while (true) {
                Object item = this.queue.take();
                if (item == END) break;
                if (item instanceof Entry entry) {
                    this.queuedBytes.addAndGet(-entry.bytes.length);
                    if (!this.failed.get()) write(entry);
                } else if (item instanceof Job job) {
                    try {
                        job.run();
                    } catch (Exception e) {
                        // A side file failing doesn't mean the recording did, unless that's what the job says.
                        FlashbackReforged.LOGGER.error("Replay writer task failed", e);
                    }
                }
                long now = System.currentTimeMillis();
                if (this.segment != null && !this.failed.get() && this.queue.isEmpty() && now - this.lastFlush > FLUSH_INTERVAL_MS) {
                    // In this order, so what a flushed segment refers to has already reached the disk.
                    this.blobs.flush();
                    this.segment.flush();
                    this.lastFlush = now;
                }
            }
            closeSegment();
            this.blobs.close();
        } catch (IOException e) {
            fail(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Runnable after = this.afterEnd;
            if (after != null) after.run();
        }
    }

    private void fail(Exception e) {
        if (this.failed.compareAndSet(false, true)) FlashbackReforged.LOGGER.error("Failed to write replay data", e);
    }

    private void closeSegment() throws IOException {
        if (this.segment == null) return;
        this.blobs.flush();
        this.segment.close();
        this.segment = null;
    }

    private void write(Entry entry) {
        if (this.segment == null) return;
        try {
            byte[] bytes = entry.bytes;
            int threshold = entry.snapshotPayload ? SNAPSHOT_PAYLOAD_MIN_BYTES : BLOB_MIN_BYTES;
            if (entry.reuse != null) {
                writeReference(entry.reuse.version().offset, ChunkPos.getX(entry.chunkPos), ChunkPos.getZ(entry.chunkPos), true, entry.reuse.changes().length, entry.reuse.changeHash());
            } else if (entry.type == EntryType.PACKET && (entry.version != null || bytes.length >= threshold)) {
                long offset = this.blobs.store(bytes);
                if (entry.version != null) entry.version.offset = offset;
                int at = PacketPolicy.chunkCoordinatesOffset(bytes);
                if (at >= 0) {
                    writeReference(offset, java.nio.ByteBuffer.wrap(bytes, at, 4).getInt(), java.nio.ByteBuffer.wrap(bytes, at + 4, 4).getInt(), true, 0, 0);
                } else {
                    writeReference(offset, 0, 0, false, 0, 0);
                }
            } else {
                writeEntry(entry.type.ordinal(), bytes, bytes.length);
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    /** A reference to a chunk also says which chunk, so playback can tell it's already showing without unpacking it. */
    private void writeReference(long offset, int chunkX, int chunkZ, boolean isChunk, int changes, long changeHash) throws IOException {
        this.refBytes.reset();
        long value = offset;
        while ((value & ~0x7FL) != 0) {
            this.refBytes.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        this.refBytes.write((int) value);
        if (isChunk) {
            writeInt(chunkX);
            writeInt(chunkZ);
            if (changes > 0) {
                writeInt(changes);
                writeInt((int) (changeHash >>> 32));
                writeInt((int) changeHash);
            }
        }
        writeEntry(EntryType.BLOB_REF.ordinal(), this.refBytes.toByteArray(), this.refBytes.size());
    }

    private void writeInt(int value) {
        this.refBytes.write(value >>> 24);
        this.refBytes.write(value >>> 16);
        this.refBytes.write(value >>> 8);
        this.refBytes.write(value);
    }

    private void writeEntry(int type, byte[] bytes, int length) throws IOException {
        this.segment.write(type);
        int value = length;
        while ((value & ~0x7F) != 0) {
            this.segment.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        this.segment.write(value);
        this.segment.write(bytes, 0, length);
    }
}
