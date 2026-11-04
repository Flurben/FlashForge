package com.flurben.flashforge.editor;

import java.util.Map;
import java.util.TreeMap;

/** An ordered set of keyframes of one type, evaluated at fractional ticks. */
public final class KeyframeTrack {
    public TrackType type;
    public String name;
    public boolean enabled = true;
    public TreeMap<Integer, Keyframe> keyframes = new TreeMap<>();

    public KeyframeTrack() {}

    public KeyframeTrack(TrackType type) {
        this.type = type;
        this.name = type.label;
    }

    /**
     * @return the track's values at this time, or null when there is nothing to apply. A track holds its first value
     * before its first keyframe only if it has a single keyframe; otherwise time outside the range leaves it inactive
     * except that the final value is held, matching how a shot settles on its last frame.
     */
    public double[] evaluate(double tick) {
        if (!this.enabled || this.keyframes.isEmpty()) return null;
        Map.Entry<Integer, Keyframe> before = this.keyframes.floorEntry((int) Math.floor(tick));
        Map.Entry<Integer, Keyframe> after = this.keyframes.higherEntry((int) Math.floor(tick));
        if (before == null) return this.keyframes.size() == 1 ? after.getValue().values.clone() : null;
        if (after == null) return before.getValue().values.clone();

        double span = after.getKey() - before.getKey();
        double raw = (tick - before.getKey()) / span;
        Keyframe from = before.getValue();
        Keyframe to = after.getValue();
        double[] out = new double[from.values.length];

        if (from.interpolation == Interpolation.CATMULL_ROM) {
            Map.Entry<Integer, Keyframe> previous = this.keyframes.lowerEntry(before.getKey());
            Map.Entry<Integer, Keyframe> next = this.keyframes.higherEntry(after.getKey());
            for (int i = 0; i < out.length; i++) {
                double p1 = from.values[i];
                double p2 = nearest(i, p1, to.values[i]);
                // Missing neighbours are mirrored, so the curve enters and leaves the path without a kink.
                double p0 = previous != null ? nearest(i, p1, previous.getValue().values[i]) : 2 * p1 - p2;
                double p3 = next != null ? nearest(i, p2, next.getValue().values[i]) : 2 * p2 - p1;
                out[i] = Interpolation.catmullRom(p0, p1, p2, p3, raw);
            }
            return out;
        }

        double t = from.interpolation.ease(raw);
        for (int i = 0; i < out.length; i++) {
            double a = from.values[i];
            double b = nearest(i, a, to.values[i]);
            out[i] = a + (b - a) * t;
        }
        return out;
    }

    /** Angles take the short way round: 350 to 10 passes through 0, not through 180. */
    private double nearest(int slot, double reference, double value) {
        if (!this.type.angular[slot]) return value;
        double delta = (value - reference) % 360;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        return reference + delta;
    }

    public KeyframeTrack copy() {
        KeyframeTrack t = new KeyframeTrack(this.type);
        t.name = this.name;
        t.enabled = this.enabled;
        this.keyframes.forEach((tick, k) -> t.keyframes.put(tick, k.copy()));
        return t;
    }
}
