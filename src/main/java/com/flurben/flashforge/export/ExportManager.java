package com.flurben.flashforge.export;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.compat.RenderReadiness;
import com.flurben.flashforge.editor.Scene;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.replay.ReplayStorage;
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
            FlashForge.LOGGER.error("Could not start export", e);
            lastError = e.getMessage();
            job = null;
        }
    }

    /** The time shader packs should see: real time, except during an export, where it follows video frames. */
    public static long shaderClock(long realNanos) {
        Job current = job;
        return current == null ? realNanos : current.clockFrames * 1_000_000_000L / current.settings.fps;
    }

    /** True while an export is rendering with no sky behind the world. */
    public static boolean transparentBackground() {
        return job != null && job.settings.transparent;
    }

    /** Called after the world is drawn and before it reaches the window. */
    public static void onFrameRendered() {
        if (job != null) job.frameRendered();
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
            if (this.cancel || this.complete || this.writer.error() != null) {
                finish();
                return -1;
            }
            this.capturedThisFrame = false;
            this.clockFrames++;

            if (!this.sized) {
                this.sized = true;
                this.capture = new FrameCapture(this.renderWidth, this.renderHeight);
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
            for (int i = 0; i < ticks; i++) this.replay.step();
            this.reachedTick = whole;
            this.position = renderedAt + this.replay.editor().scene().speedAt(renderedAt) * 20.0 / this.settings.fps;

            timer.partialTick = (float) fraction;
            timer.tickDelta = ticks;
            this.replay.editor().applyFrame(renderedAt);
            return ticks;
        }

        void frameRendered() {
            if (!this.capturedThisFrame || this.capture == null) return;
            submit(this.capture.capture(this.mc.getMainRenderTarget()));
            this.frame++;
            if (this.audio != null) this.audio.renderFrame();
            if (this.frame >= this.totalFrames) this.complete = true;
        }

        private void submit(byte[] frameData) {
            if (frameData == null) return;
            try {
                this.writer.submit(frameData);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void finish() {
            this.state = "Finishing";
            if (this.capture != null) {
                if (!this.cancel) {
                    byte[] remaining;
                    while ((remaining = this.capture.drainNext()) != null) submit(remaining);
                }
                this.capture.close();
            }
            job = null;
            // renderSize() is now null, so this restores the window's real size.
            this.mc.resizeDisplay();
            this.replay.camera().setInputEnabled(true);
            GLFW.glfwSwapInterval(this.mc.options.enableVsync().get() ? 1 : 0);
            if (this.audio != null) this.audio.stop();

            if (this.cancel) {
                this.writer.abort();
                deleteWorkDir();
                HISTORY.add(this.settings.name + " (cancelled)");
                return;
            }
            try {
                this.writer.finish();
                String error = this.writer.error();
                if (error != null) throw new IOException(error + " " + String.join(" | ", tail(this.writer.encoderLog())));
                Path output = finalizeOutput();
                lastOutput = output;
                HISTORY.add(this.settings.name + " -> " + output);
                FlashForge.LOGGER.info("Export finished: {} ({} frames)", output, this.frame);
            } catch (Exception e) {
                FlashForge.LOGGER.error("Export failed", e);
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
                FlashForge.LOGGER.debug("Could not remove export temp folder", e);
            }
        }
    }
}
