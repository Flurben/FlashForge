package com.flurben.flashbackreforged.client.ui;

import com.flurben.flashbackreforged.client.FlashbackReforgedConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Preferences, reachable from the replay browser and from Forge's mod list. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private EditBox ffmpeg;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("flashback_reforged.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 155;
        int y = this.height / 4;
        addRenderableWidget(CycleButton.<FlashbackReforgedConfig.AutoRecord>builder(v -> Component.translatable("flashback_reforged.settings.auto." + v.name().toLowerCase()))
                .withValues(FlashbackReforgedConfig.AutoRecord.values())
                .withInitialValue(FlashbackReforgedConfig.AUTO_RECORD.get())
                .create(x, y, 310, 20, Component.translatable("flashback_reforged.settings.auto"), (b, v) -> FlashbackReforgedConfig.AUTO_RECORD.set(v)));
        addRenderableWidget(CycleButton.onOffBuilder(FlashbackReforgedConfig.SHOW_INDICATOR.get())
                .create(x, y + 24, 310, 20, Component.translatable("flashback_reforged.settings.indicator"), (b, v) -> FlashbackReforgedConfig.SHOW_INDICATOR.set(v)));
        addRenderableWidget(CycleButton.onOffBuilder(FlashbackReforgedConfig.RECORD_VOICE.get())
                .create(x, y + 48, 310, 20, Component.translatable("flashback_reforged.settings.voice"), (b, v) -> FlashbackReforgedConfig.RECORD_VOICE.set(v)));
        this.ffmpeg = new EditBox(this.font, x, y + 88, 310, 20, Component.translatable("flashback_reforged.settings.ffmpeg"));
        this.ffmpeg.setMaxLength(1024);
        this.ffmpeg.setValue(FlashbackReforgedConfig.FFMPEG_PATH.get());
        addRenderableWidget(this.ffmpeg);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        graphics.drawString(this.font, Component.translatable("flashback_reforged.settings.ffmpeg"), this.width / 2 - 155, this.height / 4 + 76, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        FlashbackReforgedConfig.FFMPEG_PATH.set(this.ffmpeg.getValue().trim());
        FlashbackReforgedConfig.SPEC.save();
        this.minecraft.setScreen(this.parent);
    }
}
