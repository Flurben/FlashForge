package com.flurben.flashforge.editor;

import java.util.ArrayList;
import java.util.List;

/** A named set of tracks and an export range. Replays can hold several, like cuts of the same recording. */
public final class Scene {
    public String name = "Scene 1";
    public List<KeyframeTrack> tracks = new ArrayList<>();
    public int inTick = -1;
    public int outTick = -1;

    public Scene() {}

    public Scene(String name) {
        this.name = name;
    }

    public KeyframeTrack firstTrack(TrackType type) {
        for (KeyframeTrack t : this.tracks) {
            if (t.type == type) return t;
        }
        return null;
    }

    public KeyframeTrack trackForAdding(TrackType type) {
        KeyframeTrack t = firstTrack(type);
        if (t == null) {
            t = new KeyframeTrack(type);
            this.tracks.add(t);
        }
        return t;
    }

    public double[] evaluate(TrackType type, double tick) {
        double[] result = null;
        for (KeyframeTrack t : this.tracks) {
            if (t.type != type) continue;
            double[] v = t.evaluate(tick);
            if (v != null) result = v;
        }
        return result;
    }

    /**
     * Playback speed at a tick from Speed and Timelapse tracks. Timelapse pairs say how many video seconds a span of
     * timeline should take, so its speed follows from the ticks and seconds between its two keyframes.
     */
    public double speedAt(double tick) {
        for (KeyframeTrack t : this.tracks) {
            if (t.type != TrackType.TIMELAPSE || !t.enabled) continue;
            var from = t.keyframes.floorEntry((int) Math.floor(tick));
            var to = t.keyframes.higherEntry((int) Math.floor(tick));
            if (from != null && to != null) {
                double seconds = to.getValue().values[0] - from.getValue().values[0];
                if (seconds > 1e-3) return (to.getKey() - from.getKey()) / (seconds * 20.0);
            }
        }
        double[] speed = evaluate(TrackType.SPEED, tick);
        return speed == null ? 1.0 : Math.max(0.01, speed[0]);
    }

    public boolean frozenAt(double tick) {
        for (KeyframeTrack t : this.tracks) {
            if (t.type != TrackType.FREEZE || !t.enabled) continue;
            var from = t.keyframes.floorEntry((int) Math.floor(tick));
            if (from != null && from.getValue().values[0] > 0.5) return true;
        }
        return false;
    }

    public Scene copy() {
        Scene s = new Scene(this.name);
        for (KeyframeTrack t : this.tracks) s.tracks.add(t.copy());
        s.inTick = this.inTick;
        s.outTick = this.outTick;
        return s;
    }
}
