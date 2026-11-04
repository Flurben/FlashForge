package com.flurben.flashbackreforged;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This mod used to be called FlashForge. Its files were named after its mod ID, so without this, renaming it would leave
 * people's replays, settings and key bindings behind under the old name.
 */
final class LegacyNameMigration {
    static final String OLD_ID = "flashforge";

    private LegacyNameMigration() {}

    /** Before the config is registered, so the settings are found under their new name. */
    static void run() {
        Path game = FMLPaths.GAMEDIR.get();
        move(game.resolve(OLD_ID), game.resolve(FlashbackReforged.MOD_ID));
        move(FMLPaths.CONFIGDIR.get().resolve(OLD_ID + "-client.toml"), FMLPaths.CONFIGDIR.get().resolve(FlashbackReforged.MOD_ID + "-client.toml"));
        renameKeyBindings(game.resolve("options.txt"));
    }

    /** Never overwrites: if both exist, the new one is what's in use and the old one stays for the player to sort out. */
    private static void move(Path from, Path to) {
        if (!Files.exists(from)) return;
        if (Files.exists(to)) {
            FlashbackReforged.LOGGER.warn("Leaving {} in place because {} already exists", from, to);
            return;
        }
        try {
            Files.move(from, to);
            FlashbackReforged.LOGGER.info("Moved {} to {}", from, to);
        } catch (IOException e) {
            FlashbackReforged.LOGGER.error("Could not move {} to {}", from, to, e);
        }
    }

    private static void renameKeyBindings(Path options) {
        if (!Files.exists(options)) return;
        try {
            String text = Files.readString(options, StandardCharsets.UTF_8);
            String oldKey = "key_key." + OLD_ID + ".";
            if (text.contains(oldKey)) Files.writeString(options, text.replace(oldKey, "key_key." + FlashbackReforged.MOD_ID + "."), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FlashbackReforged.LOGGER.warn("Could not carry over key bindings", e);
        }
    }
}
