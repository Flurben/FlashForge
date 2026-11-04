package com.flurben.flashbackreforged.playback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Free-fly camera for the viewer, moved per rendered frame rather than per tick so it still works while the replay is
 * paused and stays smooth at any tick rate. Roll and FOV are applied through Forge's viewport events.
 */
public final class ViewerCamera {
    public static final float DEFAULT_FOV = -1f;

    private double x, y, z;
    private double vx, vy, vz;
    private float roll;
    private float fov = DEFAULT_FOV;
    private float speedMultiplier = 1f;
    private long lastFrame;
    private float heldYaw, heldPitch;
    private boolean inputEnabled = true;
    /** How quickly movement starts and stops: higher is snappier, lower drifts. */
    public float momentum = 12f;
    /** Moves along the horizon even when looking up or down. */
    public boolean horizontal = true;
    public boolean lockX, lockY, lockZ, lockYaw, lockPitch;

    public void syncFrom(LocalPlayer player) {
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.vx = this.vy = this.vz = 0;
    }

    public float roll() {
        return this.roll;
    }

    public void setRoll(float roll) {
        this.roll = roll;
    }

    public float fov() {
        return this.fov;
    }

    public void setFov(float fov) {
        this.fov = fov;
    }

    public float speedMultiplier() {
        return this.speedMultiplier;
    }

    public void setSpeedMultiplier(float speedMultiplier) {
        this.speedMultiplier = Math.max(0.05f, Math.min(speedMultiplier, 32f));
    }

    public void setInputEnabled(boolean enabled) {
        this.inputEnabled = enabled;
    }

    public void teleport(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = this.vy = this.vz = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yaw;
            player.xRotO = pitch;
            apply(player);
        }
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    /** Called once per rendered frame. */
    public void frame() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        long now = System.nanoTime();
        double dt = this.lastFrame == 0 ? 0 : Math.min((now - this.lastFrame) / 1e9, 0.1);
        this.lastFrame = now;

        if (this.inputEnabled && mc.getCameraEntity() == player) {
            double forward = axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
            double strafe = axis(mc.options.keyLeft.isDown(), mc.options.keyRight.isDown());
            double up = axis(mc.options.keyJump.isDown(), mc.options.keyShift.isDown());

            Vec3 look = Vec3.directionFromRotation(this.horizontal ? 0 : player.getXRot(), player.getYRot());
            Vec3 flat = Vec3.directionFromRotation(0, player.getYRot());
            Vec3 side = new Vec3(flat.z, 0, -flat.x);
            double max = 10.0 * this.speedMultiplier * (mc.options.keySprint.isDown() ? 3.0 : 1.0);
            double tx = (look.x * forward + side.x * strafe) * max;
            double ty = up * max + look.y * forward * max;
            double tz = (look.z * forward + side.z * strafe) * max;

            // Exponential smoothing keeps movement framerate-independent and avoids abrupt stops on video.
            double blend = 1 - Math.exp(-this.momentum * dt);
            this.vx += (tx - this.vx) * blend;
            this.vy += (ty - this.vy) * blend;
            this.vz += (tz - this.vz) * blend;
            if (!this.lockX) this.x += this.vx * dt;
            if (!this.lockY) this.y += this.vy * dt;
            if (!this.lockZ) this.z += this.vz * dt;
            if (this.lockYaw) player.setYRot(this.heldYaw);
            else this.heldYaw = player.getYRot();
            if (this.lockPitch) player.setXRot(this.heldPitch);
            else this.heldPitch = player.getXRot();
        }
        apply(player);
    }

    private static double axis(boolean positive, boolean negative) {
        return (positive ? 1 : 0) - (negative ? 1 : 0);
    }

    /** With previous position equal to current, the render's partial-tick interpolation can't introduce jitter. */
    private void apply(LocalPlayer player) {
        player.setPosRaw(this.x, this.y, this.z);
        player.xo = player.xOld = this.x;
        player.yo = player.yOld = this.y;
        player.zo = player.zOld = this.z;
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
        player.yBob = player.yBobO = player.getYRot();
        player.xBob = player.xBobO = player.getXRot();
        player.setDeltaMovement(Vec3.ZERO);
    }
}
