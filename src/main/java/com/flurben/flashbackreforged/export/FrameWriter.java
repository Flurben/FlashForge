package com.flurben.flashbackreforged.export;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Receives raw RGBA frames (bottom row first, as OpenGL reads them) and writes them on a background thread. The queue is
 * small and blocking, so a slow encoder slows the render instead of filling memory.
 */
final class FrameWriter {
    private static final byte[] END = new byte[0];

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6);
    private final ExportSettings settings;
    private final int renderWidth;
    private final int renderHeight;
    private final Path videoFile;
    private final Thread thread;
    private final List<String> encoderLog = new ArrayList<>();
    private Process process;
    private volatile String error;
    private volatile int written;

    FrameWriter(ExportSettings settings, int renderWidth, int renderHeight, Path ffmpeg, Path workDir) throws IOException {
        this.settings = settings;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.videoFile = workDir.resolve("video." + (settings.format.extension.isEmpty() ? "bin" : settings.format.extension));

        if (settings.format == ExportSettings.Format.PNG_SEQUENCE || settings.screenshot) {
            Files.createDirectories(settings.screenshot ? settings.output.getParent() : settings.output);
            this.thread = new Thread(this::runPng, "Flashback Reforged PNG Writer");
        } else {
            if (ffmpeg == null) throw new IOException("FFmpeg was not found. Put ffmpeg on your PATH or in the flashback_reforged folder, or export a PNG sequence.");
            List<String> cmd = videoCommand(ffmpeg);
            FlashbackReforged.LOGGER.info("Starting encoder: {}", String.join(" ", cmd));
            this.process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread drain = new Thread(this::drainEncoderLog, "Flashback Reforged Encoder Log");
            drain.setDaemon(true);
            drain.start();
            this.thread = new Thread(this::runEncoder, "Flashback Reforged Frame Writer");
        }
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private List<String> videoCommand(Path ffmpeg) {
        ExportSettings s = this.settings;
        List<String> c = new ArrayList<>(List.of(ffmpeg.toString(), "-y", "-loglevel", "warning",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", this.renderWidth + "x" + this.renderHeight, "-r", String.valueOf(s.fps), "-i", "-"));
        String filter = "vflip";
        if (s.supersample > 1) filter += ",scale=" + s.width + ":" + s.height + ":flags=area";
        if (s.format == ExportSettings.Format.GIF) {
            filter += ",split[a][b];[a]palettegen[p];[b][p]paletteuse";
            c.addAll(List.of("-filter_complex", filter));
        } else {
            c.addAll(List.of("-vf", filter));
            // Without a transparent background, framebuffer alpha is meaningless and must not reach the file.
            String pixels = s.format.alpha && !s.transparent ? s.format.pixelFormat.replace("yuva", "yuv") : s.format.pixelFormat;
            c.addAll(List.of("-c:v", s.format.codec, "-pix_fmt", pixels));
            if (s.format != ExportSettings.Format.MOV_PRORES) c.addAll(List.of("-b:v", s.bitrateKbps + "k"));
            if (s.format == ExportSettings.Format.MP4_H264 || s.format == ExportSettings.Format.MP4_H265) c.addAll(List.of("-preset", "medium"));
            if (s.format == ExportSettings.Format.MOV_PRORES) c.addAll(List.of("-profile:v", "4444"));
        }
        c.add(this.videoFile.toString());
        return c;
    }

    Path videoFile() {
        return this.videoFile;
    }

    int written() {
        return this.written;
    }

    String error() {
        return this.error;
    }

    List<String> encoderLog() {
        synchronized (this.encoderLog) {
            return new ArrayList<>(this.encoderLog);
        }
    }

    /** Blocks if the writer has fallen behind. */
    void submit(byte[] frame) throws InterruptedException {
        this.queue.put(frame);
    }

    private void runEncoder() {
        try (OutputStream out = new BufferedOutputStream(this.process.getOutputStream(), 1 << 20)) {
            while (true) {
                byte[] frame = this.queue.take();
                if (frame == END) break;
                out.write(frame);
                this.written++;
            }
        } catch (IOException e) {
            this.error = "The encoder stopped accepting frames: " + e.getMessage();
            FlashbackReforged.LOGGER.error(this.error);
            drainQueueUntilEnd();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runPng() {
        try {
            while (true) {
                byte[] frame = this.queue.take();
                if (frame == END) break;
                Path file = this.settings.screenshot ? this.settings.output : this.settings.output.resolve(String.format("frame_%06d.png", this.written));
                writePng(frame, file);
                this.written++;
            }
        } catch (IOException e) {
            this.error = "Could not write image: " + e.getMessage();
            FlashbackReforged.LOGGER.error(this.error);
            drainQueueUntilEnd();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writePng(byte[] frame, Path file) throws IOException {
        int w = this.renderWidth, h = this.renderHeight;
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false)) {
            int stride = w * 4;
            for (int y = 0; y < h; y++) {
                int row = (h - 1 - y) * stride;
                for (int x = 0; x < w; x++) {
                    int i = row + x * 4;
                    // The game leaves meaningless alpha in its framebuffer unless the background was removed on purpose.
                    int r = frame[i] & 0xFF, g = frame[i + 1] & 0xFF, b = frame[i + 2] & 0xFF, a = this.settings.transparent ? frame[i + 3] & 0xFF : 0xFF;
                    image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            image.writeToFile(file);
        }
    }

    /** After a failure, keep consuming so the render thread can't block forever on a full queue. */
    private void drainQueueUntilEnd() {
        try {
            while (this.queue.take() != END) {
                Thread.onSpinWait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainEncoderLog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                FlashbackReforged.LOGGER.info("[ffmpeg] {}", line);
                synchronized (this.encoderLog) {
                    this.encoderLog.add(line);
                }
            }
        } catch (IOException ignored) {
            // The process has ended.
        }
    }

    /** Waits for everything to be written and the encoder to finish. */
    void finish() throws InterruptedException {
        this.queue.put(END);
        this.thread.join();
        if (this.process != null) {
            int exit = this.process.waitFor();
            if (exit != 0 && this.error == null) this.error = "FFmpeg exited with code " + exit + ". See the log for details.";
        }
    }

    void abort() {
        this.queue.clear();
        this.queue.offer(END);
        if (this.process != null) this.process.destroy();
    }
}
