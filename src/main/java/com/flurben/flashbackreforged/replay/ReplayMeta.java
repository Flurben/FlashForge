package com.flurben.flashbackreforged.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Contents of {@code meta.json}. Replays are directories, so a crashed session is still a readable replay. */
public final class ReplayMeta {
    public static final String FILE = "meta.json";
    public static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Segment {
        public String file;
        public int startTick;
        public int ticks;
        /** Starts a different recording: the world is rebuilt here rather than carried over. */
        public boolean discontinuity;

        public Segment() {}

        public Segment(String file, int startTick) {
            this.file = file;
            this.startTick = startTick;
        }
    }

    public static final class Marker {
        public int color = 0xFF5555;
        public String description;
        public Double x, y, z;
        public String dimension;
    }

    public int format = FORMAT;
    public String uuid = UUID.randomUUID().toString();
    public String name = "Unnamed";
    public String world = "";
    public String recordedBy = "";
    public long createdAt = System.currentTimeMillis();
    public String minecraftVersion = "";
    public int protocol;
    public boolean finished;
    public int totalTicks;
    public List<Segment> segments = new ArrayList<>();
    public TreeMap<Integer, Marker> markers = new TreeMap<>();
    /** modid -> version at record time; used to warn when a replay is opened against a different pack. */
    public Map<String, String> mods = new LinkedHashMap<>();
    /** dimension id -> absolute path of the DH database used while recording. */
    public Map<String, String> distantHorizons = new LinkedHashMap<>();
    public boolean hasVoice;

    public static ReplayMeta read(Path replayDir) throws IOException {
        return GSON.fromJson(Files.readString(replayDir.resolve(FILE), StandardCharsets.UTF_8), ReplayMeta.class);
    }

    /** Written atomically: this runs periodically during recording and must survive a crash mid-write. */
    public synchronized void write(Path replayDir) throws IOException {
        Path tmp = replayDir.resolve(FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, replayDir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
