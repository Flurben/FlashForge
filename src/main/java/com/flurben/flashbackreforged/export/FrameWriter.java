package com.flurben.flashbackreforged.export;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.mojang.blaze3d.platform.NativeImage;

import com.flurben.flashbackreforged.mixin.accessor.NativeImageAccessor;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Receives raw RGBA frames (bottom row first, as OpenGL reads them) straight from mapped graphics memory, and copies and
 * writes them on a background thread. How many can be waiting is limited by {@link FrameCapture}'s buffers, so a slow
 * encoder slows the render instead of filling memory. PNGs are compressed on several threads at once, since they're written
 * to separate files and take far longer than rendering does.
 */
final class FrameWriter {
    private static final Frame END = new Frame(null, null);
    private static final int PNG_THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));

    /** @param done says the pixels have been copied and the memory can go back to the graphics card. */
    private record Frame(ByteBuffer pixels, Runnable done) {}

    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
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

    /** Render thread. The pixels must stay readable until {@code done} runs, which it always does unless this is aborted. */
    void submit(ByteBuffer pixels, Runnable done) {
        this.queue.add(new Frame(pixels, done));
    }

    private void runEncoder() {
        byte[] copy = new byte[this.renderWidth * this.renderHeight * 4];
        try (OutputStream out = new BufferedOutputStream(this.process.getOutputStream(), 1 << 20)) {
            while (true) {
                Frame frame = this.queue.take();
                if (frame == END) break;
                frame.pixels.get(0, copy);
                frame.done.run();
                out.write(copy);
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
        ExecutorService pool = Executors.newFixedThreadPool(PNG_THREADS, r -> {
            Thread thread = new Thread(r, "Flashback Reforged PNG Compressor");
            thread.setDaemon(true);
            return thread;
        });
        // A little more than there are threads, so one is always ready, and no more, because each is a whole frame in memory.
        Semaphore room = new Semaphore(PNG_THREADS + 2);
        try {
            while (true) {
                Frame frame = this.queue.take();
                if (frame == END) break;
                Path file = this.settings.screenshot ? this.settings.output : this.settings.output.resolve(String.format("frame_%06d.png", this.written));
                room.acquire();
                NativeImage image = copyToImage(frame.pixels);
                frame.done.run();
                this.written++;
                pool.execute(() -> {
                    try (image) {
                        if (!this.settings.transparent) makeOpaque(image);
                        image.writeToFile(file);
                    } catch (IOException e) {
                        this.error = "Could not write image: " + e.getMessage();
                        FlashbackReforged.LOGGER.error(this.error);
                    } finally {
                        room.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Also turns the picture the right way up: OpenGL's first row is the bottom one. */
    private NativeImage copyToImage(ByteBuffer pixels) {
        int w = this.renderWidth, h = this.renderHeight, stride = w * 4;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        long to = ((NativeImageAccessor) (Object) image).flashbackreforged$pixels();
        long from = MemoryUtil.memAddress(pixels);
        for (int y = 0; y < h; y++) MemoryUtil.memCopy(from + (long) (h - 1 - y) * stride, to + (long) y * stride, stride);
        return image;
    }

    /** The game leaves meaningless alpha in its framebuffer unless the background was removed on purpose. */
    private void makeOpaque(NativeImage image) {
        long address = ((NativeImageAccessor) (Object) image).flashbackreforged$pixels();
        long end = address + (long) this.renderWidth * this.renderHeight * 4;
        for (long at = address + 3; at < end; at += 4) MemoryUtil.memPutByte(at, (byte) 0xFF);
    }

    /** After a failure, keep consuming, so that frames being waited on still count as done. */
    private void drainQueueUntilEnd() {
        try {
            while (true) {
                Frame frame = this.queue.take();
                if (frame == END) return;
                frame.done.run();
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
        this.queue.add(END);
        this.thread.join();
        if (this.process != null) {
            int exit = this.process.waitFor();
            if (exit != 0 && this.error == null) this.error = "FFmpeg exited with code " + exit + ". See the log for details.";
        }
    }

    /** @return whether the writer has really stopped, so that nothing is reading from mapped memory any more. */
    boolean abort() {
        this.queue.clear();
        this.queue.add(END);
        if (this.process != null) this.process.destroy();
        try {
            this.thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !this.thread.isAlive();
    }
}
