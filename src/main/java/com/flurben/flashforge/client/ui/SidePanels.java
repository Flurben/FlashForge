package com.flurben.flashforge.client.ui;

import com.flurben.flashforge.editor.EditorState;
import com.flurben.flashforge.editor.Scene;
import com.flurben.flashforge.editor.Visuals;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.playback.ViewerCamera;
import com.flurben.flashforge.replay.ReplayMeta;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The right-hand tabs: visuals, players, entities, camera, scenes and markers. */
public final class SidePanels {
    private final ImString filter = new ImString(64);
    private final ImString newSceneName = new ImString(64);
    private final ImString markerText = new ImString(128);
    private final ImString skinName = new ImString(32);
    private final ImString playerFilter = new ImString(32);

    float preferredWidth() {
        return 340f;
    }

    void draw(ReplayPlayer replay, float x, float y, float width, float height) {
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        int flags = ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoTitleBar;
        if (ImGui.begin("Panels", flags)) {
            if (ImGui.beginTabBar("tabs", ImGuiTabBarFlags.FittingPolicyScroll)) {
                if (ImGui.beginTabItem("Visuals")) {
                    drawVisuals(replay);
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Players")) {
                    drawPlayers(replay);
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Entities")) {
                    drawEntities(replay);
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Camera")) {
                    drawCamera(replay);
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Scenes")) {
                    drawScenes(replay);
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Markers")) {
                    drawMarkers(replay);
                    ImGui.endTabItem();
                }
                ImGui.endTabBar();
            }
        }
        ImGui.end();
    }

    private static boolean toggle(String label, boolean value) {
        boolean out = value;
        if (ImGui.checkbox(label, value)) out = !value;
        return out;
    }

    private void drawVisuals(ReplayPlayer replay) {
        Visuals v = replay.editor().visuals();
        if (ImGui.collapsingHeader("Interface")) {
            v.showHud = toggle("Show HUD", v.showHud);
            ImGui.beginDisabled(!v.showHud);
            v.showHotbar = toggle("Hotbar", v.showHotbar);
            v.showActionBar = toggle("Action Bar", v.showActionBar);
            v.showChat = toggle("Chat", v.showChat);
            v.showBossBar = toggle("Boss Bars", v.showBossBar);
            v.showTitles = toggle("Titles", v.showTitles);
            v.showScoreboard = toggle("Scoreboard", v.showScoreboard);
            v.showTabList = toggle("Tab List", v.showTabList);
            ImGui.endDisabled();
            v.showHand = toggle("First-Person Hand", v.showHand);
        }
        if (ImGui.collapsingHeader("World", ImGuiTreeNodeFlags.DefaultOpen)) {
            v.renderBlocks = toggle("Blocks", v.renderBlocks);
            v.renderEntities = toggle("Entities", v.renderEntities);
            v.renderPlayers = toggle("Players", v.renderPlayers);
            v.renderParticles = toggle("Particles", v.renderParticles);
            v.renderNametags = toggle("Name Tags", v.renderNametags);
            v.renderSky = toggle("Sky", v.renderSky);
            v.renderClouds = toggle("Clouds", v.renderClouds);
            v.renderWeather = toggle("Weather", v.renderWeather);
            v.renderBeaconBeams = toggle("Beacon Beams", v.renderBeaconBeams);
            v.renderWorldBorder = toggle("World Border", v.renderWorldBorder);
            v.renderBlockOutline = toggle("Block Outline", v.renderBlockOutline);
        }
        if (ImGui.collapsingHeader("Overrides", ImGuiTreeNodeFlags.DefaultOpen)) {
            v.overrideFov = toggle("Override FOV", v.overrideFov);
            if (v.overrideFov) {
                float[] fov = {v.fov};
                if (ImGui.sliderFloat("FOV", fov, 1f, 170f, "%.1f")) v.fov = fov[0];
            }
            v.overrideTime = toggle("Override Time", v.overrideTime);
            if (v.overrideTime) {
                int[] time = {v.time};
                if (ImGui.sliderInt("Time", time, 0, 24000)) v.time = time[0];
            }
            String[] weather = {"As Recorded", "Clear", "Rain", "Thunder"};
            if (ImGui.beginCombo("Weather", weather[v.weather])) {
                for (int i = 0; i < weather.length; i++) {
                    if (ImGui.selectable(weather[i], i == v.weather)) v.weather = i;
                }
                ImGui.endCombo();
            }
            v.fullBright = toggle("Full Bright", v.fullBright);
            v.disableFog = toggle("Disable Fog", v.disableFog);
            v.overrideFog = toggle("Override Fog Range", v.overrideFog);
            if (v.overrideFog) {
                float[] start = {v.fogStart};
                float[] end = {v.fogEnd};
                if (ImGui.sliderFloat("Fog Start", start, 0f, 2048f)) v.fogStart = start[0];
                if (ImGui.sliderFloat("Fog End", end, 0f, 2048f)) v.fogEnd = end[0];
            }
            v.overrideFogColor = toggle("Override Fog Color", v.overrideFogColor);
            if (v.overrideFogColor) ImGui.colorEdit3("Fog Color", v.fogColor);
            v.overrideSkyColor = toggle("Solid Sky Color", v.overrideSkyColor);
            if (v.overrideSkyColor) ImGui.colorEdit3("Sky Color", v.skyColor);
        }
        if (ImGui.collapsingHeader("Guides")) {
            v.ruleOfThirds = toggle("Rule of Thirds", v.ruleOfThirds);
            v.centerGuide = toggle("Center Mark", v.centerGuide);
            v.showCameraPath = toggle("Camera Path", v.showCameraPath);
            String[] names = {"Off", "16:9", "9:16", "1:1", "4:3", "2.39:1"};
            float[] values = {0f, 16f / 9f, 9f / 16f, 1f, 4f / 3f, 2.39f};
            int current = 0;
            for (int i = 0; i < values.length; i++) {
                if (Math.abs(values[i] - v.aspectGuide) < 1e-3) current = i;
            }
            if (ImGui.beginCombo("Aspect Guide", names[current])) {
                for (int i = 0; i < names.length; i++) {
                    if (ImGui.selectable(names[i], i == current)) v.aspectGuide = values[i];
                }
                ImGui.endCombo();
            }
        }
    }

