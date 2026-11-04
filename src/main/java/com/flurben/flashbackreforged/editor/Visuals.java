package com.flurben.flashbackreforged.editor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-replay presentation settings. Everything here changes only what is drawn, never the recorded world. */
public final class Visuals {
    // GUI
    public boolean showHud = false;
    public boolean showChat = false;
    public boolean showBossBar = false;
    public boolean showTitles = false;
    public boolean showScoreboard = false;
    public boolean showTabList = false;
    public boolean showHotbar = false;
    public boolean showHand = false;
    public boolean showActionBar = false;

    // World
    public boolean renderBlocks = true;
    public boolean renderEntities = true;
    public boolean renderPlayers = true;
    public boolean renderParticles = true;
    public boolean renderSky = true;
    public boolean renderClouds = true;
    public boolean renderWeather = true;
    public boolean renderNametags = true;
    public boolean renderBlockOutline = false;
    public boolean renderBeaconBeams = true;
    public boolean renderWorldBorder = true;

    // Overrides
    public boolean overrideFov = false;
    public float fov = 70f;
    public boolean overrideTime = false;
    public int time = 6000;
    /** 0 = off, 1 = clear, 2 = rain, 3 = thunder, 4 = overcast, 5 = snow. */
    public int weather = 0;
    public boolean fullBright = false;
    public boolean disableFog = false;
    public boolean overrideFog = false;
    public float fogStart = 0f;
    public float fogEnd = 256f;
    public boolean overrideSkyColor = false;
    public float[] skyColor = {0f, 1f, 0f};
    public boolean overrideFogColor = false;
    public float[] fogColor = {0.7f, 0.8f, 1f};
    public boolean hideSpectators = false;
    public boolean noBob = true;

    // Compositing guides, editor only
    public boolean ruleOfThirds = false;
    public boolean centerGuide = false;
    public boolean showCameraPath = true;
    /** 0 = off, otherwise width / height. */
    public float aspectGuide = 0f;

    // How the game image uses the space between the panels: 0 = keep window shape, 1 = chosen shape, 2 = fill, 3 = behind the panels.
    public int sizing = 0;
    public float viewAspect = 16f / 9f;

    /** Whose position sounds are heard from, instead of the camera's. */
    public String audioSource;
    /** Entity UUIDs left out of exports. They stay visible in the editor, outlined in red. */
    public Set<String> hiddenEntities = new HashSet<>();

    // Entity filters: hidden player UUIDs, hidden entity types (by registry id), per-entity tweaks.
    public Set<String> hiddenPlayers = new HashSet<>();
    public Set<String> hiddenEntityTypes = new HashSet<>();
    public Set<String> hiddenNametags = new HashSet<>();
    public Set<String> glowingEntities = new HashSet<>();
    public Set<String> mutedEntities = new HashSet<>();
    public Set<String> hiddenCapes = new HashSet<>();
    public Set<String> noGlowEntities = new HashSet<>();
    public Set<String> hiddenParticles = new HashSet<>();
    /** Player UUID to the UUID whose skin and cape should be shown instead. */
    public Map<String, String> skinOverrides = new HashMap<>();
}
