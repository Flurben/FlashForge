package com.flurben.flashforge.client.ui;

import com.flurben.flashforge.editor.EditorState;
import com.flurben.flashforge.playback.ReplayPlayer;
import imgui.ImGui;

final class MenuBar {
    private final EditorUi ui;

    MenuBar(EditorUi ui) {
        this.ui = ui;
    }

    /** @return the height of the bar. */
    float draw(ReplayPlayer replay) {
        EditorState editor = replay.editor();
        if (!ImGui.beginMainMenuBar()) return 0;
        float height = ImGui.getWindowHeight();
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("Save Project", "Ctrl+S")) editor.save();
            ImGui.separator();
            if (ImGui.menuItem("Export Video...")) ExportDialog.get().open(ExportDialog.Mode.VIDEO);
            if (ImGui.menuItem("Export Screenshot...")) ExportDialog.get().open(ExportDialog.Mode.SCREENSHOT);
            if (ImGui.menuItem("Export Queue (" + com.flurben.flashforge.export.ExportManager.queue().size() + ")")) ExportDialog.get().open(ExportDialog.Mode.VIDEO);
            ImGui.separator();
            if (ImGui.menuItem("Exit Replay")) replay.exit();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Edit")) {
            if (ImGui.menuItem("Undo", "Ctrl+Z", false, editor.canUndo())) editor.undo();
            if (ImGui.menuItem("Redo", "Ctrl+Y", false, editor.canRedo())) editor.redo();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("View")) {
            if (ImGui.menuItem("Hide Editor", "F1")) this.ui.toggleHidden();
            if (ImGui.menuItem("Show Tip")) this.ui.tips().show();
            ImGui.endMenu();
        }
        ImGui.separator();
        ImGui.textDisabled(replay.meta().name);
        ImGui.endMainMenuBar();
        return height;
    }
}
