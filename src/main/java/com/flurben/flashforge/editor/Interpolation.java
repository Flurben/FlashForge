package com.flurben.flashforge.editor;

/** How a value travels from one keyframe to the next. Each maps progress in [0,1] to eased progress. */
public enum Interpolation {
    LINEAR("Linear"),
    SMOOTH("Smooth"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In/Out"),
    /** Holds the earlier value, then jumps. */
    HOLD("Hold"),
    /** Curved path through neighbouring keyframes. Uses four points rather than easing two. */
    CATMULL_ROM("Catmull-Rom"),
    OVERSHOOT("Overshoot"),
    BOUNCE("Bounce");

    public final String label;

    Interpolation(String label) {
        this.label = label;
    }

    public double ease(double t) {
        return switch (this) {
            case LINEAR, CATMULL_ROM -> t;
            case SMOOTH -> t * t * (3 - 2 * t);
            case EASE_IN -> t * t * t;
            case EASE_OUT -> 1 - Math.pow(1 - t, 3);
            case EASE_IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            case HOLD -> t >= 1 ? 1 : 0;
            case OVERSHOOT -> {
                double back = 1.70158;
                double u = t - 1;
                yield 1 + (back + 1) * u * u * u + back * u * u;
            }
            case BOUNCE -> bounce(t);
        };
    }

    private static double bounce(double t) {
        double strength = 7.5625;
        double width = 2.75;
        if (t < 1 / width) return strength * t * t;
        if (t < 2 / width) {
            t -= 1.5 / width;
            return strength * t * t + 0.75;
        }
        if (t < 2.5 / width) {
            t -= 2.25 / width;
            return strength * t * t + 0.9375;
        }
        t -= 2.625 / width;
        return strength * t * t + 0.984375;
    }

    /** Centripetal-free uniform Catmull-Rom through p1 and p2. */
    public static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }
}