    private void drawPlayers(ReplayPlayer replay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.level == null) return;
        Visuals v = replay.editor().visuals();
        List<PlayerInfo> players = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        players.sort(Comparator.comparing(p -> p.getProfile().getName(), String.CASE_INSENSITIVE_ORDER));
        if (ImGui.button("Stop Spectating")) replay.spectate(null);
        ImGui.sameLine();
        if (ImGui.button("Hide All")) {
            for (PlayerInfo info : players) v.hiddenPlayers.add(info.getProfile().getId().toString());
        }
        ImGui.sameLine();
        if (ImGui.button("Show All")) v.hiddenPlayers.clear();
        v.hideSpectators = toggle("Hide All Spectators", v.hideSpectators);
        ImGui.inputTextWithHint("##playerFilter", "Search", this.playerFilter);
        String playerNeedle = this.playerFilter.get().toLowerCase();
        ImGui.separator();
        for (PlayerInfo info : players) {
            String id = info.getProfile().getId().toString();
            if ("Viewer".equals(info.getProfile().getName()) || !info.getProfile().getName().toLowerCase().contains(playerNeedle)) continue;
            ImGui.pushID(id);
            Player player = mc.level.getPlayerByUUID(info.getProfile().getId());
            ImGui.text(info.getProfile().getName() + (player == null ? " (out of range)" : ""));
            ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - 145);
            boolean hidden = v.hiddenPlayers.contains(id);
            if (ImGui.smallButton(hidden ? "Show" : "Hide")) toggle(v.hiddenPlayers, id);
            ImGui.sameLine();
            ImGui.beginDisabled(player == null);
            if (ImGui.smallButton("Spectate") && player != null) replay.spectate(player);
            ImGui.sameLine();
            if (ImGui.smallButton("TP") && player != null) {
                replay.camera().teleport(player.getX(), player.getY() + 1.6, player.getZ() - 3, 0, 15);
            }
            ImGui.endDisabled();
            String skin = v.skinOverrides.get(id);
            this.skinName.set(skin == null ? "" : skin);
            if (ImGui.checkbox("Hide Cape", v.hiddenCapes.contains(id))) toggle(v.hiddenCapes, id);
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 60);
            if (ImGui.inputTextWithHint("Skin", "Show another player's skin", this.skinName, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                if (this.skinName.get().isBlank()) v.skinOverrides.remove(id);
                else v.skinOverrides.put(id, this.skinName.get().trim());
            }
            ImGui.popID();
        }
    }

    private void drawEntities(ReplayPlayer replay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Visuals v = replay.editor().visuals();
        ImGui.inputTextWithHint("##filter", "Filter by name", this.filter);
        String needle = this.filter.get().toLowerCase();

        Map<String, Integer> counts = new TreeMap<>();
        List<Entity> entities = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            entities.add(e);
            counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), 1, Integer::sum);
        }

        if (ImGui.collapsingHeader("Render Filter (by type)", ImGuiTreeNodeFlags.DefaultOpen)) {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (!entry.getKey().contains(needle)) continue;
                boolean shown = !v.hiddenEntityTypes.contains(entry.getKey());
                if (ImGui.checkbox(entry.getKey() + " (" + entry.getValue() + ")", shown)) toggle(v.hiddenEntityTypes, entry.getKey());
            }
        }
        if (ImGui.collapsingHeader("Nearby Entities")) {
            var camera = mc.player;
            entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(camera)));
            int shown = 0;
            for (Entity e : entities) {
                String label = e.getName().getString();
                if (!label.toLowerCase().contains(needle)) continue;
                if (++shown > 60) break;
                ImGui.pushID(e.getId());
                if (ImGui.treeNode(label + " (" + Math.round(Math.sqrt(e.distanceToSqr(camera))) + " m)")) {
                    String id = e.getUUID().toString();
                    if (ImGui.smallButton("Spectate")) replay.spectate(e);
                    ImGui.sameLine();
                    if (ImGui.smallButton("TP")) replay.camera().teleport(e.getX(), e.getY() + 1.6, e.getZ() - 3, 0, 15);
                    if (ImGui.checkbox("Glow", v.glowingEntities.contains(id))) toggle(v.glowingEntities, id);
                    if (ImGui.checkbox("Never Glow", v.noGlowEntities.contains(id))) toggle(v.noGlowEntities, id);
                    if (ImGui.checkbox("Hide Name Tag", v.hiddenNametags.contains(id))) toggle(v.hiddenNametags, id);
                    if (ImGui.checkbox("Mute", v.mutedEntities.contains(id))) toggle(v.mutedEntities, id);
                    if (ImGui.smallButton("Copy UUID")) ImGui.setClipboardText(id);
                    ImGui.treePop();
                }
                ImGui.popID();
            }
        }
    }

    private static void toggle(Set<String> set, String value) {
        if (!set.remove(value)) set.add(value);
    }

    private void drawCamera(ReplayPlayer replay) {
        ViewerCamera camera = replay.camera();
        Minecraft mc = Minecraft.getInstance();
        ImGui.textWrapped("Hold right mouse over the game to look around and move with your movement keys.");
        ImGui.separator();
        float[] speed = {camera.speedMultiplier()};
        if (ImGui.sliderFloat("Move Speed", speed, 0.05f, 32f, "%.2f")) camera.setSpeedMultiplier(speed[0]);
        float[] roll = {camera.roll()};
        if (ImGui.sliderFloat("Roll", roll, -180f, 180f, "%.1f")) camera.setRoll(roll[0]);
        if (ImGui.button("Reset Roll")) camera.setRoll(0);
        float[] momentum = {camera.momentum};
        if (ImGui.sliderFloat("Momentum", momentum, 1f, 40f, "%.1f")) camera.momentum = momentum[0];
        camera.horizontal = toggle("Move Along Horizon", camera.horizontal);
        camera.lockX = toggle("Lock X", camera.lockX);
        ImGui.sameLine();
        camera.lockY = toggle("Lock Y", camera.lockY);
        ImGui.sameLine();
        camera.lockZ = toggle("Lock Z", camera.lockZ);
        camera.lockYaw = toggle("Lock Yaw", camera.lockYaw);
        ImGui.sameLine();
        camera.lockPitch = toggle("Lock Pitch", camera.lockPitch);
        if (mc.player != null) {
            ImGui.separator();
            ImGui.text(String.format("Position  %.2f, %.2f, %.2f", camera.x(), camera.y(), camera.z()));
            ImGui.text(String.format("Rotation  %.1f, %.1f", mc.player.getYRot(), mc.player.getXRot()));
            ImGui.text("Dimension " + mc.level.dimension().location());
        }
        Entity spectated = replay.spectated();
        if (spectated != null) ImGui.text("Spectating " + spectated.getName().getString());
        ImGui.separator();
        ImGui.text("Replay");
        ReplayMeta meta = replay.meta();
        ImGui.textDisabled("Recorded by " + meta.recordedBy + " in " + meta.world);
        ImGui.textDisabled("Minecraft " + meta.minecraftVersion + ", " + meta.mods.size() + " mods");
        if (replay.packetFailures() > 0) {
            ImGui.textColored(1f, 0.7f, 0.3f, 1f, replay.packetFailures() + " packets were skipped (see log)");
            if (ImGui.isItemHovered()) ImGui.setTooltip("Something in the replay could not be applied, usually a modded packet. Playback continues without it.");
        }
    }

    private void drawScenes(ReplayPlayer replay) {
        EditorState editor = replay.editor();
        List<Scene> scenes = editor.scenes();
        for (int i = 0; i < scenes.size(); i++) {
            if (ImGui.selectable(scenes.get(i).name + "##" + i, i == editor.currentSceneIndex())) editor.selectScene(i);
        }
        ImGui.separator();
        ImGui.inputTextWithHint("##name", "New scene name", this.newSceneName);
        if (ImGui.button("New Scene")) {
            editor.checkpoint();
            String name = this.newSceneName.get().isBlank() ? "Scene " + (scenes.size() + 1) : this.newSceneName.get();
            scenes.add(new Scene(name));
            editor.selectScene(scenes.size() - 1);
        }
        ImGui.sameLine();
        if (ImGui.button("Duplicate")) {
            editor.checkpoint();
            Scene copy = editor.scene().copy();
            copy.name = editor.scene().name + " Copy";
            scenes.add(copy);
            editor.selectScene(scenes.size() - 1);
        }
        ImGui.sameLine();
        ImGui.beginDisabled(scenes.size() <= 1);
        if (ImGui.button("Delete")) {
            editor.checkpoint();
            scenes.remove(editor.currentSceneIndex());
            editor.selectScene(editor.currentSceneIndex());
        }
        ImGui.endDisabled();
    }

    private void drawMarkers(ReplayPlayer replay) {
        ReplayMeta meta = replay.meta();
        ImGui.inputTextWithHint("##marker", "Description", this.markerText);
        if (ImGui.button("Add Marker Here")) {
            ReplayMeta.Marker marker = new ReplayMeta.Marker();
            marker.description = this.markerText.get().isBlank() ? null : this.markerText.get();
            meta.markers.put(replay.tick(), marker);
            saveMeta(replay);
        }
        ImGui.separator();
        Integer remove = null;
        for (Map.Entry<Integer, ReplayMeta.Marker> entry : meta.markers.entrySet()) {
            ImGui.pushID(entry.getKey());
            String text = entry.getValue().description == null ? "Marker" : entry.getValue().description;
            if (ImGui.selectable(TimelinePanel.formatTime(entry.getKey()) + "  " + text, false, 0, ImGui.getContentRegionAvailX() - 30, 0)) {
                replay.seek(entry.getKey());
            }
            ImGui.sameLine();
            if (ImGui.smallButton("X")) remove = entry.getKey();
            ImGui.popID();
        }
        if (remove != null) {
            meta.markers.remove(remove);
            saveMeta(replay);
        }
    }

    private static void saveMeta(ReplayPlayer replay) {
        try {
            replay.meta().write(replay.dir());
        } catch (java.io.IOException e) {
            com.flurben.flashforge.FlashForge.LOGGER.error("Could not save markers", e);
        }
    }
}
