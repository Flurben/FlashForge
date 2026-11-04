package com.flurben.flashbackreforged.client.ui;

import com.flurben.flashbackreforged.editor.Visuals;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.flurben.flashbackreforged.mixin.accessor.GameRendererInvoker;
import imgui.ImGui;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The entity under the mouse, the popup it opens, and the actions shared with the Entities tab. */
public final class EntityPopup {
    private static final String ID = "entity_popup";
    private static final double RANGE = 256;

    private UUID selected;
    private boolean openNextFrame;

    public void open(Entity entity) {
        this.selected = entity.getUUID();
        this.openNextFrame = true;
    }

    public void close() {
        this.selected = null;
    }

    /** Null when nothing is selected. Outlined in the world while the popup is open. */
    public UUID selected() {
        return this.selected;
    }

    void draw(ReplayPlayer replay) {
        if (this.openNextFrame) {
            this.openNextFrame = false;
            ImGui.openPopup(ID);
        }
        if (!ImGui.beginPopup(ID)) {
            this.selected = null;
            return;
        }
        if (this.selected == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        Entity entity = find();
        if (entity == null) {
            ImGui.textDisabled("This entity isn't loaded at this point in the replay.");
        } else {
            controls(replay, entity);
        }
        ImGui.endPopup();
    }

    private Entity find() {
        Minecraft mc = Minecraft.getInstance();
        if (this.selected == null || mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(this.selected)) return e;
        }
        return null;
    }

    static void controls(ReplayPlayer replay, Entity entity) {
        Visuals v = replay.editor().visuals();
        String id = entity.getUUID().toString();
        ImGui.text(entity.getName().getString());
        ImGui.textDisabled(EntityType.getKey(entity.getType()).toString());
        if (ImGui.smallButton("Look At")) lookAt(replay, entity);
        ImGui.sameLine();
        if (ImGui.smallButton("Spectate")) replay.spectate(entity);
        ImGui.sameLine();
        if (ImGui.smallButton("TP")) replay.camera().teleport(entity.getX(), entity.getY() + 1.6, entity.getZ() - 3, 0, 15);
        ImGui.sameLine();
        if (ImGui.smallButton("Copy UUID")) ImGui.setClipboardText(id);
        if (id.equals(v.audioSource)) {
            if (ImGui.smallButton("Stop Hearing From Here")) v.audioSource = null;
        } else if (ImGui.smallButton("Hear Audio From Here")) {
            v.audioSource = id;
        }
        check("Hide During Export", v.hiddenEntities, id);
        check("Glow", v.glowingEntities, id);
        check("Never Glow", v.noGlowEntities, id);
        check("Hide Name Tag", v.hiddenNametags, id);
        check("Mute", v.mutedEntities, id);
        if (entity instanceof Player) check("Hide Cape", v.hiddenCapes, id);
    }

    private static void check(String label, Set<String> set, String id) {
        if (ImGui.checkbox(label, set.contains(id)) && !set.remove(id)) set.add(id);
    }

    static void lookAt(ReplayPlayer replay, Entity entity) {
        double dx = entity.getX() - replay.camera().x();
        double dy = entity.getEyeY() - replay.camera().y() - Minecraft.getInstance().player.getEyeHeight();
        double dz = entity.getZ() - replay.camera().z();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        replay.camera().teleport(replay.camera().x(), replay.camera().y(), replay.camera().z(), yaw, pitch);
    }

    /**
     * @param ndcX -1 at the left of the game image, 1 at the right
     * @param ndcY -1 at the bottom, 1 at the top
     * @return the nearest entity along that line of sight, unless a block is in the way
     */
    public static Entity pick(ReplayPlayer replay, double ndcX, double ndcY, double aspect) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        double fov = ((GameRendererInvoker) mc.gameRenderer).flashbackreforged$getFov(camera, mc.getFrameTime(), true);
        double tan = Math.tan(Math.toRadians(fov) / 2);
        double sx = ndcX * tan * aspect;
        double sy = ndcY * tan;

        // Roll turns the image, so undo it to find where this point on screen really looks.
        double roll = Math.toRadians(replay.camera().roll());
        double rx = sx * Math.cos(roll) + sy * Math.sin(roll);
        double ry = -sx * Math.sin(roll) + sy * Math.cos(roll);

        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        Vec3 direction = new Vec3(look.x - left.x * rx + up.x * ry, look.y - left.y * rx + up.y * ry, look.z - left.z * rx + up.z * ry).normalize();
        Vec3 to = from.add(direction.scale(RANGE));

        BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        double limit = block.getType() == HitResult.Type.MISS ? RANGE * RANGE : block.getLocation().distanceToSqr(from);

        Entity best = null;
        double bestDistance = limit;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity == replay.spectated()) continue;
            AABB box = entity.getBoundingBox().inflate(Math.max(0.1, entity.getPickRadius()));
            if (box.contains(from)) continue;
            Optional<Vec3> hit = box.clip(from, to);
            if (hit.isEmpty()) continue;
            double distance = hit.get().distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }
}
