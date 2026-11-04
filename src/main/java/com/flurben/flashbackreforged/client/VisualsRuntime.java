package com.flurben.flashbackreforged.client;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.editor.Visuals;
import com.flurben.flashbackreforged.export.ExportManager;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
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
        return v != null && (!v.renderWeather || v.weather == 4);
    }

    /** Null keeps each biome's own rain or snow. */
    public static net.minecraft.world.level.biome.Biome.Precipitation forcedPrecipitation() {
        Visuals v = visuals();
        if (v == null) return null;
        return switch (v.weather) {
            case 2, 3 -> net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
            case 5 -> net.minecraft.world.level.biome.Biome.Precipitation.SNOW;
            default -> null;
        };
    }

    /** Null keeps the recorded weather. */
    public static Float forcedRainLevel() {
        Visuals v = visuals();
        return v == null || v.weather == 0 ? null : v.weather == 1 ? Float.valueOf(0f) : Float.valueOf(1f);
    }

    public static Float forcedThunderLevel() {
        Visuals v = visuals();
        return v == null || v.weather == 0 ? null : v.weather == 3 ? Float.valueOf(1f) : Float.valueOf(0f);
    }

    private static volatile Vec3 audioPosition;
    private static volatile Vector3f audioLook;
    private static volatile Vector3f audioUp;
    private static volatile Vec3 lastListener;

    /** Runs each frame. The results are read on the sound thread. */
    public static void updateAudioSource() {
        Visuals v = visuals();
        Minecraft mc = Minecraft.getInstance();
        Entity source = null;
        if (v != null && v.audioSource != null && mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().toString().equals(v.audioSource)) {
                    source = e;
                    break;
                }
            }
        }
        if (source == null) {
            audioPosition = null;
            return;
        }
        float partial = mc.getFrameTime();
        Vec3 view = source.getViewVector(partial);
        Vec3 side = view.cross(new Vec3(0, 1, 0));
        // Looking straight up or down leaves no side vector to build "up" from.
        Vec3 up = side.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : side.cross(view).normalize();
        audioLook = new Vector3f((float) view.x, (float) view.y, (float) view.z);
        audioUp = new Vector3f((float) up.x, (float) up.y, (float) up.z);
        audioPosition = source.getEyePosition(partial);
    }

    public static Vec3 listenerPosition(Vec3 camera) {
        Vec3 source = audioPosition;
        Vec3 out = source != null ? source : camera;
        lastListener = out;
        return out;
    }

    public static Vector3f listenerLook(Vector3f camera) {
        return audioPosition != null ? audioLook : camera;
    }

    public static Vector3f listenerUp(Vector3f camera) {
        return audioPosition != null ? audioUp : camera;
    }

    /** Where sounds were last heard from. */
    public static Vec3 lastListener() {
        return lastListener;
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
        if (ExportManager.isExporting() && v.hiddenEntities.contains(entity.getUUID().toString())) return true;
        if (entity instanceof Player player) {
            if (!v.renderPlayers || v.hiddenPlayers.contains(player.getUUID().toString()) || (v.hideSpectators && player.isSpectator())) return true;
        } else if (!v.renderEntities) {
            return true;
        }
        return !v.hiddenEntityTypes.isEmpty() && v.hiddenEntityTypes.contains(typeId(entity));
    }

    /** Null leaves the entity's own glow alone. */
    public static Boolean glowOverride(Entity entity) {
        Visuals v = visuals();
        if (v == null) return null;
        String id = entity.getUUID().toString();
        if (v.noGlowEntities.contains(id)) return false;
        return v.glowingEntities.contains(id) ? Boolean.TRUE : null;
    }

    public static boolean hideCape(Entity entity) {
        Visuals v = visuals();
        return v != null && v.hiddenCapes.contains(entity.getUUID().toString());
    }

    private static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id.toString();
    }

    @Mod.EventBusSubscriber(modid = FlashbackReforged.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        /** The GUI can be on just for the hand, without any of the HUD. */
        @SubscribeEvent
        public static void gui(net.minecraftforge.client.event.RenderGuiEvent.Pre event) {
            Visuals v = visuals();
            if (v != null && !v.showHud) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void overlay(RenderGuiOverlayEvent.Pre event) {
            Visuals v = visuals();
            if (v == null) return;
            ResourceLocation id = event.getOverlay().id();
            // A crosshair only means something when looking through a player's eyes.
            if (id.equals(VanillaGuiOverlay.CROSSHAIR.id()) && ReplayPlayer.active().spectated() == null) event.setCanceled(true);
            if (id.equals(VanillaGuiOverlay.RECORD_OVERLAY.id()) && !v.showActionBar) event.setCanceled(true);
            else if (id.equals(VanillaGuiOverlay.HOTBAR.id()) && !v.showHotbar) event.setCanceled(true);
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
            if (v == null) return;
            float[] color = v.overrideSkyColor ? v.skyColor : v.overrideFogColor ? v.fogColor : null;
            if (color != null) {
                event.setRed(color[0]);
                event.setGreen(color[1]);
                event.setBlue(color[2]);
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
