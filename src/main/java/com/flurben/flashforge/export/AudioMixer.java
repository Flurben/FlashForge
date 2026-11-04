package com.flurben.flashforge.export;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.editor.Keyframe;
import com.flurben.flashforge.editor.KeyframeTrack;
import com.flurben.flashforge.editor.Scene;
import com.flurben.flashforge.editor.TrackType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Combines the rendered video, captured game audio and Audio track files into the final file. */
final class AudioMixer {
    private AudioMixer() {}

    record Clip(Path file, double delaySeconds, double volume) {}

    static List<Clip> clipsFrom(Scene scene, ExportSettings settings) {
        List<Clip> clips = new ArrayList<>();
        if (!settings.audio) return clips;
        for (KeyframeTrack track : scene.tracks) {
            if (track.type != TrackType.AUDIO || !track.enabled) continue;
            for (Map.Entry<Integer, Keyframe> entry : track.keyframes.entrySet()) {
                Keyframe k = entry.getValue();
                if (k.text == null || k.text.isBlank() || entry.getKey() >= settings.endTick) continue;
                Path file = Path.of(k.text);
                if (!Files.isRegularFile(file)) {
                    FlashForge.LOGGER.warn("Skipping audio keyframe: file not found: {}", file);
                    continue;
                }
                double delay = Math.max(0, (entry.getKey() - settings.startTick) / 20.0);
                clips.add(new Clip(file, delay, k.values[0]));
            }
        }
        return clips;
    }

    static void mux(Path ffmpeg, Path video, Path gameAudio, List<Clip> clips, ExportSettings settings) throws IOException, InterruptedException {
        List<String> c = new ArrayList<>(List.of(ffmpeg.toString(), "-y", "-loglevel", "warning", "-i", video.toString()));
        int inputs = 1;
        StringBuilder filter = new StringBuilder();
        List<String> labels = new ArrayList<>();
        if (gameAudio != null && Files.exists(gameAudio)) {
            c.addAll(List.of("-i", gameAudio.toString()));
            labels.add("[" + inputs + ":a]");
            inputs++;
        }
        for (Clip clip : clips) {
            c.addAll(List.of("-i", clip.file().toString()));
            long delayMs = Math.round(clip.delaySeconds() * 1000);
            String label = "[c" + inputs + "]";
            filter.append('[').append(inputs).append(":a]adelay=").append(delayMs).append(":all=1,volume=").append(clip.volume()).append(label).append(';');
            labels.add(label);
            inputs++;
        }
        String out = "[mixed]";
        filter.append(String.join("", labels)).append("amix=inputs=").append(labels.size()).append(":normalize=0:dropout_transition=0").append(out);
        c.addAll(List.of("-filter_complex", filter.toString(), "-map", "0:v", "-map", out, "-c:v", "copy", "-shortest"));
        boolean webm = settings.format == ExportSettings.Format.WEBM_VP9 || settings.format == ExportSettings.Format.WEBM_VP9_ALPHA;
        c.addAll(webm ? List.of("-c:a", "libopus") : settings.format == ExportSettings.Format.MOV_PRORES ? List.of("-c:a", "pcm_s16le") : List.of("-c:a", "aac", "-b:a", "256k"));
        c.add(settings.output.toString());

        FlashForge.LOGGER.info("Muxing: {}", String.join(" ", c));
        Process process = new ProcessBuilder(c).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("Timed out combining audio and video");
        }
        if (process.exitValue() != 0) throw new IOException("FFmpeg could not combine audio and video: " + log);
    }
}
