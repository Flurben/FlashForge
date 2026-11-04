package com.flurben.flashforge.client;

import net.minecraft.client.Minecraft;

import java.util.Arrays;

/**
 * The size the game renders at while the editor is open, when that differs from the window. Kept apart from the editor UI
 * because the window reads it from the moment the game starts, long before the UI's native libraries should load.
 */
public final class EditorSizing {
    private static int[] desired;
    private static int[] applied;

    private EditorSizing() {}

    public static int[] current() {
        return applied;
    }

    /** Null means the size of the window. */
    public static void request(int[] size) {
        desired = size;
    }

    /** Must run at the top of a frame, since it replaces the render targets. */
    public static void apply(Minecraft mc) {
        if (Arrays.equals(desired, applied)) return;
        applied = desired;
        mc.resizeDisplay();
    }
}
