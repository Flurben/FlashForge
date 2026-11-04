package com.flurben.flashbackreforged.playback;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a replay's blob file. Safe to use from several threads, and unpacking happens outside the lock, so that a snapshot's
 * chunks can be prepared in parallel. Disk reads go through a cache of file blocks because thousands of small records sit
 * together, and recently unpacked records are kept because a seek usually wants the same ones again.
 */
final class BlobReader implements Closeable {
    private static final int BLOCK_SIZE = 1 << 20;
    private static final int MAX_BLOCKS = 32;
    private static final long MAX_CACHED_BYTES = 256L << 20;
    /** Small records come straight out of a cached block, so keeping a second copy isn't worth it. */
    private static final int MIN_CACHED_SIZE = 512;

    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(() -> new Inflater(true));

    private final FileChannel channel;
    private final Map<Long, byte[]> blocks = new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(1024, 0.75f, true);
    private long cachedBytes;

    BlobReader(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
    }

    /** The result may be shared, so callers must not change it. */
    byte[] get(long offset) throws IOException {
        int rawLength;
        byte[] stored;
        synchronized (this) {
            byte[] cached = this.cache.get(offset);
            if (cached != null) return cached;
            long[] cursor = {offset};
            rawLength = readVarInt(cursor);
            int storedLength = readVarInt(cursor);
            if (rawLength < 0 || storedLength < 0 || storedLength > rawLength) throw new IOException("Bad blob record at " + offset);
            stored = new byte[storedLength];
            readFully(cursor[0], stored);
        }
        byte[] result = stored.length == rawLength ? stored : inflate(stored, rawLength);
        if (rawLength >= MIN_CACHED_SIZE) {
            synchronized (this) {
                if (this.cache.put(offset, result) == null) this.cachedBytes += rawLength;
                var oldest = this.cache.entrySet().iterator();
                while (this.cachedBytes > MAX_CACHED_BYTES && oldest.hasNext()) {
                    this.cachedBytes -= oldest.next().getValue().length;
                    oldest.remove();
                }
            }
        }
        return result;
    }

    /** Unpacks these ahead of time on other threads, returning when all of them are ready. */
    void preload(long[] offsets) {
        int batches = Math.min(8, Runtime.getRuntime().availableProcessors());
        if (offsets.length < 32 || batches < 2) return;
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[batches];
        for (int b = 0; b < batches; b++) {
            int batch = b;
            tasks[b] = CompletableFuture.runAsync(() -> {
                for (int i = batch; i < offsets.length; i += batches) {
                    try {
                        get(offsets[i]);
                    } catch (IOException e) {
                        // Whoever really needs it will find out, and can decide what to do about it.
                    }
                }
            }, net.minecraft.Util.backgroundExecutor());
        }
        CompletableFuture.allOf(tasks).join();
    }

    private static byte[] inflate(byte[] stored, int rawLength) throws IOException {
        byte[] out = new byte[rawLength];
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(stored);
        try {
            int at = 0;
            while (at < rawLength) {
                int n = inflater.inflate(out, at, rawLength - at);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) throw new IOException("Blob record is cut short");
                at += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("Blob record is damaged", e);
        }
        return out;
    }

    private int readVarInt(long[] cursor) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            long position = cursor[0]++;
            byte[] block = block(position / BLOCK_SIZE);
            int index = (int) (position % BLOCK_SIZE);
            if (index >= block.length) throw new EOFException();
            byte b = block[index];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("Bad blob record");
    }

    private void readFully(long position, byte[] out) throws IOException {
        int at = 0;
        while (at < out.length) {
            byte[] block = block(position / BLOCK_SIZE);
            int index = (int) (position % BLOCK_SIZE);
            int n = Math.min(block.length - index, out.length - at);
            if (n <= 0) throw new EOFException();
            System.arraycopy(block, index, out, at, n);
            at += n;
            position += n;
        }
    }

    private byte[] block(long index) throws IOException {
        byte[] block = this.blocks.get(index);
        if (block != null) return block;
        ByteBuffer buffer = ByteBuffer.allocate(BLOCK_SIZE);
        long position = index * BLOCK_SIZE;
        while (buffer.hasRemaining()) {
            int n = this.channel.read(buffer, position + buffer.position());
            if (n < 0) break;
        }
        block = buffer.position() == BLOCK_SIZE ? buffer.array() : java.util.Arrays.copyOf(buffer.array(), buffer.position());
        this.blocks.put(index, block);
        if (this.blocks.size() > MAX_BLOCKS) {
            var oldest = this.blocks.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return block;
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
