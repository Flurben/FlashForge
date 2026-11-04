package com.flurben.flashforge.compat;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.client.FlashForgeConfig;
import com.flurben.flashforge.export.AudioCapture;
import com.flurben.flashforge.export.ExportManager;
import com.flurben.flashforge.record.Recorder;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.audiochannel.ClientEntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.ClientLocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.ClientStaticAudioChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores voice as decoded audio with who said it and how it was positioned, then plays it through Simple Voice Chat's
 * own channels so it is heard from the right place. The mod's output goes to its own sound device, which an export's
 * loopback can't hear, so voice is also fed straight into the export's audio.
 */
public final class VoiceChatCompat {
    public static final byte STATIC = 0, LOCATIONAL = 1, ENTITY = 2;
    private static final boolean LOADED = ModList.get().isLoaded("voicechat");
    private static int errors;

    private VoiceChatCompat() {}

    public static boolean loaded() {
        return LOADED;
    }

    /** Voice events arrive on the mod's network thread; the recording is only written from the client thread. */
    public static void record(byte kind, UUID source, short[] samples, boolean whispering, float distance, Vec3 position) {
        if (samples == null || samples.length == 0) return;
        Minecraft.getInstance().execute(() -> {
            Recorder recorder = Recorder.active();
            if (recorder == null || !FlashForgeConfig.RECORD_VOICE.get()) return;
            recorder.meta().hasVoice = true;
            recorder.writeVoice(b -> {
                b.writeByte(kind);
                b.writeUUID(source);
                b.writeBoolean(whispering);
                b.writeFloat(distance);
                if (kind == LOCATIONAL) {
                    b.writeDouble(position.x);
                    b.writeDouble(position.y);
                    b.writeDouble(position.z);
                }
                b.writeVarInt(samples.length);
                for (short s : samples) b.writeShort(s);
            });
        });
    }

    public static void playback(FriendlyByteBuf b) {
        if (!LOADED) return;
        try {
            byte kind = b.readByte();
            UUID source = b.readUUID();
            boolean whispering = b.readBoolean();
            float distance = b.readFloat();
            Vec3 position = kind == LOCATIONAL ? new Vec3(b.readDouble(), b.readDouble(), b.readDouble()) : null;
            short[] samples = new short[b.readVarInt()];
            for (int i = 0; i < samples.length; i++) samples[i] = b.readShort();

            Vec3 from = kind == STATIC ? null : position != null ? position : sourcePosition(source);
            if (ExportManager.isExporting()) {
                AudioCapture.addVoice(samples, gainAt(from, distance));
                return;
            }
            Channels.play(kind, source, samples, whispering, distance, position);
        } catch (Throwable t) {
            if (errors++ < 8) FlashForge.LOGGER.warn("Could not play recorded voice", t);
        }
    }

    private static Vec3 sourcePosition(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        var player = mc.level.getPlayerByUUID(id);
        return player == null ? null : player.position();
    }

    /** A linear falloff over the voice's range, matching how far away it could be heard in game. */
    private static float gainAt(Vec3 from, float distance) {
        if (from == null) return 1f;
        Vec3 at = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return (float) Math.max(0, 1 - from.distanceTo(at) / Math.max(1, distance));
    }

    /** Separate class so voice chat types are only loaded when the mod is installed. */
    private static final class Channels {
        private static final Map<UUID, ClientStaticAudioChannel> STATIC_CHANNELS = new HashMap<>();
        private static final Map<UUID, ClientLocationalAudioChannel> LOCATIONAL_CHANNELS = new HashMap<>();
        private static final Map<UUID, ClientEntityAudioChannel> ENTITY_CHANNELS = new HashMap<>();

        static void play(byte kind, UUID source, short[] samples, boolean whispering, float distance, Vec3 position) {
            VoicechatClientApi api = FlashForgeVoicePlugin.api();
            if (api == null) return;
            switch (kind) {
                case STATIC -> STATIC_CHANNELS.computeIfAbsent(source, api::createStaticAudioChannel).play(samples);
                case LOCATIONAL -> {
                    Position where = api.createPosition(position.x, position.y, position.z);
                    ClientLocationalAudioChannel channel = LOCATIONAL_CHANNELS.computeIfAbsent(source, id -> api.createLocationalAudioChannel(id, where));
                    channel.setLocation(where);
                    channel.setDistance(distance);
                    channel.play(samples);
                }
                default -> {
                    ClientEntityAudioChannel channel = ENTITY_CHANNELS.computeIfAbsent(source, api::createEntityAudioChannel);
                    channel.setWhispering(whispering);
                    channel.setDistance(distance);
                    channel.play(samples);
                }
            }
        }
    }
}
