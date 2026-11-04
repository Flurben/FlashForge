package com.flurben.flashforge.export;

import java.nio.file.Path;

/** One export job. */
public final class ExportSettings {
    public enum Format {
        MP4_H264("MP4 (H.264)", "mp4", "libx264", "yuv420p", false),
        MP4_H265("MP4 (H.265)", "mp4", "libx265", "yuv420p", false),
        MP4_NVENC("MP4 (H.264, NVIDIA GPU)", "mp4", "h264_nvenc", "yuv420p", false),
        WEBM_VP9("WebM (VP9)", "webm", "libvpx-vp9", "yuv420p", false),
        WEBM_VP9_ALPHA("WebM (VP9 with transparency)", "webm", "libvpx-vp9", "yuva420p", true),
        MOV_PRORES("MOV (ProRes 4444 with transparency)", "mov", "prores_ks", "yuva444p10le", true),
        GIF("GIF", "gif", "gif", "pal8", false),
        PNG_SEQUENCE("PNG Sequence (no FFmpeg needed)", "", "", "", true);

        public final String label;
        public final String extension;
        public final String codec;
        public final String pixelFormat;
        public final boolean alpha;

        Format(String label, String extension, String codec, String pixelFormat, boolean alpha) {
            this.label = label;
            this.extension = extension;
            this.codec = codec;
            this.pixelFormat = pixelFormat;
            this.alpha = alpha;
        }
    }

    public String name = "export";
    public Path output;
    public int width = 1920;
    public int height = 1080;
    public int fps = 60;
    /** Render at this multiple of the output size, then downscale. */
    public int supersample = 1;
    public int startTick;
    public int endTick;
    public Format format = Format.MP4_H264;
    public int bitrateKbps = 20000;
    public boolean audio = true;
    /** Removes the sky so the background is transparent, on formats that can store it. */
    public boolean transparent;
    public boolean resetRng = true;
    /** Number of times to redraw before the first captured frame, giving temporal shaders time to settle. */
    public int warmupFrames = 30;
    /** Maximum number of redraws per frame while waiting for chunks to finish building. */
    public int chunkWaitFrames = 240;
    /** A single frame written as a PNG. */
    public boolean screenshot;
}
