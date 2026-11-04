package com.flurben.flashforge.compat;

import com.flurben.flashforge.FlashForge;
import com.flurben.flashforge.playback.ReplayPlayer;
import com.flurben.flashforge.record.Recorder;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.levelHandling.IDhApiSaveStructure;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.minecraftforge.fml.ModList;

import java.io.File;
import java.util.Map;

/**
 * Shows the LODs that existed when a replay was recorded. Recording stores where each dimension's LOD database is; a
 * replay points Distant Horizons at those folders and makes it read-only, so watching can never alter a real world's LODs.
 */
public final class DistantHorizonsCompat {
    private static final boolean LOADED = ModList.get().isLoaded("distanthorizons");

    private DistantHorizonsCompat() {}

    public static boolean loaded() {
        return LOADED;
    }

    public static void init() {
        if (!LOADED) return;
        try {
            Hooks.register();
        } catch (Throwable t) {
            FlashForge.LOGGER.error("This Distant Horizons version has an unexpected API; replays will not use its LODs", t);
        }
    }

    /** Called when recording starts. */
    public static void collectPaths(Map<String, String> into) {
        if (!LOADED) return;
        try {
            Hooks.collect(into);
        } catch (Throwable t) {
            FlashForge.LOGGER.warn("Could not read Distant Horizons save folders", t);
        }
    }

    public static void onReplayClosed() {
        if (!LOADED) return;
        try {
            Hooks.unbind();
        } catch (Throwable t) {
            FlashForge.LOGGER.debug("Could not unbind Distant Horizons overrides", t);
        }
    }

    /** Separate class so Distant Horizons types are only loaded when the mod is installed. */
    private static final class Hooks {
        private static final ReplaySaves SAVES = new ReplaySaves();
        private static boolean bound;

        static void register() {
            DhApiEventRegister.on(DhApiWorldLoadEvent.class, new DhApiWorldLoadEvent() {
                @Override
                public void onWorldLoad(DhApiEventParam<EventParam> param) {
                    if (ReplayPlayer.isActive()) enterReplay(ReplayPlayer.active());
                }
            });
            DhApiEventRegister.on(DhApiWorldUnloadEvent.class, new DhApiWorldUnloadEvent() {
                @Override
                public void onWorldUnload(DhApiEventParam<EventParam> param) {
                    unbind();
                }
            });
            DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
                @Override
                public void onLevelLoad(DhApiEventParam<EventParam> param) {
                    // Dimensions load as the recording moves between them, so their folders are noted as they appear.
                    Recorder recorder = Recorder.active();
                    if (recorder != null) collect(recorder.meta().distantHorizons);
                }
            });
        }

        private static void enterReplay(ReplayPlayer replay) {
            DhApi.Delayed.worldProxy.setReadOnly(true);
            SAVES.folders.clear();
            replay.meta().distantHorizons.forEach((dimension, path) -> {
                File folder = new File(path);
                if (folder.exists()) SAVES.folders.put(dimension, folder);
                else FlashForge.LOGGER.warn("LOD folder for {} no longer exists: {}", dimension, path);
            });
            DhApi.overrides.bind(IDhApiSaveStructure.class, SAVES);
            bound = true;
            FlashForge.LOGGER.info("Distant Horizons is read-only for this replay, with {} LOD folders", SAVES.folders.size());
        }

        static void unbind() {
            if (!bound) return;
            bound = false;
            DhApi.overrides.unbind(IDhApiSaveStructure.class, SAVES);
        }

        static void collect(Map<String, String> into) {
            if (!DhApi.Delayed.worldProxy.worldLoaded()) return;
            for (IDhApiLevelWrapper level : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
                File folder = level.getDhSaveFolder();
                if (folder != null) into.put(level.getDimensionName(), folder.getAbsolutePath());
            }
        }

        private static final class ReplaySaves implements IDhApiSaveStructure {
            final Map<String, File> folders = new java.util.HashMap<>();

            @Override
            public File overrideFilePath(File current, IDhApiLevelWrapper level) {
                return this.folders.get(level.getDimensionName());
            }
        }
    }
}
