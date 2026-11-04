package com.flurben.flashforge.editor;

/**
 * Moves a copied camera move to a new place and heading: the keyframes keep their position relative to where the camera was
 * when they were copied, as if the whole path were picked up and set down facing a new way.
 */
public final class RelativeOffset {
    public double oldX, oldY, oldZ, newX, newY, newZ;
    public double oldYaw, newYaw, oldPitch, newPitch;

    /** Changes the keyframe in place. Only tracks that hold a position are affected. */
    public void apply(TrackType type, Keyframe keyframe) {
        if (type != TrackType.CAMERA && type != TrackType.ORBIT) return;
        double[] v = keyframe.values;
        double[] moved = move(v[0], v[1], v[2]);
        v[0] = moved[0];
        v[1] = moved[1];
        v[2] = moved[2];
        int yawSlot = type == TrackType.CAMERA ? 3 : 4;
        v[yawSlot] += this.newYaw - this.oldYaw;
        v[yawSlot + 1] += this.newPitch - this.oldPitch;
    }

    private double[] move(double x, double y, double z) {
        double px = x - this.oldX, py = y - this.oldY, pz = z - this.oldZ;

        // Into the frame where the old heading looks along +Z.
        double a = Math.toRadians(-this.oldYaw);
        double fx = px * Math.cos(a) - pz * Math.sin(a);
        double fz = px * Math.sin(a) + pz * Math.cos(a);

        // Pitching down tips +Z toward -Y.
        double b = Math.toRadians(this.newPitch - this.oldPitch);
        double gy = py * Math.cos(b) - fz * Math.sin(b);
        double gz = py * Math.sin(b) + fz * Math.cos(b);

        // Out to the new heading.
        double c = Math.toRadians(this.newYaw);
        double hx = fx * Math.cos(c) - gz * Math.sin(c);
        double hz = fx * Math.sin(c) + gz * Math.cos(c);
        return new double[]{hx + this.newX, gy + this.newY, hz + this.newZ};
    }
}
