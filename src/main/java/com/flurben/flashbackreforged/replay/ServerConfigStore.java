package com.flurben.flashbackreforged.replay;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.flurben.flashbackreforged.FlashbackReforged;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Stores the SERVER-type mod configs that were in force while recording. A client normally receives these during the
 * login handshake, which a replay never performs, and mods read them client-side too (Forge itself does so every entity
 * tick). Restoring the recorded values rather than defaults keeps mods behaving as they did when the replay was made.
 */
public final class ServerConfigStore {
    private static final String FILE = "serverconfig.nbt";

    private ServerConfigStore() {}

    public static void save(Path replayDir) throws IOException {
        CompoundTag root = new CompoundTag();
        Set<ModConfig> configs = ConfigTracker.INSTANCE.configSets().get(ModConfig.Type.SERVER);
        if (configs != null) {
            for (ModConfig config : configs) {
                CommentedConfig data = config.getConfigData();
                if (data == null) continue;
                try {
                    String toml = TomlFormat.instance().createWriter().writeToString(data);
                    root.putByteArray(config.getFileName(), toml.getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    FlashbackReforged.LOGGER.warn("Could not store server config {}", config.getFileName(), e);
                }
            }
        }
        NbtIo.writeCompressed(root, replayDir.resolve(FILE).toFile());
    }

    /** Must run before the replay's world exists, since ticking it reads these values. */
    public static void restore(Path replayDir) {
        // Defaults cover mods added since the recording, and replays with no stored file.
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        Path file = replayDir.resolve(FILE);
        if (!Files.exists(file)) return;
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file.toFile());
        } catch (IOException e) {
            FlashbackReforged.LOGGER.warn("Could not read stored server configs; using defaults", e);
            return;
        }
        for (String name : root.getAllKeys()) {
            ModConfig config = ConfigTracker.INSTANCE.fileMap().get(name);
            if (config == null) continue;
            try {
                config.acceptSyncedConfig(root.getByteArray(name));
            } catch (RuntimeException e) {
                FlashbackReforged.LOGGER.warn("Could not apply stored server config {}; using defaults", name, e);
            }
        }
    }
}
