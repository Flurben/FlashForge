package com.flurben.flashbackreforged.record;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;

import java.util.Set;

/**
 * Which server packets belong in a replay. Anything about the local player's own session is dropped: a
 * viewer has no menus, and replaying GUI packets against a viewer is a classic source of disconnects
 * (a modded container_set_content larger than the viewer's menu is a fatal protocol error).
 * <p>
 * Decisions can also be made from a packet's id, so that playback doesn't have to decode what it's going to skip.
 */
public final class PacketPolicy {
    private static final Set<Class<?>> DROP_AT_RECORD = Set.of(
            ClientboundKeepAlivePacket.class,
            ClientboundPingPacket.class,
            ClientboundDisconnectPacket.class,
            // Huge in modded packs. Viewers can't craft, and leaving it out avoids JEI/EMI reindexing on open.
            ClientboundUpdateRecipesPacket.class,
            ClientboundRecipePacket.class,
            ClientboundUpdateAdvancementsPacket.class,
            ClientboundSelectAdvancementsTabPacket.class,
            ClientboundCommandsPacket.class,
            ClientboundCommandSuggestionsPacket.class,
            ClientboundOpenScreenPacket.class,
            ClientboundOpenBookPacket.class,
            ClientboundOpenSignEditorPacket.class,
            ClientboundHorseScreenOpenPacket.class,
            ClientboundMerchantOffersPacket.class,
            ClientboundContainerClosePacket.class,
            ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetSlotPacket.class,
            ClientboundContainerSetDataPacket.class,
            ClientboundPlaceGhostRecipePacket.class,
            ClientboundCooldownPacket.class,
            ClientboundTagQueryPacket.class,
            ClientboundResourcePackPacket.class,
            ClientboundPlayerLookAtPacket.class,
            ClientboundBlockChangedAckPacket.class);

    private static final Set<Class<?>> DROP_AT_PLAYBACK = Set.of(
            ClientboundPlayerPositionPacket.class,
            ClientboundPlayerAbilitiesPacket.class,
            ClientboundSetCameraPacket.class,
            ClientboundSetHealthPacket.class,
            ClientboundSetExperiencePacket.class,
            ClientboundPlayerCombatKillPacket.class,
            ClientboundMoveVehiclePacket.class,
            ClientboundSetCarriedItemPacket.class,
            ClientboundSetDefaultSpawnPositionPacket.class,
            ClientboundDeleteChatPacket.class,
            ClientboundServerDataPacket.class,
            ClientboundCustomChatCompletionsPacket.class);

    private static final Set<Class<?>> TRANSIENT_EFFECT = Set.of(
            ClientboundSoundPacket.class,
            ClientboundSoundEntityPacket.class,
            ClientboundStopSoundPacket.class,
            ClientboundLevelParticlesPacket.class,
            ClientboundLevelEventPacket.class,
            ClientboundBlockEventPacket.class,
            ClientboundExplodePacket.class,
            ClientboundAnimatePacket.class,
            ClientboundHurtAnimationPacket.class,
            ClientboundDamageEventPacket.class,
            ClientboundTakeItemEntityPacket.class,
            ClientboundSystemChatPacket.class,
            ClientboundPlayerChatPacket.class,
            ClientboundDisguisedChatPacket.class,
            ClientboundSetActionBarTextPacket.class,
            ClientboundSetTitleTextPacket.class,
            ClientboundSetSubtitleTextPacket.class);

    /** What is known about each packet id. */
    private static final class Ids {
        static final int DROP = 1, EFFECT = 2;
        static final byte[] FLAGS;
        static final int CHUNK, LOGIN, TAGS, CUSTOM_PAYLOAD;

        static {
            var map = ConnectionProtocol.PLAY.getPacketsByIds(PacketFlow.CLIENTBOUND);
            int size = 0;
            for (int id : map.keySet()) size = Math.max(size, id + 1);
            byte[] flags = new byte[size];
            int chunk = -1, login = -1, tags = -1, custom = -1;
            for (var e : map.int2ObjectEntrySet()) {
                Class<?> type = e.getValue();
                int id = e.getIntKey();
                if (DROP_AT_RECORD.contains(type) || DROP_AT_PLAYBACK.contains(type)) flags[id] |= DROP;
                if (TRANSIENT_EFFECT.contains(type)) flags[id] |= EFFECT;
                if (type == ClientboundLevelChunkWithLightPacket.class) chunk = id;
                if (type == ClientboundLoginPacket.class) login = id;
                if (type == ClientboundUpdateTagsPacket.class) tags = id;
                if (type == ClientboundCustomPayloadPacket.class) custom = id;
            }
            FLAGS = flags;
            CHUNK = chunk;
            LOGIN = login;
            TAGS = tags;
            CUSTOM_PAYLOAD = custom;
        }
    }

    private PacketPolicy() {}

    /** Never written to a replay. */
    public static boolean dropAtRecord(Packet<?> p) {
        return DROP_AT_RECORD.contains(p.getClass());
    }

    /** Never applied to the viewer: these describe the recording player's own session. */
    public static boolean dropAtPlayback(int id) {
        return flag(id, Ids.DROP);
    }

    /** Sound, particles and one-shot effects, which must stay silent while seeking through time. */
    public static boolean isTransientEffect(int id) {
        return flag(id, Ids.EFFECT);
    }

    private static boolean flag(int id, int flag) {
        return id >= 0 && id < Ids.FLAGS.length && (Ids.FLAGS[id] & flag) != 0;
    }

    public static boolean isLogin(int id) {
        return id == Ids.LOGIN;
    }

    public static boolean isTags(int id) {
        return id == Ids.TAGS;
    }

    public static boolean isCustomPayload(int id) {
        return id == Ids.CUSTOM_PAYLOAD;
    }

    /** The id at the start of an encoded packet. */
    public static int readId(byte[] data, int offset) {
        int value = 0;
        for (int shift = 0; shift < 28; shift += 7) {
            byte b = data[offset++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        return value;
    }

    /** @return where the eight bytes of chunk x and z start, if this encoded packet is a chunk, or else -1. */
    public static int chunkCoordinatesOffset(byte[] packet) {
        return chunkCoordinatesOffset(packet, 0, packet.length);
    }

    public static int chunkCoordinatesOffset(byte[] data, int offset, int length) {
        if (length < 10 || readId(data, offset) != Ids.CHUNK) return -1;
        int at = offset;
        while ((data[at] & 0x80) != 0) at++;
        return at + 1;
    }
}
