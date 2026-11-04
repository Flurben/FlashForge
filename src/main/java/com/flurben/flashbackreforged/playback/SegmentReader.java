package com.flurben.flashbackreforged.playback;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.replay.EntryType;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * A decompressed segment held in memory with a cursor over its entries. A truncated file (the game crashed
 * while recording) reads up to the last complete entry instead of failing.
 */
final class SegmentReader {
    private final byte[] data;
    private final int size;
    private int pos;

    EntryType type;
    int payloadOffset;
    int payloadLength;

    SegmentReader(byte[] data) {
        this.data = data;
        this.size = data.length;
    }

    SegmentReader(Path file) throws IOException {
        this(read(file));
    }

    /** Safe on any thread. */
    static byte[] read(Path file) throws IOException {
        byte[] packed = Files.readAllBytes(file);
        byte[] out = new byte[sizeHint(packed)];
        int at = 0;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed), 1 << 16)) {
            int read;
            while ((read = in.read(out, at, out.length - at)) >= 0) {
                at += read;
                if (at == out.length) {
                    // The size was right if this is the end, and otherwise there's more than it said.
                    int next = in.read();
                    if (next < 0) break;
                    out = Arrays.copyOf(out, out.length * 2);
                    out[at++] = (byte) next;
                }
            }
        } catch (EOFException | ZipException e) {
            FlashbackReforged.LOGGER.warn("Segment {} is truncated; using the {} bytes that could be read", file.getFileName(), at);
        }
        return at == out.length ? out : Arrays.copyOf(out, at);
    }

    /** A finished gzip file ends with its original size. A crashed recording's doesn't, so this is only a starting point. */
    private static int sizeHint(byte[] packed) {
        int fallback = Math.max(1 << 16, packed.length * 4);
        if (packed.length < 18) return fallback;
        int n = packed.length;
        int size = (packed[n - 4] & 0xFF) | (packed[n - 3] & 0xFF) << 8 | (packed[n - 2] & 0xFF) << 16 | (packed[n - 1] & 0xFF) << 24;
        return size >= packed.length / 2 && size <= Math.min(1L << 30, packed.length * 1032L) ? Math.max(size, 1) : fallback;
    }

    byte[] data() {
        return this.data;
    }

    void rewind() {
        this.pos = 0;
    }

    /** @return false at the end of the segment. Unknown entry types are skipped, since they're length-delimited. */
    boolean next() {
        while (this.pos < this.size) {
            int start = this.pos;
            int id = this.data[this.pos++] & 0xFF;
            int length = 0;
            int shift = 0;
            while (true) {
                if (this.pos >= this.size || shift > 28) {
                    this.pos = this.size;
                    return false;
                }
                byte b = this.data[this.pos++];
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            if (length < 0 || this.pos + length > this.size) {
                FlashbackReforged.LOGGER.warn("Ignoring incomplete entry at the end of a segment (offset {})", start);
                this.pos = this.size;
                return false;
            }
            this.payloadOffset = this.pos;
            this.payloadLength = length;
            this.pos += length;
            this.type = EntryType.byId(id);
            if (this.type != null) return true;
        }
        return false;
    }

    /** For a {@link EntryType#BLOB_REF} entry. */
    long readBlobOffset() {
        long value = 0;
        int at = this.payloadOffset;
        for (int shift = 0; at < this.payloadOffset + this.payloadLength && shift < 63; shift += 7) {
            byte b = this.data[at++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                this.blobHeaderEnd = at;
                return value;
            }
        }
        throw new IllegalStateException("Bad blob reference");
    }

    private int blobHeaderEnd;

    /** After {@link #readBlobOffset}: whether this reference says which chunk it is. */
    boolean blobIsChunk() {
        return this.payloadOffset + this.payloadLength - this.blobHeaderEnd >= 8;
    }

    int blobChunkX() {
        return readInt(this.blobHeaderEnd);
    }

    int blobChunkZ() {
        return readInt(this.blobHeaderEnd + 4);
    }

    /** How many entries after this one bring the stored chunk up to date. */
    int blobChanges() {
        return this.payloadOffset + this.payloadLength - this.blobHeaderEnd >= 20 ? readInt(this.blobHeaderEnd + 8) : 0;
    }

    long blobChangeHash() {
        return blobChanges() == 0 ? 0 : (long) readInt(this.blobHeaderEnd + 12) << 32 | readInt(this.blobHeaderEnd + 16) & 0xFFFFFFFFL;
    }

    private int readInt(int at) {
        return (this.data[at] & 0xFF) << 24 | (this.data[at + 1] & 0xFF) << 16 | (this.data[at + 2] & 0xFF) << 8 | (this.data[at + 3] & 0xFF);
    }

    /** Number of completed ticks in the segment. */
    int countTicks() {
        int saved = this.pos;
        this.pos = 0;
        int ticks = 0;
        while (next()) {
            if (this.type == EntryType.TICK) ticks++;
        }
        this.pos = saved;
        return ticks;
    }
}
