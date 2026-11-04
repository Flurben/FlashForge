package com.flurben.flashforge.export;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Video encoding uses an FFmpeg executable as a separate process. That keeps gigabytes of native codec libraries out of
 * the mod jar and out of Forge's module layer, and a crashing encoder can't take the game down with it.
 */
public final class FfmpegLocator {
    private FfmpegLocator() {}

    /** @return the executable, or null if none could be found. */
    public static Path find() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";

        String configured = System.getProperty("flashforge.ffmpeg");
        if (configured != null && Files.isRegularFile(Path.of(configured))) return Path.of(configured);

        Path local = Minecraft.getInstance().gameDirectory.toPath().resolve("flashforge").resolve(exe);
        if (Files.isRegularFile(local)) return local;

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                try {
                    Path candidate = Path.of(dir.trim()).resolve(exe);
                    if (Files.isRegularFile(candidate)) return candidate;
                } catch (RuntimeException ignored) {
                    // PATH entries can be malformed; those simply aren't candidates.
                }
            }
        }
        return null;
    }
}
