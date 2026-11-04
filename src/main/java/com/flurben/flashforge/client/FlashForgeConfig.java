package com.flurben.flashforge.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client preferences, stored in config/flashforge-client.toml. */
public final class FlashForgeConfig {
    public enum AutoRecord { NEVER, SINGLEPLAYER, MULTIPLAYER, ALWAYS }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<AutoRecord> AUTO_RECORD;
    public static final ForgeConfigSpec.BooleanValue SHOW_INDICATOR;
    public static final ForgeConfigSpec.BooleanValue RECORD_VOICE;
    public static final ForgeConfigSpec.ConfigValue<String> FFMPEG_PATH;
    public static final ForgeConfigSpec.DoubleValue UI_SCALE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        AUTO_RECORD = b.comment("Start recording automatically when joining a world.").defineEnum("autoRecord", AutoRecord.NEVER);
        SHOW_INDICATOR = b.comment("Show a small indicator on the HUD while recording.").define("showRecordingIndicator", true);
        RECORD_VOICE = b.comment("Record Simple Voice Chat audio you can hear, if that mod is installed.").define("recordVoiceChat", true);
        FFMPEG_PATH = b.comment("Full path to ffmpeg. Leave empty to look on PATH and in the flashforge folder.").define("ffmpegPath", "");
        UI_SCALE = b.comment("Size of the replay editor's interface.").defineInRange("editorScale", 1.0, 0.5, 3.0);
        SPEC = b.build();
    }

    private FlashForgeConfig() {}
}
