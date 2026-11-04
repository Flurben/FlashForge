package com.flurben.flashbackreforged.replay;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Where replays live on disk, and listing/removing them. */
public final class ReplayStorage {
    private ReplayStorage() {}

    public record Entry(Path dir, ReplayMeta meta) {}

    private static Path dir(String name) {
        Path p = Minecraft.getInstance().gameDirectory.toPath().resolve("flashback_reforged").resolve(name);
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
        }
        return p;
    }

    public static Path root() {
        return dir("replays");
    }

    public static Path exports() {
        return dir("exports");
    }

    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root())) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                try {
                    out.add(new Entry(d, ReplayMeta.read(d)));
                } catch (Exception ignored) {
                    // Not a replay, or metadata was never written.
                }
            });
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong((Entry e) -> e.meta.createdAt).reversed());
        return out;
    }

    public static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    public static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return s.isEmpty() ? "replay" : s;
    }
}
