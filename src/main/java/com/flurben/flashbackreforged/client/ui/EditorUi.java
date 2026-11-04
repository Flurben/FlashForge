package com.flurben.flashbackreforged.client.ui;

import com.flurben.flashbackreforged.FlashbackReforged;
import com.flurben.flashbackreforged.export.ExportManager;
import com.flurben.flashbackreforged.playback.ReplayPlayer;
import com.mojang.blaze3d.platform.Window;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Owns the ImGui context and draws the replay editor over the game. The game image is shown in the area the panels
 * leave free (see {@link #viewport()}); it always keeps the window's aspect ratio, so nothing has to resize the game's
 * framebuffer, and shader packs and DH keep running at a stable size while the panels change.
 */
public final class EditorUi {
    private static final EditorUi INSTANCE = new EditorUi();

    private final ImGuiImplGlfw glfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 gl3 = new ImGuiImplGl3();
    private final TimelinePanel timeline = new TimelinePanel();
    private final SidePanels side = new SidePanels();
    private final MenuBar menu = new MenuBar(this);
    private final EntityPopup entityPopup = new EntityPopup();
    private final TipsWindow tips = new TipsWindow();
    private boolean initialized;
    private boolean failed;
    private boolean hidden;
    private boolean flying;

    private int viewX, viewY, viewW, viewH;

    public static EditorUi get() {
        return INSTANCE;
    }

    public EntityPopup entityPopup() {
        return this.entityPopup;
    }

    public TipsWindow tips() {
        return this.tips;
    }

    public SidePanels side() {
        return this.side;
    }

    public TimelinePanel timeline() {
        return this.timeline;
    }

    public boolean hidden() {
        return this.hidden;
    }

    public void toggleHidden() {
        this.hidden = !this.hidden;
    }

    /** True when the panels should be drawn this frame. */
    private boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        return ReplayPlayer.isActive() && !this.hidden && !this.failed && mc.screen == null && !ExportManager.isExporting();
    }

    /** Mouse input is for the UI, not the game. */
    public boolean captureMouse() {
        return visible() && !this.flying && this.initialized;
    }

    /** Keyboard input is for the UI, not the game. */
    public boolean captureKeyboard() {
        return visible() && this.initialized && ImGui.getIO().getWantCaptureKeyboard();
    }

    public boolean flying() {
        return this.flying;
    }

    private void init() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.setFontGlobalScale(1f);
        UiTheme.apply();
        // Chains to the callbacks Minecraft already installed, so the game still receives events.
        this.glfw.init(window, true);
        this.gl3.init("#version 150");
        this.initialized = true;
        FlashbackReforged.LOGGER.info("Editor UI initialized");
    }

    /** Called after the game has drawn itself to the default framebuffer and before the buffers swap. */
    public void render() {
        if (this.failed || !ReplayPlayer.isActive()) {
            finishWindowShot();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        if (!this.initialized) {
            try {
                init();
            } catch (Throwable t) {
                // Losing the panels is much better than losing the session: playback still works without them.
                this.failed = true;
                FlashbackReforged.LOGGER.error("Could not start the editor UI", t);
                return;
            }
        }
        if (ExportManager.isExporting()) {
            setFlying(false);
            drawExportProgress();
            finishWindowShot();
            return;
        }
        if (!visible()) {
            setFlying(false);
            // With the panels gone the game has the whole window again.
            if (mc.screen == null) com.flurben.flashbackreforged.client.EditorSizing.request(null);
            finishWindowShot();
            return;
        }

        ReplayPlayer replay = ReplayPlayer.active();
        SavedGlState saved = SavedGlState.capture();
        try {
            ImGui.getIO().setFontGlobalScale(com.flurben.flashbackreforged.client.FlashbackReforgedConfig.UI_SCALE.get().floatValue());
            this.gl3.newFrame();
            this.glfw.newFrame();
            ImGui.newFrame();
            float width = window.getScreenWidth();
            float height = window.getScreenHeight();
            float menuHeight = this.menu.draw(replay);
            float timelineHeight = Math.min(this.timeline.preferredHeight(), height * 0.45f);
            float sideWidth = Math.min(this.side.preferredWidth(), width * 0.33f);
            this.timeline.draw(replay, 0, height - timelineHeight, width, timelineHeight);
            this.side.draw(replay, width - sideWidth, menuHeight, sideWidth, height - timelineHeight - menuHeight);
            ExportDialog.get().draw(replay);
            this.tips.draw(replay);
            this.entityPopup.draw(replay);
            computeViewport(window, menuHeight, sideWidth, timelineHeight, replay.editor().visuals());
            updateFlying(mc);
            GuideOverlay.draw(replay, this.viewX, this.viewY, this.viewW, this.viewH, window);
            ImGui.render();
            this.gl3.renderDrawData(ImGui.getDrawData());
        } catch (Throwable t) {
            this.failed = true;
            FlashbackReforged.LOGGER.error("Editor UI failed; continuing without panels", t);
        } finally {
            saved.restore();
        }
        finishWindowShot();
    }

    private void drawExportProgress() {
        SavedGlState saved = SavedGlState.capture();
        try {
            this.gl3.newFrame();
            this.glfw.newFrame();
            ImGui.newFrame();
            ImGui.setNextWindowPos(ImGui.getIO().getDisplaySizeX() / 2 - 220, 40, imgui.flag.ImGuiCond.Always);
            ImGui.setNextWindowSize(440, 0, imgui.flag.ImGuiCond.Always);
            if (ImGui.begin("Exporting", imgui.flag.ImGuiWindowFlags.NoMove | imgui.flag.ImGuiWindowFlags.NoResize | imgui.flag.ImGuiWindowFlags.NoCollapse)) {
                ImGui.text(ExportManager.status());
                ImGui.progressBar((float) ExportManager.progress(), -1, 0);
                if (ImGui.button("Cancel")) ExportManager.cancel();
            }
            ImGui.end();
            ImGui.render();
            this.gl3.renderDrawData(ImGui.getDrawData());
        } catch (Throwable t) {
            // Only the progress window is lost; the export is unaffected.
            this.failed = true;
            FlashbackReforged.LOGGER.error("Could not draw export progress", t);
        } finally {
            saved.restore();
        }
    }

    /** Asks for the next finished window, panels included, to be saved as a PNG. Used by the self test. */
    public void requestWindowShot(java.nio.file.Path file) {
        this.windowShot = file;
    }

    private java.nio.file.Path windowShot;

    private void finishWindowShot() {
        if (this.windowShot == null) return;
        java.nio.file.Path file = this.windowShot;
        this.windowShot = null;
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetFramebufferSize(Minecraft.getInstance().getWindow().getWindow(), w, h);
        try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(w[0], h[0], false)) {
            int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadBuffer(GL11.GL_BACK);
            com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            java.nio.ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(w[0] * h[0] * 4);
            GL11.glReadPixels(0, 0, w[0], h[0], GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            for (int y = 0; y < h[0]; y++) {
                for (int x = 0; x < w[0]; x++) {
                    int rgba = pixels.getInt((x + (h[0] - 1 - y) * w[0]) * 4);
                    image.setPixelRGBA(x, y, rgba | 0xFF000000);
                }
            }
            org.lwjgl.system.MemoryUtil.memFree(pixels);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
            java.nio.file.Files.createDirectories(file.getParent());
            image.writeToFile(file);
        } catch (Exception e) {
            FlashbackReforged.LOGGER.error("Could not save window image", e);
        }
    }

    /** Where the finished game image is drawn this frame, or null to fill the window. Framebuffer pixels, bottom-left. */
    public int[] blitArea() {
        if (!ExportManager.isExporting()) return viewport();
        // During an export the render size may not match the window at all, so show it fitted inside the window.
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetFramebufferSize(Minecraft.getInstance().getWindow().getWindow(), w, h);
        int[] size = ExportManager.renderSize();
        float scale = Math.min((float) w[0] / size[0], (float) h[0] / size[1]);
        int vw = Math.max(1, (int) (size[0] * scale)), vh = Math.max(1, (int) (size[1] * scale));
        return new int[]{(w[0] - vw) / 2, (h[0] - vh) / 2, vw, vh};
    }

    /**
     * Fits the game image into the space the panels leave, in framebuffer pixels (origin bottom-left). The window's own
     * width and height can't be used here, since they report the render size and this decides what that should be.
     */
    private void computeViewport(Window window, float top, float right, float bottom, com.flurben.flashbackreforged.editor.Visuals visuals) {
        int[] fbW = new int[1], fbH = new int[1];
        GLFW.glfwGetFramebufferSize(window.getWindow(), fbW, fbH);
        float scale = (float) fbW[0] / Math.max(1, window.getScreenWidth());
        float availW = (window.getScreenWidth() - right) * scale;
        float availH = (window.getScreenHeight() - top - bottom) * scale;
        int[] render = null;

        if (visuals.sizing == 3) {
            this.viewX = 0;
            this.viewY = 0;
            this.viewW = Math.max(1, fbW[0]);
            this.viewH = Math.max(1, fbH[0]);
        } else if (visuals.sizing == 2) {
            this.viewW = Math.max(16, (int) availW);
            this.viewH = Math.max(16, (int) availH);
            this.viewX = 0;
            this.viewY = (int) (bottom * scale);
            render = new int[]{this.viewW, this.viewH};
        } else {
            float aspect = visuals.sizing == 1 ? visuals.viewAspect : (float) fbW[0] / Math.max(1, fbH[0]);
            float w = availW;
            float h = w / aspect;
            if (h > availH) {
                h = availH;
                w = h * aspect;
            }
            this.viewW = Math.max(16, (int) w);
            this.viewH = Math.max(16, (int) h);
            this.viewX = (int) ((availW - w) / 2);
            this.viewY = (int) (bottom * scale + (availH - h) / 2);
            if (visuals.sizing == 1) render = new int[]{this.viewW, this.viewH};
        }
        com.flurben.flashbackreforged.client.EditorSizing.request(render);
    }

    /** The entity at a window position given in screen coordinates, or null. */
    private net.minecraft.world.entity.Entity pickAtCursor(Window window, double cursorX, double cursorY) {
        int[] fbW = new int[1], fbH = new int[1];
        GLFW.glfwGetFramebufferSize(window.getWindow(), fbW, fbH);
        double scale = (double) fbW[0] / Math.max(1, window.getScreenWidth());
        double px = cursorX * scale;
        double py = fbH[0] - cursorY * scale;
        if (px < this.viewX || py < this.viewY || px > this.viewX + this.viewW || py > this.viewY + this.viewH) return null;
        return pickAt((px - this.viewX) / this.viewW * 2 - 1, (py - this.viewY) / this.viewH * 2 - 1);
    }

    /** The entity at a point in the game image: -1 to 1 across and up. */
    public net.minecraft.world.entity.Entity pickAt(double ndcX, double ndcY) {
        return EntityPopup.pick(ReplayPlayer.active(), ndcX, ndcY, (double) this.viewW / Math.max(1, this.viewH));
    }

    /** Where the game image goes this frame, or null to fill the window. Framebuffer pixels, origin bottom-left. */
    public int[] viewport() {
        if (!visible() || !this.initialized || this.viewW == 0) return null;
        return new int[]{this.viewX, this.viewY, this.viewW, this.viewH};
    }

    private void updateFlying(Minecraft mc) {
        ImGuiIO io = ImGui.getIO();
        boolean overUi = ImGui.isWindowHovered(imgui.flag.ImGuiHoveredFlags.AnyWindow) || io.getWantCaptureMouse();
        long handle = mc.getWindow().getWindow();
        boolean down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (!this.flying && down && !overUi) {
            double[] x = new double[1], y = new double[1];
            GLFW.glfwGetCursorPos(handle, x, y);
            this.pressX = x[0];
            this.pressY = y[0];
            this.pressTime = System.nanoTime();
            this.pressYaw = mc.player.getYRot();
            this.pressPitch = mc.player.getXRot();
            setFlying(true);
        } else if (this.flying && !down) {
            setFlying(false);
            // A quick click that didn't turn the camera means "what's this?" rather than "fly".
            boolean click = System.nanoTime() - this.pressTime < 300_000_000L
                    && Math.abs(mc.player.getYRot() - this.pressYaw) < 1f && Math.abs(mc.player.getXRot() - this.pressPitch) < 1f;
            if (click) {
                GLFW.glfwSetCursorPos(handle, this.pressX, this.pressY);
                net.minecraft.world.entity.Entity hit = pickAtCursor(mc.getWindow(), this.pressX, this.pressY);
                if (hit != null) this.entityPopup.open(hit);
            }
        }
    }

    private double pressX, pressY;
    private long pressTime;
    private float pressYaw, pressPitch;

    private void setFlying(boolean flying) {
        if (this.flying == flying) return;
        this.flying = flying;
        Minecraft mc = Minecraft.getInstance();
        if (flying) mc.mouseHandler.grabMouse();
        else mc.mouseHandler.releaseMouse();
    }

    /** ImGui changes GL state the game assumes is unchanged; this restores what its renderer does not. */
    private record SavedGlState(int program, int texture, int activeTexture, int vao, int arrayBuffer, int framebuffer, boolean blend, boolean depth, boolean cull, boolean scissor,
                                int blendSrcRgb, int blendDstRgb, int blendSrcAlpha, int blendDstAlpha) {
        static SavedGlState capture() {
            return new SavedGlState(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
                    GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
                    GL11.glIsEnabled(GL11.GL_BLEND), GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glIsEnabled(GL11.GL_CULL_FACE), GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        }

        void restore() {
            GL20.glUseProgram(this.program);
            GL13.glActiveTexture(this.activeTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.arrayBuffer);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
            set(GL11.GL_BLEND, this.blend);
            set(GL11.GL_DEPTH_TEST, this.depth);
            set(GL11.GL_CULL_FACE, this.cull);
            set(GL11.GL_SCISSOR_TEST, this.scissor);
            GL14.glBlendFuncSeparate(this.blendSrcRgb, this.blendDstRgb, this.blendSrcAlpha, this.blendDstAlpha);
        }

        private static void set(int capability, boolean on) {
            if (on) GL11.glEnable(capability);
            else GL11.glDisable(capability);
        }
    }
}
