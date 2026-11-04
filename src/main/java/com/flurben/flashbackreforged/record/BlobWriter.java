package com.flurben.flashbackreforged.record;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Stores each distinct large packet once, so the same chunk appearing in several snapshots costs one copy. Each record is
 * {@code [varint raw length][varint stored length][bytes]}, deflated on its own so any record can be read without its
 * neighbours. Stored and raw lengths being equal means it isn't compressed. Writer thread only.
 */
final class BlobWriter implements AutoCloseable {
    /** Below this, compressing rarely pays for itself. */
    private static final int MIN_COMPRESS = 128;

    private final OutputStream out;
    private final Deflater deflater = new Deflater(6, true);
    private final Map<ByteBuffer, Long> offsets = new HashMap<>();
    private final MessageDigest digest;
    private byte[] packed = new byte[1 << 16];
    private long position;

    BlobWriter(Path file) throws IOException {
        this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        try {
            this.digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** @return where this content is stored, which is the same place as last time if it has been seen before. */
    long store(byte[] data) throws IOException {
        ByteBuffer key = ByteBuffer.wrap(this.digest.digest(data));
        Long known = this.offsets.get(key);
        if (known != null) return known;

        long offset = this.position;
        int stored = compress(data);
        writeVarInt(data.length);
        writeVarInt(stored < data.length ? stored : data.length);
        if (stored < data.length) {
            this.out.write(this.packed, 0, stored);
            this.position += stored;
        } else {
            this.out.write(data);
            this.position += data.length;
        }
        this.offsets.put(key, offset);
        return offset;
    }

    /** @return the compressed size in {@link #packed}, or the raw size when it isn't worth compressing. */
    private int compress(byte[] data) {
        if (data.length < MIN_COMPRESS) return data.length;
        this.deflater.reset();
        this.deflater.setInput(data);
        this.deflater.finish();
        int size = 0;
        while (!this.deflater.finished()) {
            if (size == this.packed.length) {
                // Larger than the original already, so there's no point carrying on.
                if (size >= data.length) return data.length;
                this.packed = Arrays.copyOf(this.packed, this.packed.length * 2);
            }
            size += this.deflater.deflate(this.packed, size, this.packed.length - size);
        }
        return size;
    }

    private void writeVarInt(int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            this.out.write((value & 0x7F) | 0x80);
            value >>>= 7;
            this.position++;
        }
        this.out.write(value);
        this.position++;
    }

    void flush() throws IOException {
        this.out.flush();
    }

    @Override
    public void close() throws IOException {
        this.deflater.end();
        this.out.close();
    }
}
