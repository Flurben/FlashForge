package com.flurben.flashbackreforged.playback;

import com.flurben.flashbackreforged.replay.EntryType;
import com.flurben.flashbackreforged.replay.ReplayMeta;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Breaks a replay's bytes down by what they hold, for the profiling run. Also works out what storing repeats once would save. */
public final class ReplayAnalyzer {
    private ReplayAnalyzer() {}

    private static final class Bucket {
        long count;
        long bytes;
        long snapshotBytes;
        long repeatBytes;
    }

    /** @param dump when set, also writes the replay in this folder as its raw entries, for comparing compressors offline. */
    public static Map<String, Object> analyze(Path dir, Path dump) throws Exception {
        com.flurben.flashbackreforged.record.Recorder.awaitWrites();
        ReplayMeta meta = ReplayMeta.read(dir);
        ReplayFiles replayFiles = new ReplayFiles(dir, meta);
        var ids = ConnectionProtocol.PLAY.getPacketsByIds(PacketFlow.CLIENTBOUND);
        int customId = -1;
        for (var e : ids.int2ObjectEntrySet()) {
            if (e.getValue() == ClientboundCustomPayloadPacket.class) customId = e.getIntKey();
        }

        Map<String, Bucket> buckets = new TreeMap<>();
        Set<BigInteger> seen = new HashSet<>();
        List<Map<String, Object>> segments = new java.util.ArrayList<>();
        long rawTotal = 0, snapshotTotal = 0, repeatTotal = 0, refCount = 0;
        MessageDigest sha = MessageDigest.getInstance("SHA-1");

        OutputStream rawOut = null, uniqueOut = null;
        if (dump != null) {
            Files.createDirectories(dump);
            rawOut = new BufferedOutputStream(Files.newOutputStream(dump.resolve("raw.bin")), 1 << 20);
            uniqueOut = new BufferedOutputStream(Files.newOutputStream(dump.resolve("stream_without_repeats.bin")), 1 << 20);
        }
        OutputStream blobOut = dump == null ? null : new BufferedOutputStream(Files.newOutputStream(dump.resolve("blobs.bin")), 1 << 20);

        for (int segmentIndex = 0; segmentIndex < meta.segments.size(); segmentIndex++) {
            ReplayMeta.Segment s = meta.segments.get(segmentIndex);
            SegmentReader reader = new SegmentReader(dir.resolve(s.file));
            BlobReader blobs = replayFiles.blobsFor(segmentIndex);
            boolean inSnapshot = true;
            long snapshotBytes = 0, streamBytes = 0, firstSeenBytes = 0;
            while (reader.next()) {
                byte[] data = reader.data();
                int offset = reader.payloadOffset, length = reader.payloadLength;
                boolean isPacket = reader.type == EntryType.PACKET;
                String name = reader.type.name();
                if (reader.type == EntryType.BLOB_REF) {
                    // Counted as what it stands for, so the totals describe the recording rather than how it's stored.
                    data = blobs.get(reader.readBlobOffset());
                    offset = 0;
                    length = data.length;
                    isPacket = true;
                    refCount++;
                }
                if (reader.type == EntryType.SNAPSHOT_END) inSnapshot = false;
                if (isPacket && length > 0) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data, offset, length));
                    int id = buf.readVarInt();
                    if (id == customId) {
                        try {
                            name = "custom:" + buf.readResourceLocation();
                        } catch (RuntimeException e) {
                            name = "custom:?";
                        }
                    } else {
                        Class<?> type = ids.get(id);
                        name = type == null ? "id" + id : type.getSimpleName();
                    }
                }
                boolean big = length >= 512;
                boolean repeat = false;
                if (big) {
                    sha.update(data, offset, length);
                    repeat = !seen.add(new BigInteger(1, sha.digest()));
                }
                Bucket b = buckets.computeIfAbsent(name, k -> new Bucket());
                b.count++;
                b.bytes += length;
                if (inSnapshot) b.snapshotBytes += length;
                if (repeat) b.repeatBytes += length;
                rawTotal += length + 2;
                if (inSnapshot) snapshotBytes += length + 2;
                else streamBytes += length + 2;
                if (inSnapshot) snapshotTotal += length + 2;
                if (repeat) repeatTotal += length;
                if (big && !repeat) firstSeenBytes += length;

                if (rawOut != null) {
                    rawOut.write(isPacket ? EntryType.PACKET.ordinal() : reader.type.ordinal());
                    writeVarInt(rawOut, length);
                    rawOut.write(data, offset, length);
                    if (big) {
                        // A reference in the stream, and the content only the first time it appears.
                        uniqueOut.write(reader.type.ordinal() + 100);
                        uniqueOut.write(20);
                        if (!repeat) blobOut.write(data, offset, length);
                    } else {
                        uniqueOut.write(reader.type.ordinal());
                        writeVarInt(uniqueOut, length);
                        uniqueOut.write(data, offset, length);
                    }
                }
            }
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("file", s.file);
            seg.put("ticks", s.ticks);
            seg.put("fileBytes", Files.size(dir.resolve(s.file)));
            seg.put("snapshotRawBytes", snapshotBytes);
            seg.put("streamRawBytes", streamBytes);
            seg.put("newContentRawBytes", firstSeenBytes);
            segments.add(seg);
        }
        replayFiles.close();
        if (rawOut != null) {
            rawOut.close();
            uniqueOut.close();
            blobOut.close();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", meta.totalTicks);
        long diskTotal = 0;
        Map<String, Object> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                diskTotal += Files.size(p);
                String n = p.getFileName().toString();
                if (!n.startsWith("seg")) files.put(n, Files.size(p));
            }
        }
        out.put("diskBytes", diskTotal);
        out.put("mbPerMinute", Math.round(diskTotal / 1048576.0 / Math.max(1.0, meta.totalTicks / 1200.0) * 100) / 100.0);
        out.put("otherFiles", files);
        out.put("rawBytes", rawTotal);
        out.put("snapshotRawBytes", snapshotTotal);
        out.put("repeatedRawBytes", repeatTotal);
        out.put("blobReferences", refCount);
        out.put("segments", segments);

        Map<String, Object> kinds = new LinkedHashMap<>();
        buckets.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().bytes, a.getValue().bytes)).limit(40).forEach(e -> {
            Bucket b = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", b.count);
            m.put("kb", b.bytes >> 10);
            m.put("snapshotKb", b.snapshotBytes >> 10);
            m.put("repeatKb", b.repeatBytes >> 10);
            kinds.put(e.getKey(), m);
        });
        out.put("byKind", kinds);
        return out;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
