package com.flurben.flashforge.export;

import com.flurben.flashforge.FlashForge;
import net.minecraft.client.Minecraft;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

/**
 * Captures game audio in exact sync with video frames. While exporting, the sound engine is restarted on an OpenAL
 * loopback device, which plays no sound of its own: this class asks it for exactly one video frame's worth of samples at
 * a time, however long that frame took to render. The sound engine is put back on the real device afterwards.
 */
public final class AudioCapture {
    public static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_SAMPLE = 2;

    private static long loopbackDevice;
    private static boolean armed;

    private final Path file;
    private final RandomAccessFile out;
    private final int samplesPerFrame;
    private final double samplesPerFrameExact;
    private final ByteBuffer buffer;
    private final byte[] bytes;
    private double owedSamples;
    private long totalBytes;

    private AudioCapture(Path file, int fps) throws IOException {
        this.file = file;
        this.out = new RandomAccessFile(file.toFile(), "rw");
        this.out.write(new byte[44]);
        this.samplesPerFrameExact = (double) SAMPLE_RATE / fps;
        this.samplesPerFrame = (int) Math.ceil(this.samplesPerFrameExact) + 1;
        int size = this.samplesPerFrame * CHANNELS * BYTES_PER_SAMPLE;
        this.buffer = MemoryUtil.memAlloc(size);
        this.bytes = new byte[size];
    }

    /** @return a running capture, or null if this system can't do loopback audio; the video then has no game audio. */
    static AudioCapture tryStart(int fps, Path workDir) {
        try {
            AudioCapture capture = new AudioCapture(workDir.resolve("game.wav"), fps);
            armed = true;
            Minecraft.getInstance().getSoundManager().reload();
            if (loopbackDevice == 0) {
                armed = false;
                capture.close();
                FlashForge.LOGGER.warn("Audio capture is not available; exporting without game audio");
                Minecraft.getInstance().getSoundManager().reload();
                return null;
            }
            return capture;
        } catch (Throwable t) {
            armed = false;
            FlashForge.LOGGER.warn("Could not start audio capture", t);
            return null;
        }
    }

    /** Whether the sound engine should open a loopback device instead of the real one. */
    public static boolean armed() {
        return armed;
    }

    /** Called by the sound library hook in place of opening an output device. */
    public static long openLoopback() {
        loopbackDevice = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
        return loopbackDevice;
    }

    /** Attribute list for the loopback context: the format the samples should come back in. */
    public static IntBuffer contextAttributes() {
        IntBuffer attributes = MemoryUtil.memAllocInt(7);
        attributes.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
                .put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE)
                .put(0).flip();
        return attributes;
    }

    /** Called once per captured video frame. */
    void renderFrame() {
        if (loopbackDevice == 0) return;
        Minecraft.getInstance().getSoundManager().updateSource(Minecraft.getInstance().gameRenderer.getMainCamera());
        // 48000 / fps is rarely whole, so fractions are carried over to prevent slow drift out of sync.
        this.owedSamples += this.samplesPerFrameExact;
        int samples = (int) this.owedSamples;
        this.owedSamples -= samples;
        this.buffer.clear();
        SOFTLoopback.alcRenderSamplesSOFT(loopbackDevice, this.buffer, samples);
        int length = samples * CHANNELS * BYTES_PER_SAMPLE;
        this.buffer.position(0).limit(length);
        this.buffer.get(this.bytes, 0, length);
        try {
            this.out.write(this.bytes, 0, length);
            this.totalBytes += length;
        } catch (IOException e) {
            FlashForge.LOGGER.error("Could not write captured audio", e);
        }
    }

    Path wavFile() {
        return this.file;
    }

    void stop() {
        armed = false;
        loopbackDevice = 0;
        // Back to the real output device.
        Minecraft.getInstance().getSoundManager().reload();
        close();
    }

    private void close() {
        try {
            writeHeader();
            this.out.close();
        } catch (IOException e) {
            FlashForge.LOGGER.error("Could not finish captured audio file", e);
        }
        MemoryUtil.memFree(this.buffer);
    }

    private void writeHeader() throws IOException {
        this.out.seek(0);
        ByteBuffer h = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + this.totalBytes)).put(new byte[]{'W', 'A', 'V', 'E'});
        h.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) CHANNELS).putInt(SAMPLE_RATE);
        h.putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE).putShort((short) (CHANNELS * BYTES_PER_SAMPLE)).putShort((short) (BYTES_PER_SAMPLE * 8));
        h.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) this.totalBytes);
        this.out.write(h.array());
    }
}
