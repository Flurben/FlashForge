package com.flurben.flashbackreforged.client.ui;

import com.flurben.flashbackreforged.editor.Visuals;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.mojang.blaze3d.platform.Window;
import imgui.ImDrawList;
import imgui.ImGui;

/** Composition guides over the game image. Drawn by the UI, so they never appear in exports. */
final class GuideOverlay {
    private static final int COLOR = ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.55f);
    private static final int SHADE = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.55f);

    private GuideOverlay() {}

    static void draw(ReplayPlayer replay, int viewX, int viewY, int viewW, int viewH, Window window) {
        Visuals v = replay.editor().visuals();
        if (!v.ruleOfThirds && !v.centerGuide && v.aspectGuide <= 0) return;
        int[] fbW = new int[1], fbH = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window.getWindow(), fbW, fbH);
        float toUi = (float) window.getScreenWidth() / Math.max(1, fbW[0]);
        float x = viewX * toUi;
        float w = viewW * toUi;
        float h = viewH * toUi;
        // Framebuffer y grows upward, UI y grows downward.
        float y = window.getScreenHeight() - (viewY * toUi + h);

        ImDrawList draw = ImGui.getForegroundDrawList();
        if (v.ruleOfThirds) {
            for (int i = 1; i <= 2; i++) {
                draw.addLine(x + w * i / 3, y, x + w * i / 3, y + h, COLOR);
                draw.addLine(x, y + h * i / 3, x + w, y + h * i / 3, COLOR);
            }
        }
        if (v.centerGuide) {
            draw.addLine(x + w / 2 - 12, y + h / 2, x + w / 2 + 12, y + h / 2, COLOR);
            draw.addLine(x + w / 2, y + h / 2 - 12, x + w / 2, y + h / 2 + 12, COLOR);
        }
        if (v.aspectGuide > 0) {
            float current = w / h;
            if (v.aspectGuide < current) {
                float keep = h * v.aspectGuide;
                float bar = (w - keep) / 2;
                draw.addRectFilled(x, y, x + bar, y + h, SHADE);
                draw.addRectFilled(x + w - bar, y, x + w, y + h, SHADE);
            } else {
                float keep = w / v.aspectGuide;
                float bar = (h - keep) / 2;
                draw.addRectFilled(x, y, x + w, y + bar, SHADE);
                draw.addRectFilled(x, y + h - bar, x + w, y + h, SHADE);
            }
        }
    }
}
