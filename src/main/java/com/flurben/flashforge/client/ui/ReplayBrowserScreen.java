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
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/** Lists recordings and opens, renames, combines or deletes them. */
public final class ReplayBrowserScreen extends Screen {
    public enum Sort {
        DATE("date", Comparator.comparingLong(r -> r.entry.meta().createdAt)),
        NAME("name", Comparator.comparing(r -> r.entry.meta().name, String.CASE_INSENSITIVE_ORDER)),
        WORLD("world", Comparator.comparing(r -> String.valueOf(r.entry.meta().world), String.CASE_INSENSITIVE_ORDER)),
        DURATION("duration", Comparator.comparingInt(r -> r.entry.meta().totalTicks)),
        SIZE("size", Comparator.comparingLong(r -> r.bytes));

        final String key;
        final Comparator<Row> order;

        Sort(String key, Comparator<Row> order) {
            this.key = key;
            this.order = order;
        }
    }

    private static Sort sort = Sort.DATE;
    private static boolean descending = true;

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
        this.list.applySort();
        addRenderableWidget(this.list);
        addRenderableWidget(Button.builder(sortLabel(), b -> {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            b.setMessage(sortLabel());
            this.list.applySort();
        }).bounds(this.width - 134, 6, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(descending ? "v" : "^"), b -> {
            descending = !descending;
            b.setMessage(Component.literal(descending ? "v" : "^"));
            this.list.applySort();
        }).bounds(this.width - 30, 6, 20, 20).build());

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

    private static Component sortLabel() {
        return Component.translatable("flashforge.browser.sort", Component.translatable("flashforge.browser.sort." + sort.key));
    }

    /** Lets go of the thumbnails, which are textures on the graphics card. */
    @Override
    public void removed() {
        for (Row row : this.list.children()) row.releaseIcon();
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

        void applySort() {
            List<Row> rows = new ArrayList<>(children());
            rows.sort(descending ? sort.order.reversed() : sort.order);
            replaceEntries(rows);
        }

        @Override
        public int getRowWidth() {
            return 460;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + 236;
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
        final long bytes;
        private ResourceLocation icon;
        private boolean iconLoaded;
        private long lastClick;

        Row(ReplayStorage.Entry entry) {
            this.entry = entry;
            int seconds = entry.meta().totalTicks / 20;
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(entry.meta().createdAt));
            this.bytes = size(entry.dir());
            String size = this.bytes > 1 << 30 ? String.format("%.1f GB", this.bytes / (double) (1 << 30)) : String.format("%.1f MB", this.bytes / (double) (1 << 20));
            this.details = entry.meta().world + "  -  " + when + "  -  " + String.format("%d:%02d", seconds / 60, seconds % 60) + "  -  " + size;
        }

        private static long size(Path dir) {
            try (Stream<Path> files = Files.walk(dir)) {
                return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
            } catch (IOException e) {
                return 0;
            }
        }

        /** Loaded on first draw, so a long list doesn't load pictures nobody scrolled to. */
        private void loadIcon() {
            this.iconLoaded = true;
            Path file = this.entry.dir().resolve(com.flurben.flashforge.record.Thumbnails.FILE);
            if (!Files.exists(file)) return;
            try (InputStream in = Files.newInputStream(file)) {
                this.icon = new ResourceLocation(FlashForge.MOD_ID, "replay_icon/" + Integer.toHexString(this.entry.dir().toString().hashCode()));
                Minecraft.getInstance().getTextureManager().register(this.icon, new DynamicTexture(NativeImage.read(in)));
            } catch (IOException e) {
                FlashForge.LOGGER.warn("Could not read thumbnail {}", file, e);
                this.icon = null;
            }
        }

        void releaseIcon() {
            if (this.icon != null) Minecraft.getInstance().getTextureManager().release(this.icon);
            this.icon = null;
            this.iconLoaded = false;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partial) {
            var font = Minecraft.getInstance().font;
            Component name = Component.literal(this.entry.meta().name);
            if (!this.entry.meta().finished) name = name.copy().append(Component.translatable("flashforge.browser.unfinished").withStyle(ChatFormatting.YELLOW));
            if (!this.iconLoaded) loadIcon();
            g.fill(left + 2, top + 2, left + 58, top + 34, 0xFF202020);
            if (this.icon != null) g.blit(this.icon, left + 2, top + 2, 0f, 0f, 56, 32, 56, 32);
            g.drawString(font, name, left + 62, top + 5, 0xFFFFFF);
            g.drawString(font, font.plainSubstrByWidth(this.details, width - 66), left + 62, top + 18, 0x909090);
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
