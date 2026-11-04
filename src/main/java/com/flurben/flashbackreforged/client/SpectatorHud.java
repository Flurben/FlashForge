package com.flurben.flashbackreforged.client;

import com.flurben.flashbackreforged.editor.Visuals;
import com.flurben.flashbackreforged.mixin.accessor.ItemInHandRendererAccessor;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.replay.LocalHud;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes first-person spectating look like the original gameplay. The viewer is always a spectator, so while frames
 * render and the camera is on the recorded player, the HUD and hand code are told the recorded game mode and read the
 * recorded hotbar and vitals. Game logic never sees this, because it only holds during rendering.
 */
public final class SpectatorHud {
    private static GameType renderMode;
    private static RemotePlayer target;
    private static final Map<String, ResourceLocation> SKINS = new HashMap<>();

    private SpectatorHud() {}

    /** Non-null only while rendering from the recorded player's point of view. */
    public static GameType renderMode() {
        return renderMode;
    }

    public static void endFrame() {
        renderMode = null;
    }

    public static void beginFrame(ReplayPlayer replay) {
        renderMode = null;
        target = null;
        Minecraft mc = Minecraft.getInstance();
        if (!(replay.spectated() instanceof RemotePlayer player) || player.getId() != replay.recordedPlayerId() || mc.getConnection() == null) return;
        PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
        GameType mode = info == null ? GameType.SURVIVAL : info.getGameMode();
        if (mode == GameType.SPECTATOR) return;

        LocalPlayer viewer = mc.player;
        LocalHud hud = replay.hud();
        target = player;
        renderMode = mode;
        if (hud != null) {
            apply(hud, player.getInventory());
            apply(hud, viewer.getInventory());
            player.getFoodData().setFoodLevel(hud.food());
            player.getFoodData().setSaturation(hud.saturation());
            viewer.experienceLevel = player.experienceLevel = hud.xpLevel();
            viewer.experienceProgress = player.experienceProgress = hud.xpProgress();
            player.setAirSupply(hud.air());
        }
        // Spectators are flagged invisible, which would also hide the arm.
        viewer.setInvisible(player.isInvisible());
        viewer.swinging = player.swinging;
        viewer.swingTime = player.swingTime;
        viewer.swingingArm = player.swingingArm;
        viewer.attackAnim = player.attackAnim;
        viewer.oAttackAnim = player.oAttackAnim;
        // The hand rises on game ticks, which don't run while paused; it would stay below the screen.
        if (replay.worldStopped()) raiseHand(mc, viewer);
    }

    private static void raiseHand(Minecraft mc, LocalPlayer viewer) {
        ItemInHandRendererAccessor hands = (ItemInHandRendererAccessor) mc.getEntityRenderDispatcher().getItemInHandRenderer();
        hands.flashbackreforged$setMainHandItem(viewer.getMainHandItem());
        hands.flashbackreforged$setOffHandItem(viewer.getOffhandItem());
        hands.flashbackreforged$setMainHandHeight(1f);
        hands.flashbackreforged$setOMainHandHeight(1f);
        hands.flashbackreforged$setOffHandHeight(1f);
        hands.flashbackreforged$setOOffHandHeight(1f);
    }

    private static void apply(LocalHud hud, Inventory inventory) {
        List<ItemStack> hotbar = hud.hotbar();
        inventory.selected = hud.selected();
        for (int i = 0; i < 9 && i < hotbar.size(); i++) inventory.items.set(i, hotbar.get(i));
        if (hotbar.size() > 9) inventory.offhand.set(0, hotbar.get(9));
    }

    /** The skin to draw instead of this player's own, or null. */
    public static ResourceLocation skinFor(AbstractClientPlayer player) {
        ReplayPlayer replay = ReplayPlayer.active();
        if (replay == null) return null;
        Minecraft mc = Minecraft.getInstance();
        // The hand is drawn with the viewer's skin, which should look like whoever it is standing in for.
        if (player == mc.player && target != null && renderMode != null) return target.getSkinTextureLocation();
        Visuals v = replay.editor().visuals();
        if (v.skinOverrides.isEmpty()) return null;
        String name = v.skinOverrides.get(player.getUUID().toString());
        return name == null ? null : skinNamed(name);
    }

    /** Looks up a skin by username in the background and returns null until it has arrived. */
    private static ResourceLocation skinNamed(String name) {
        if (SKINS.containsKey(name)) return SKINS.get(name);
        SKINS.put(name, null);
        SkullBlockEntity.updateGameprofile(new GameProfile(null, name), profile ->
                Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, texture) -> {
                    if (type == MinecraftProfileTexture.Type.SKIN) SKINS.put(name, location);
                }, true));
        return null;
    }
}
