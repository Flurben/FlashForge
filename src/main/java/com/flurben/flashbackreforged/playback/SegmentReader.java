package com.flurben.flashbackreforged.playback;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.replay.EntryType;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    SegmentReader(Path file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (EOFException | ZipException e) {
            FlashbackReforged.LOGGER.warn("Segment {} is truncated; using the {} bytes that could be read", file.getFileName(), out.size());
        }
        this.data = out.toByteArray();
        this.size = this.data.length;
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
