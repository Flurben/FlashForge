package com.flurben.flashbackreforged.playback;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.perf.Perf;
import com.flurben.flashbackreforged.replay.EntryType;
import com.flurben.flashbackreforged.replay.ReplayMeta;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A replay's files as playback needs them. Unpacking a segment used to happen on the game thread at the moment it was
 * needed, which hitched playback at every boundary. Now the segments on either side of the current one are always being
 * prepared in the background.
 */
final class ReplayFiles implements Closeable {
    private final Path dir;
    private final ReplayMeta meta;
    private final Map<String, BlobReader> blobs = new HashMap<>();
    private final Map<Integer, CompletableFuture<byte[]>> segments = new HashMap<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Flashback Reforged Replay Reader");
        thread.setDaemon(true);
        return thread;
    });

    ReplayFiles(Path dir, ReplayMeta meta) {
        this.dir = dir;
        this.meta = meta;
    }

    /** Game thread only. @return null when this segment doesn't use a blob file, as in replays from before they existed. */
    BlobReader blobsFor(int segment) throws IOException {
        String name = this.meta.segments.get(segment).blobs;
        if (name == null) return null;
        BlobReader reader = this.blobs.get(name);
        if (reader == null) {
            reader = new BlobReader(this.dir.resolve(name));
            this.blobs.put(name, reader);
        }
        return reader;
    }

    /** A number for a blob file, which together with an offset identifies some content across a combined replay. */
    int blobFileId(int segment) {
        String name = this.meta.segments.get(segment).blobs;
        return name == null ? 0 : name.hashCode() & 0x7FFF;
    }

    /** Game thread only. Waits if that segment is still being prepared. */
    byte[] segment(int index) throws IOException {
        long start = Perf.start();
        byte[] data;
        CompletableFuture<byte[]> pending = this.segments.get(index);
        try {
            data = pending != null ? pending.get() : SegmentReader.read(this.dir.resolve(this.meta.segments.get(index).file));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            // It's worth one more try here, in case that was something passing.
            FlashbackReforged.LOGGER.warn("Could not prepare a replay segment in the background", e.getCause());
            data = SegmentReader.read(this.dir.resolve(this.meta.segments.get(index).file));
        }
        Perf.end("replay.loadSegment", start);
        this.segments.put(index, CompletableFuture.completedFuture(data));
        prepareAround(index);
        return data;
    }

    private void prepareAround(int index) {
        this.segments.keySet().removeIf(i -> Math.abs(i - index) > 1);
        prepare(index + 1);
        prepare(index - 1);
    }

    private void prepare(int index) {
        if (index < 0 || index >= this.meta.segments.size() || this.segments.containsKey(index)) return;
        ReplayMeta.Segment segment = this.meta.segments.get(index);
        BlobReader reader;
        try {
            reader = blobsFor(index);
        } catch (IOException e) {
            return;
        }
        this.segments.put(index, CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = SegmentReader.read(this.dir.resolve(segment.file));
                if (reader != null) reader.preload(snapshotBlobs(data));
                return data;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, this.background));
    }

    /** Where a segment's snapshot content is, so it can be unpacked ahead of time. */
    private static long[] snapshotBlobs(byte[] data) {
        SegmentReader reader = new SegmentReader(data);
        long[] offsets = new long[1024];
        int count = 0;
        while (reader.next() && reader.type != EntryType.SNAPSHOT_END) {
            if (reader.type != EntryType.BLOB_REF) continue;
            if (count == offsets.length) offsets = java.util.Arrays.copyOf(offsets, count * 2);
            offsets[count++] = reader.readBlobOffset();
        }
        return java.util.Arrays.copyOf(offsets, count);
    }

    @Override
    public void close() {
        this.background.shutdownNow();
        for (BlobReader reader : this.blobs.values()) {
            try {
                reader.close();
            } catch (IOException e) {
                FlashbackReforged.LOGGER.debug("Could not close a replay file", e);
            }
        }
        this.blobs.clear();
    }

    /** The segments' files in order. */
    List<ReplayMeta.Segment> list() {
        return this.meta.segments;
    }
}
