package com.flurben.flashforge.record;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;

/**
 * Which server packets belong in a replay. Anything about the local player's own session is dropped: a
 * viewer has no menus, and replaying GUI packets against a viewer is a classic source of disconnects
 * (a modded container_set_content larger than the viewer's menu is a fatal protocol error).
 */
public final class PacketPolicy {
    private PacketPolicy() {}

    /** Never written to a replay. */
    public static boolean dropAtRecord(Packet<?> p) {
        return p instanceof ClientboundKeepAlivePacket
                || p instanceof ClientboundPingPacket
                || p instanceof ClientboundDisconnectPacket
                // Huge in modded packs. Viewers can't craft, and leaving it out avoids JEI/EMI reindexing on open.
                || p instanceof ClientboundUpdateRecipesPacket
                || p instanceof ClientboundRecipePacket
                || p instanceof ClientboundUpdateAdvancementsPacket
                || p instanceof ClientboundSelectAdvancementsTabPacket
                || p instanceof ClientboundCommandsPacket
                || p instanceof ClientboundCommandSuggestionsPacket
                || p instanceof ClientboundOpenScreenPacket
                || p instanceof ClientboundOpenBookPacket
                || p instanceof ClientboundOpenSignEditorPacket
                || p instanceof ClientboundHorseScreenOpenPacket
                || p instanceof ClientboundMerchantOffersPacket
                || p instanceof ClientboundContainerClosePacket
                || p instanceof ClientboundContainerSetContentPacket
                || p instanceof ClientboundContainerSetSlotPacket
                || p instanceof ClientboundContainerSetDataPacket
                || p instanceof ClientboundPlaceGhostRecipePacket
                || p instanceof ClientboundCooldownPacket
                || p instanceof ClientboundTagQueryPacket
                || p instanceof ClientboundResourcePackPacket
                || p instanceof ClientboundPlayerLookAtPacket
                || p instanceof ClientboundBlockChangedAckPacket;
    }

    /** Never applied to the viewer: these describe the recording player's own session. */
    public static boolean dropAtPlayback(Packet<?> p) {
        return dropAtRecord(p)
                || p instanceof ClientboundPlayerPositionPacket
                || p instanceof ClientboundPlayerAbilitiesPacket
                || p instanceof ClientboundSetCameraPacket
                || p instanceof ClientboundSetHealthPacket
                || p instanceof ClientboundSetExperiencePacket
                || p instanceof ClientboundPlayerCombatKillPacket
                || p instanceof ClientboundMoveVehiclePacket
                || p instanceof ClientboundSetCarriedItemPacket
                || p instanceof ClientboundSetDefaultSpawnPositionPacket
                || p instanceof ClientboundDeleteChatPacket
                || p instanceof ClientboundServerDataPacket
                || p instanceof ClientboundCustomChatCompletionsPacket;
    }

    /** Sound, particles and one-shot effects, which must stay silent while seeking through time. */
    public static boolean isTransientEffect(Packet<?> p) {
        return p instanceof ClientboundSoundPacket
                || p instanceof ClientboundSoundEntityPacket
                || p instanceof ClientboundStopSoundPacket
                || p instanceof ClientboundLevelParticlesPacket
                || p instanceof ClientboundLevelEventPacket
                || p instanceof ClientboundBlockEventPacket
                || p instanceof ClientboundExplodePacket
                || p instanceof ClientboundAnimatePacket
                || p instanceof ClientboundHurtAnimationPacket
                || p instanceof ClientboundDamageEventPacket
                || p instanceof ClientboundTakeItemEntityPacket
                || p instanceof ClientboundSystemChatPacket
                || p instanceof ClientboundPlayerChatPacket
                || p instanceof ClientboundDisguisedChatPacket
                || p instanceof ClientboundSetActionBarTextPacket
                || p instanceof ClientboundSetTitleTextPacket
                || p instanceof ClientboundSetSubtitleTextPacket;
    }
}
