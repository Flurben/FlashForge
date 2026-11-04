package com.flurben.flashbackreforged.editor;

/** The kinds of keyframe track. {@code angular} marks value slots that wrap at 360 degrees. */
public enum TrackType {
    CAMERA("Camera", new String[]{"X", "Y", "Z", "Yaw", "Pitch", "Roll"}, new double[]{0, 0, 0, 0, 0, 0}, new boolean[]{false, false, false, true, true, true}, 0xFF4FC3F7),
    ORBIT("Camera Orbit", new String[]{"Center X", "Center Y", "Center Z", "Distance", "Yaw", "Pitch"}, new double[]{0, 0, 0, 8, 0, 25}, new boolean[]{false, false, false, false, true, true}, 0xFF81D4FA),
    FOV("FOV", new String[]{"FOV"}, new double[]{70}, new boolean[]{false}, 0xFFFFD54F),
    SPEED("Speed", new String[]{"Speed"}, new double[]{1}, new boolean[]{false}, 0xFFAED581),
    TIME_OF_DAY("Time of Day", new String[]{"Time"}, new double[]{6000}, new boolean[]{false}, 0xFFFFB74D),
    /** Keyframes pair a timeline position with a video time, so spans can be sped up to fit an exact duration. */
    TIMELAPSE("Timelapse", new String[]{"Seconds"}, new double[]{0}, new boolean[]{false}, 0xFFCE93D8),
    /** While the value is above 0.5 the world stands still, then catches up to the timeline when it ends. */
    FREEZE("Freeze", new String[]{"Frozen"}, new double[]{1}, new boolean[]{false}, 0xFF90CAF9),
    /** Keeps the camera pointed at (and optionally following) an entity. The entity UUID is the keyframe text. */
    TRACK_ENTITY("Track Entity", new String[]{"Follow", "Offset X", "Offset Y", "Offset Z", "Eye Height"}, new double[]{0, 0, 0, 0, 1}, new boolean[]{false, false, false, false, false}, 0xFFEF9A9A),
    SHAKE("Camera Shake", new String[]{"Amplitude X", "Amplitude Y", "Frequency"}, new double[]{0.3, 0.3, 1}, new boolean[]{false, false, false}, 0xFFF48FB1),
    /** Plays an external audio file (path is the keyframe text) starting at this keyframe, added at export. */
    AUDIO("Audio", new String[]{"Volume"}, new double[]{1}, new boolean[]{false}, 0xFF80CBC4),
    /** Places a block at a position from this moment on. The block state is the keyframe text. */
    BLOCK_OVERRIDE("Block Override", new String[]{"X", "Y", "Z"}, new double[]{0, 0, 0}, new boolean[]{false, false, false}, 0xFFBCAAA4);

    public final String label;
    public final String[] slots;
    public final double[] defaults;
    public final boolean[] angular;
    public final int color;

    TrackType(String label, String[] slots, double[] defaults, boolean[] angular, int color) {
        this.label = label;
        this.slots = slots;
        this.defaults = defaults;
        this.angular = angular;
        this.color = color;
    }

    /** Values change continuously between keyframes, rather than taking effect at a keyframe. */
    public boolean interpolated() {
        return this != AUDIO && this != BLOCK_OVERRIDE;
    }
}
