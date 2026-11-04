package com.flurben.flashbackreforged.client.ui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

final class UiTheme {
    private UiTheme() {}

    static void apply() {
        ImGuiStyle style = ImGui.getStyle();
        ImGui.styleColorsDark();
        style.setWindowRounding(0f);
        style.setFrameRounding(3f);
        style.setGrabRounding(3f);
        style.setWindowBorderSize(1f);
        style.setFramePadding(6f, 3f);
        style.setItemSpacing(6f, 5f);
        style.setColor(ImGuiCol.WindowBg, 0.09f, 0.10f, 0.12f, 1f);
        style.setColor(ImGuiCol.TitleBg, 0.12f, 0.13f, 0.16f, 1f);
        style.setColor(ImGuiCol.TitleBgActive, 0.14f, 0.16f, 0.20f, 1f);
        style.setColor(ImGuiCol.MenuBarBg, 0.12f, 0.13f, 0.16f, 1f);
        style.setColor(ImGuiCol.FrameBg, 0.16f, 0.18f, 0.22f, 1f);
        style.setColor(ImGuiCol.FrameBgHovered, 0.22f, 0.25f, 0.30f, 1f);
        style.setColor(ImGuiCol.FrameBgActive, 0.26f, 0.30f, 0.37f, 1f);
        style.setColor(ImGuiCol.Header, 0.19f, 0.22f, 0.28f, 1f);
        style.setColor(ImGuiCol.HeaderHovered, 0.25f, 0.30f, 0.38f, 1f);
        style.setColor(ImGuiCol.HeaderActive, 0.28f, 0.34f, 0.44f, 1f);
        style.setColor(ImGuiCol.Button, 0.20f, 0.24f, 0.30f, 1f);
        style.setColor(ImGuiCol.ButtonHovered, 0.27f, 0.33f, 0.42f, 1f);
        style.setColor(ImGuiCol.ButtonActive, 0.31f, 0.39f, 0.50f, 1f);
        style.setColor(ImGuiCol.CheckMark, 0.95f, 0.65f, 0.25f, 1f);
        style.setColor(ImGuiCol.SliderGrab, 0.95f, 0.65f, 0.25f, 1f);
        style.setColor(ImGuiCol.SliderGrabActive, 1f, 0.75f, 0.35f, 1f);
    }
}
