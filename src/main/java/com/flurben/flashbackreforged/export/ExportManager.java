package com.flurben.flashbackreforged.export;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.compat.RenderReadiness;
import com.flurben.flashbackreforged.editor.Scene;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.replay.ReplayStorage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Runs export jobs. Rather than using a private render path, this takes control of the clock and lets the game's normal
 * frame loop run, so shader packs, Distant Horizons and every mod's render hook behave as they do in play. Each loop
 * iteration is exactly one video frame: {@link #onAdvanceTime} decides how far time moves, and {@link #onFrameRendered}
 * collects the image.
 */
public final class ExportManager {
    private static final Deque<ExportSettings> QUEUE = new ArrayDeque<>();
    private static final List<String> HISTORY = new ArrayList<>();
    private static Job job;
    private static boolean running;
    private static String lastError;
    private static Path lastOutput;

    private ExportManager() {}

    public static boolean isExporting() {
        return job != null;
    }

    /** Starts as soon as the current job is done. */
    public static void enqueue(ExportSettings settings) {
        QUEUE.addLast(settings);
        running = true;
    }

    /** Waits with the rest of the queue until {@link #startQueue()}. */
    public static void hold(ExportSettings settings) {
        QUEUE.addLast(settings);
    }

    public static void startQueue() {
        running = true;
    }

    public static Deque<ExportSettings> queue() {
        return QUEUE;
    }

    public static List<String> history() {
        return HISTORY;
    }

    public static String lastError() {
        return lastError;
    }

    public static Path lastOutput() {
        return lastOutput;
    }

    public static double progress() {
        return job == null ? 0 : job.progress();
    }

    public static String status() {
        return job == null ? "" : job.status();
    }

    public static void cancel() {
        if (job != null) job.cancel = true;
    }

    /** Called at the start of each frame's clock update. Returns the ticks to run, or -1 to leave the clock alone. */
    public static int onAdvanceTime(net.minecraft.client.Timer timer) {
        if (job == null) {
            if (QUEUE.isEmpty()) running = false;
            if (!running || !ReplayPlayer.isActive()) return -1;
            start(QUEUE.pollFirst());
            if (job == null) return -1;
        }
        return job.advance(timer);
    }

    private static void start(ExportSettings settings) {
        try {
            job = new Job(settings);
            lastError = null;
        } catch (Exception e) {
            FlashbackReforged.LOGGER.error("Could not start export", e);
            lastError = e.getMessage();
            job = null;
        }
    }

    private static long lastShaderClock;
    private static long shaderClockOffset;
    private static boolean clockInExport;

    /** The time shader packs should see: real time, except during an export, where it follows video frames. */
    public static long shaderClock(long realNanos) {
        Job current = job;
        long value;
        if (current != null) {
            // Must carry on from the last value: shaders feed the difference into exposure and temporal buffers, and one
            // large negative frame time leaves them black for the rest of the export.
            if (current.clockBase < 0) current.clockBase = lastShaderClock;
            value = current.clockBase + current.clockFrames * 1_000_000_000L / current.settings.fps;
            clockInExport = true;
        } else {
            if (clockInExport) {
                clockInExport = false;
                shaderClockOffset = lastShaderClock + 16_666_666L - realNanos;
            }
            value = realNanos + shaderClockOffset;
        }
        lastShaderClock = value;
        return value;
    }

    /** True while an export is rendering with no sky behind the world. */
    public static boolean transparentBackground() {
        return job != null && job.settings.transparent;
    }

    /** Called after the world is drawn and before it reaches the window. */
    public static void onFrameRendered() {
        if (job != null) job.frameRendered();
    }

    private static java.util.Random rng;

    /** True during an export that wants the same result every time. */
    public static boolean rngActive() {
        return job != null && job.settings.resetRng && rng != null;
    }

    public static long nextSeed() {
        return rng.nextLong();
    }

    /** Size the game should render at during an export, or null for the window's real size. */
    public static int[] renderSize() {
        return job == null ? null : new int[]{job.renderWidth, job.renderHeight};
    }

    private static final class Job {
        final ExportSettings settings;
        final int renderWidth;
        final int renderHeight;
        final ReplayPlayer replay;
        final Minecraft mc = Minecraft.getInstance();
        final Path workDir;
        final FrameWriter writer;
        final AudioCapture audio;
        FrameCapture capture;
        long clockBase = -1;
        final int totalFrames;

        /** Timeline position of the next frame to render. */
        double position;
        /** The whole tick the world has been brought up to. */
        int reachedTick;
        int warmup;
        int chunkWait;
        int frame;
        /** Every frame drawn, including ones that aren't saved. */
        long clockFrames;
        boolean capturedThisFrame;
        long frameStart;
        boolean sized;
        boolean cancel;
        boolean complete;
        String state = "Preparing";

        Job(ExportSettings settings) throws IOException {
            this.settings = settings;
            this.replay = ReplayPlayer.active();
            this.renderWidth = settings.width * settings.supersample;
            this.renderHeight = settings.height * settings.supersample;
            this.workDir = Files.createTempDirectory(ReplayStorage.exports(), "tmp");
            this.writer = new FrameWriter(settings, this.renderWidth, this.renderHeight, FfmpegLocator.find(), this.workDir);
            this.totalFrames = settings.screenshot ? 1 : countFrames(settings);
            boolean wantsAudio = settings.audio && !settings.screenshot && settings.format != ExportSettings.Format.PNG_SEQUENCE && settings.format != ExportSettings.Format.GIF;
            this.audio = wantsAudio ? AudioCapture.tryStart(settings.fps, this.workDir) : null;
            this.position = settings.startTick;
            this.reachedTick = settings.startTick;
            this.warmup = settings.warmupFrames;
            this.replay.setPaused(true);
            this.replay.seek(settings.startTick);
            this.replay.camera().setInputEnabled(false);
            this.mc.getToasts().clear();
            this.mc.setScreen(null);
            GLFW.glfwSwapInterval(0);
        }

        private int countFrames(ExportSettings s) {
            Scene scene = this.replay.editor().scene();
            double t = s.startTick;
            int n = 0;
            double perFrame = 20.0 / s.fps;
            while (t < s.endTick) {
                t += scene.speedAt(t) * perFrame;
                n++;
            }
            return Math.max(1, n);
        }

        double progress() {
            return (double) this.frame / this.totalFrames;
        }

        String status() {
            return this.state + " - frame " + this.frame + " / " + this.totalFrames;
        }

        /** Decides how far the clock moves this frame. This runs at the top of the frame, where world changes are safe. */
        int advance(net.minecraft.client.Timer timer) {
            if (com.flurben.flashbackreforged.perf.Perf.on && this.frameStart != 0) com.flurben.flashbackreforged.perf.Perf.add("export.wholeFrame", System.nanoTime() - this.frameStart);
            this.frameStart = System.nanoTime();
            if (this.cancel || this.complete || this.writer.error() != null) {
                finish();
                return -1;
            }
            this.capturedThisFrame = false;
            this.clockFrames++;
            rng = this.settings.resetRng ? new java.util.Random(0x5DEECE66DL ^ (long) Math.floor(this.position) * 0x9E3779B97F4A7C15L) : null;
            // Falling rain and snow are drawn from this counter, which otherwise depends on how long the game has been open.
            if (this.settings.resetRng) ((com.flurben.flashbackreforged.mixin.accessor.LevelRendererAccessor) this.mc.levelRenderer).flashbackreforged$setTicks((int) Math.floor(this.position));

            if (!this.sized) {
                this.sized = true;
                this.capture = new FrameCapture(this.renderWidth, this.renderHeight, this.writer);
                this.mc.resizeDisplay();
            }
            this.replay.runPendingSeek();

            double fraction = this.position - Math.floor(this.position);
            if (this.warmup > 0 || (!RenderReadiness.terrainReady() && this.chunkWait < this.settings.chunkWaitFrames)) {
                this.state = "Waiting for chunks";
                if (this.warmup > 0) this.warmup--;
                else this.chunkWait++;
                timer.partialTick = (float) fraction;
                this.replay.editor().applyFrame(this.position);
                return 0;
            }

            this.chunkWait = 0;
            this.state = "Rendering";
            this.capturedThisFrame = true;

            // Bring the world up to this frame's tick, then render at its fraction.
            double renderedAt = this.position;
            int whole = (int) Math.floor(renderedAt);
            int ticks = Math.max(0, whole - this.reachedTick);
            long stepStart = com.flurben.flashbackreforged.perf.Perf.start();
            for (int i = 0; i < ticks; i++) this.replay.step();
            com.flurben.flashbackreforged.perf.Perf.end("export.replayStep", stepStart);
            this.reachedTick = whole;
            this.position = renderedAt + this.replay.editor().scene().speedAt(renderedAt) * 20.0 / this.settings.fps;

            timer.partialTick = (float) fraction;
            timer.tickDelta = ticks;
            this.replay.editor().applyFrame(renderedAt);
            return ticks;
        }

        void frameRendered() {
            if (!this.capturedThisFrame || this.capture == null) return;
            long captureStart = com.flurben.flashbackreforged.perf.Perf.start();
            this.capture.capture(this.mc.getMainRenderTarget());
            com.flurben.flashbackreforged.perf.Perf.end("export.capture", captureStart);
            this.frame++;
            long audioStart = com.flurben.flashbackreforged.perf.Perf.start();
            if (this.audio != null) this.audio.renderFrame();
            com.flurben.flashbackreforged.perf.Perf.end("export.audio", audioStart);
            if (this.frame >= this.totalFrames) this.complete = true;
        }

        void finish() {
            this.state = "Finishing";
            boolean stopped = true;
            if (this.cancel) {
                // Before the memory it might be copying from goes away.
                stopped = this.writer.abort();
            } else if (this.capture != null) {
                this.capture.drain();
            }
            // If the writer somehow won't stop, leaking some memory beats pulling it out from under the writer.
            if (this.capture != null && stopped) this.capture.close();
            job = null;
            // renderSize() is now null, so this restores the window's real size.
            this.mc.resizeDisplay();
            this.replay.camera().setInputEnabled(true);
            GLFW.glfwSwapInterval(this.mc.options.enableVsync().get() ? 1 : 0);
            if (this.audio != null) this.audio.stop();

            if (this.cancel) {
                deleteWorkDir();
                HISTORY.add(this.settings.name + " (cancelled)");
                return;
            }
            long finishStart = com.flurben.flashbackreforged.perf.Perf.start();
            try {
                this.writer.finish();
                com.flurben.flashbackreforged.perf.Perf.end("export.waitForEncoder", finishStart);
                String error = this.writer.error();
                if (error != null) throw new IOException(error + " " + String.join(" | ", tail(this.writer.encoderLog())));
                Path output = finalizeOutput();
                lastOutput = output;
                HISTORY.add(this.settings.name + " -> " + output);
                FlashbackReforged.LOGGER.info("Export finished: {} ({} frames)", output, this.frame);
            } catch (Exception e) {
                FlashbackReforged.LOGGER.error("Export failed", e);
                lastError = e.getMessage();
                HISTORY.add(this.settings.name + " (failed)");
            }
            deleteWorkDir();
        }

        private static List<String> tail(List<String> lines) {
            return lines.subList(Math.max(0, lines.size() - 5), lines.size());
        }

        private Path finalizeOutput() throws Exception {
            if (this.settings.screenshot || this.settings.format == ExportSettings.Format.PNG_SEQUENCE) return this.settings.output;
            Path video = this.writer.videoFile();
            Files.createDirectories(this.settings.output.getParent());
            List<AudioMixer.Clip> clips = AudioMixer.clipsFrom(this.replay.editor().scene(), this.settings);
            Path game = this.audio == null ? null : this.audio.wavFile();
            if ((game == null || !Files.exists(game)) && clips.isEmpty()) {
                Files.move(video, this.settings.output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                AudioMixer.mux(FfmpegLocator.find(), video, game, clips, this.settings);
            }
            return this.settings.output;
        }

        private void deleteWorkDir() {
            try {
                ReplayStorage.delete(this.workDir);
            } catch (IOException e) {
                FlashbackReforged.LOGGER.debug("Could not remove export temp folder", e);
            }
        }
    }
}
