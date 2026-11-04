package com.flurben.flashforge.client;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.editor.Visuals;
import com.flurben.flashforge.export.ExportManager;
import com.flurben.flashforge.playback.ReplayPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Applies {@link Visuals} to rendering. Every check is cheap and is a no-op outside a replay. */
public final class VisualsRuntime {
    private VisualsRuntime() {}

    private static Visuals visuals() {
        ReplayPlayer replay = ReplayPlayer.active();
        return replay == null ? null : replay.editor().visuals();
    }

    public static boolean hideSky() {
        Visuals v = visuals();
        return v != null && (!v.renderSky || v.overrideSkyColor || ExportManager.transparentBackground());
    }

    public static boolean hideClouds() {
        Visuals v = visuals();
        return v != null && (!v.renderClouds || ExportManager.transparentBackground());
    }

    public static boolean hideWeather() {
        Visuals v = visuals();
        return v != null && !v.renderWeather;
    }

    public static boolean hideWorldBorder() {
        Visuals v = visuals();
        return v != null && !v.renderWorldBorder;
    }

    public static boolean hideBlocks() {
        Visuals v = visuals();
        return v != null && !v.renderBlocks;
    }

    public static boolean hideParticles() {
        Visuals v = visuals();
        return v != null && !v.renderParticles;
    }

    public static boolean hideBeacons() {
        Visuals v = visuals();
        return v != null && !v.renderBeaconBeams;
    }

    public static boolean fullBright() {
        Visuals v = visuals();
        return v != null && v.fullBright;
    }

    public static boolean hideEntity(Entity entity) {
        Visuals v = visuals();
        if (v == null) return false;
        if (entity instanceof LocalPlayer) return false;
        if (entity instanceof Player player) {
            if (!v.renderPlayers || v.hiddenPlayers.contains(player.getUUID().toString())) return true;
        } else if (!v.renderEntities) {
            return true;
        }
        return !v.hiddenEntityTypes.isEmpty() && v.hiddenEntityTypes.contains(typeId(entity));
    }

    public static boolean forceGlow(Entity entity) {
        Visuals v = visuals();
        return v != null && !v.glowingEntities.isEmpty() && v.glowingEntities.contains(entity.getUUID().toString());
    }

    private static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id.toString();
    }

    @Mod.EventBusSubscriber(modid = FlashForge.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void overlay(RenderGuiOverlayEvent.Pre event) {
            Visuals v = visuals();
            if (v == null) return;
            ResourceLocation id = event.getOverlay().id();
            if (id.equals(VanillaGuiOverlay.HOTBAR.id()) && !v.showHotbar) event.setCanceled(true);
            else if (id.equals(VanillaGuiOverlay.CHAT_PANEL.id()) && !v.showChat) event.setCanceled(true);
            else if (id.equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id()) && !v.showBossBar) event.setCanceled(true);
            else if (id.equals(VanillaGuiOverlay.TITLE_TEXT.id()) && !v.showTitles) event.setCanceled(true);
            else if (id.equals(VanillaGuiOverlay.SCOREBOARD.id()) && !v.showScoreboard) event.setCanceled(true);
            else if (id.equals(VanillaGuiOverlay.PLAYER_LIST.id()) && !v.showTabList) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void nameTag(RenderNameTagEvent event) {
            Visuals v = visuals();
            if (v == null) return;
            if (!v.renderNametags || v.hiddenNametags.contains(event.getEntity().getUUID().toString())) event.setResult(Event.Result.DENY);
        }

        @SubscribeEvent
        public static void hand(RenderHandEvent event) {
            Visuals v = visuals();
            if (v != null && !v.showHand) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void outline(RenderHighlightEvent.Block event) {
            Visuals v = visuals();
            if (v != null && !v.renderBlockOutline) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void fog(ViewportEvent.RenderFog event) {
            Visuals v = visuals();
            if (v == null) return;
            if (v.disableFog) {
                event.setNearPlaneDistance(Float.MAX_VALUE / 4);
                event.setFarPlaneDistance(Float.MAX_VALUE / 2);
                event.setCanceled(true);
            } else if (v.overrideFog) {
                event.setNearPlaneDistance(v.fogStart);
                event.setFarPlaneDistance(v.fogEnd);
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void fogColor(ViewportEvent.ComputeFogColor event) {
            Visuals v = visuals();
            if (v != null && v.overrideSkyColor) {
                event.setRed(v.skyColor[0]);
                event.setGreen(v.skyColor[1]);
                event.setBlue(v.skyColor[2]);
            }
        }

        /** Sounds aren't labelled with their source, so a muted entity silences what plays right where it stands. */
        @SubscribeEvent
        public static void sound(PlaySoundEvent event) {
            Visuals v = visuals();
            Minecraft mc = Minecraft.getInstance();
            if (v == null || v.mutedEntities.isEmpty() || mc.level == null || event.getSound() == null) return;
            for (String id : v.mutedEntities) {
                Entity entity = find(mc, id);
                if (entity != null && entity.distanceToSqr(event.getSound().getX(), event.getSound().getY(), event.getSound().getZ()) < 4) {
                    event.setSound(null);
                    return;
                }
            }
        }

        private static Entity find(Minecraft mc, String id) {
            UUID uuid = UUID.fromString(id);
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(uuid)) return e;
            }
            return null;
        }
    }
}
