package com.flurben.flashforge.client.ui;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.replay.ReplayCombiner;
import com.flurben.flashforge.replay.ReplayStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/** Lists recordings and opens, renames, combines or deletes them. */
public final class ReplayBrowserScreen extends Screen {
    private final Screen parent;
    private ReplayList list;
    private EditBox nameBox;
    private Button open, rename, delete, combine;
    private ReplayStorage.Entry combineFirst;

    public ReplayBrowserScreen(Screen parent) {
        super(Component.translatable("flashforge.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.list = new ReplayList(this.minecraft, this.width, this.height, 32, this.height - 80, 36);
        for (ReplayStorage.Entry e : ReplayStorage.list()) this.list.addRow(new Row(e));
        addRenderableWidget(this.list);

        int y = this.height - 72;
        int cx = this.width / 2;
        this.nameBox = new EditBox(this.font, cx - 154, y, 308, 20, Component.translatable("flashforge.browser.name"));
        this.nameBox.setMaxLength(64);
        addRenderableWidget(this.nameBox);

        y += 24;
        this.open = addRenderableWidget(Button.builder(Component.translatable("flashforge.browser.open"), b -> openSelected()).bounds(cx - 154, y, 100, 20).build());
        this.rename = addRenderableWidget(Button.builder(Component.translatable("flashforge.browser.rename"), b -> renameSelected()).bounds(cx - 50, y, 100, 20).build());
        this.delete = addRenderableWidget(Button.builder(Component.translatable("flashforge.browser.delete"), b -> confirmDelete()).bounds(cx + 54, y, 100, 20).build());
        y += 24;
        this.combine = addRenderableWidget(Button.builder(Component.translatable("flashforge.browser.combine"), b -> combineSelected()).bounds(cx - 154, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("flashforge.browser.settings"), b -> this.minecraft.setScreen(new SettingsScreen(this))).bounds(cx - 50, y, 48, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("flashforge.browser.folder"), b -> Util.getPlatform().openFile(ReplayStorage.root().toFile())).bounds(cx + 2, y, 48, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(cx + 54, y, 100, 20).build());
        updateButtons();
    }

    private ReplayStorage.Entry selected() {
        Row row = this.list.getSelected();
        return row == null ? null : row.entry;
    }

    private void updateButtons() {
        boolean has = selected() != null;
        this.open.active = has;
        this.rename.active = has;
        this.delete.active = has;
        this.combine.active = has;
        this.combine.setMessage(this.combineFirst == null ? Component.translatable("flashforge.browser.combine") : Component.translatable("flashforge.browser.combine_with"));
    }

    private void openSelected() {
        ReplayStorage.Entry entry = selected();
        if (entry == null) return;
        List<String> missing = new ArrayList<>();
        for (String mod : entry.meta().mods.keySet()) {
            if (!ModList.get().isLoaded(mod)) missing.add(mod);
        }
        if (missing.isEmpty()) {
            launch(entry.dir());
            return;
        }
        // Registry ids in a replay are only meaningful with the same mods; a different pack can show the wrong blocks.
        this.minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) launch(entry.dir());
            else this.minecraft.setScreen(this);
        }, Component.translatable("flashforge.browser.missing_mods"), Component.literal(String.join(", ", missing.subList(0, Math.min(20, missing.size()))))));
    }

    /** Opening renders nested frames, so it must come from the task queue, not from inside this input event. */
    private void launch(Path dir) {
        Minecraft mc = this.minecraft;
        mc.tell(() -> {
            try {
                ReplayPlayer.open(dir);
            } catch (Throwable t) {
                FlashForge.LOGGER.error("Could not open replay {}", dir, t);
                mc.setScreen(new AlertScreen(() -> mc.setScreen(new ReplayBrowserScreen(new TitleScreen())),
                        Component.translatable("flashforge.browser.open_failed"), Component.literal(String.valueOf(t.getMessage()))));
            }
        });
    }

    private void renameSelected() {
        ReplayStorage.Entry entry = selected();
        if (entry == null || this.nameBox.getValue().isBlank()) return;
        entry.meta().name = this.nameBox.getValue().trim();
        try {
            entry.meta().write(entry.dir());
        } catch (IOException e) {
            FlashForge.LOGGER.error("Could not rename replay", e);
        }
        this.minecraft.setScreen(new ReplayBrowserScreen(this.parent));
    }

    private void confirmDelete() {
        ReplayStorage.Entry entry = selected();
        if (entry == null) return;
        this.minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                try {
                    ReplayStorage.delete(entry.dir());
                } catch (IOException e) {
                    FlashForge.LOGGER.error("Could not delete replay", e);
                }
            }
            this.minecraft.setScreen(new ReplayBrowserScreen(this.parent));
        }, Component.translatable("flashforge.browser.delete_confirm", entry.meta().name), Component.translatable("flashforge.browser.delete_warning")));
    }

    private void combineSelected() {
        ReplayStorage.Entry entry = selected();
        if (entry == null) return;
        if (this.combineFirst == null) {
            this.combineFirst = entry;
            updateButtons();
            return;
        }
        try {
            ReplayCombiner.combine(this.combineFirst.dir(), entry.dir());
            this.minecraft.setScreen(new ReplayBrowserScreen(this.parent));
        } catch (IOException e) {
            FlashForge.LOGGER.error("Could not combine replays", e);
            this.minecraft.setScreen(new AlertScreen(() -> this.minecraft.setScreen(new ReplayBrowserScreen(this.parent)),
                    Component.translatable("flashforge.browser.combine_failed"), Component.literal(String.valueOf(e.getMessage()))));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partial);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        if (this.list.children().isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("flashforge.browser.empty"), this.width / 2, this.height / 2 - 20, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private final class ReplayList extends ObjectSelectionList<Row> {
        ReplayList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        void addRow(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return 340;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + 180;
        }

        @Override
        public void setSelected(Row row) {
            super.setSelected(row);
            if (row != null) ReplayBrowserScreen.this.nameBox.setValue(row.entry.meta().name);
            updateButtons();
        }
    }

    private final class Row extends ObjectSelectionList.Entry<Row> {
        final ReplayStorage.Entry entry;
        final String details;
        private long lastClick;

        Row(ReplayStorage.Entry entry) {
            this.entry = entry;
            int seconds = entry.meta().totalTicks / 20;
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(entry.meta().createdAt));
            this.details = entry.meta().world + "  -  " + when + "  -  " + String.format("%d:%02d", seconds / 60, seconds % 60) + "  -  " + size(entry.dir());
        }

        private static String size(Path dir) {
            try (Stream<Path> files = Files.walk(dir)) {
                long bytes = files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
                return bytes > 1 << 30 ? String.format("%.1f GB", bytes / (double) (1 << 30)) : String.format("%.1f MB", bytes / (double) (1 << 20));
            } catch (IOException e) {
                return "?";
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partial) {
            var font = Minecraft.getInstance().font;
            Component name = Component.literal(this.entry.meta().name);
            if (!this.entry.meta().finished) name = name.copy().append(Component.translatable("flashforge.browser.unfinished").withStyle(ChatFormatting.YELLOW));
            g.drawString(font, name, left + 4, top + 3, 0xFFFFFF);
            g.drawString(font, font.plainSubstrByWidth(this.details, width - 8), left + 4, top + 16, 0x909090);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            ReplayBrowserScreen.this.list.setSelected(this);
            long now = Util.getMillis();
            if (now - this.lastClick < 300) openSelected();
            this.lastClick = now;
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.entry.meta().name);
        }
    }
}
