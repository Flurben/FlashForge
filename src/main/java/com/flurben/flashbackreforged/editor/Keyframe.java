package com.flurben.flashbackreforged.editor;

/** A value set at a point on the timeline. Plain fields, since the editor file is written with Gson. */
public final class Keyframe {
    public double[] values;
    public Interpolation interpolation = Interpolation.SMOOTH;
    /** Entity UUID, file path or block state, depending on the track type. */
    public String text;

    public Keyframe() {}

    public Keyframe(double[] values, Interpolation interpolation) {
        this.values = values;
        this.interpolation = interpolation;
    }

    public Keyframe copy() {
        Keyframe k = new Keyframe(this.values.clone(), this.interpolation);
        k.text = this.text;
        return k;
    }
}
