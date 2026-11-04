package com.flurben.flashbackreforged.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Joins two recordings of the same mod set into a new replay, leaving both originals alone. Each recording's first
 * segment is flagged so playback rebuilds the world from its snapshot rather than continuing from the previous one.
 */
public final class ReplayCombiner {
    private ReplayCombiner() {}

    public static Path combine(Path first, Path second) throws IOException {
        // Either could be a recording that only just stopped.
        com.flurben.flashbackreforged.record.Recorder.awaitWrites();
        ReplayMeta a = ReplayMeta.read(first);
        ReplayMeta b = ReplayMeta.read(second);
        if (!a.mods.equals(b.mods)) {
            throw new IOException("These replays were recorded with different mods, so their block and item ids don't match.");
        }

        ReplayMeta out = new ReplayMeta();
        out.name = a.name + " + " + b.name;
        out.world = a.world;
        out.recordedBy = a.recordedBy;
        out.minecraftVersion = a.minecraftVersion;
        out.protocol = a.protocol;
        out.mods = a.mods;
        out.hasVoice = a.hasVoice || b.hasVoice;
        out.distantHorizons.putAll(a.distantHorizons);
        out.distantHorizons.putAll(b.distantHorizons);

        Path dir = ReplayStorage.root().resolve(ReplayStorage.safeName(out.uuid));
        Files.createDirectories(dir);
        Files.copy(first.resolve("registries.nbt"), dir.resolve("registries.nbt"));
        if (Files.exists(first.resolve("serverconfig.nbt"))) Files.copy(first.resolve("serverconfig.nbt"), dir.resolve("serverconfig.nbt"));

        Path icon = first.resolve(com.flurben.flashbackreforged.record.Thumbnails.FILE);
        if (Files.exists(icon)) Files.copy(icon, dir.resolve(com.flurben.flashbackreforged.record.Thumbnails.FILE));

        int index = append(first, a, dir, out, 0);
        append(second, b, dir, out, index);
        out.finished = true;
        out.write(dir);
        return dir;
    }

    private static int append(Path from, ReplayMeta meta, Path dir, ReplayMeta out, int index) throws IOException {
        int offset = out.totalTicks;
        boolean firstOfRecording = index > 0;
        // Each source keeps its own blob files, since what's in its segments points into them by position.
        Map<String, String> blobFiles = new java.util.HashMap<>();
        for (ReplayMeta.Segment s : meta.segments) {
            String file = String.format("seg%04d.gz", index++);
            Files.copy(from.resolve(s.file), dir.resolve(file));
            ReplayMeta.Segment copy = new ReplayMeta.Segment(file, offset + s.startTick);
            copy.ticks = s.ticks;
            copy.discontinuity = firstOfRecording || s.discontinuity;
            if (s.blobs != null) {
                copy.blobs = blobFiles.get(s.blobs);
                if (copy.blobs == null) {
                    copy.blobs = String.format("blobs%04d.bin", index);
                    Files.copy(from.resolve(s.blobs), dir.resolve(copy.blobs));
                    blobFiles.put(s.blobs, copy.blobs);
                }
            }
            firstOfRecording = false;
            out.segments.add(copy);
        }
        for (Map.Entry<Integer, ReplayMeta.Marker> marker : meta.markers.entrySet()) out.markers.put(offset + marker.getKey(), marker.getValue());
        out.totalTicks = offset + meta.totalTicks;
        return index;
    }
}
