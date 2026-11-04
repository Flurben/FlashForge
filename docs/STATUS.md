# FlashForge status

Tested on Minecraft 1.20.1, Forge 47.4.13, Windows 11, NVIDIA RTX 4070 Ti SUPER.

"Verified" below means the end-to-end self test ran the feature in the game and the result was checked by looking at its
screenshots or video frames, unless the row says otherwise. Nothing here has been used by a person playing normally yet.

## Test results

| Setup | Result |
| --- | --- |
| Dev client (Forge only) | Self test passes, 0 packet failures |
| TerraFirmaGreg 0.13.17 through Prism, TFC world | Self test passes, 6 packet failures (see below) |
| TFG + Distant Horizons 3.3.3 | Self test passes; LODs visible past render distance in a replay |
| TFG + DH + Complementary Unbound (Oculus) Self test passes; LODs and volumetric clouds render in a replay; export has normal brightness in every frame |

Entity types in the replay's final seconds matched the live game exactly in TFG, including TFC and TFG mobs, apart from the
replay's own viewer entity.

The remaining packet failures are one GTCEu packet (`SPacketNotifyCapeChange`) that throws a null pointer in GTCEu's own
handler when a player has no cape. FlashForge skips it and logs it. Whether it also throws in normal play wasn't checked.

## Features

| Feature | State |
| --- | --- |
| Recording: start, stop, pause, resume, cancel, auto-record, markers, indicator | Start and stop verified; the rest implemented, not exercised by the self test |
| 5-minute segments with snapshots | Implemented; test recordings were shorter than one segment |
| Replay browser: open, rename, delete, combine | Open and combine verified; rename and delete not exercised |
| Playback: play, pause, speed, seek both ways | Verified |
| Combined replays crossing the join | Verified |
| Free camera | Verified through keyframes; manual flying not exercised |
| Spectating a player with HUD, hotbar and hand | Verified |
| Keyframe editor and camera path | Verified |
| Visuals panel: hide entities and others | Hiding entities verified; other options implemented, not each checked |
| Video export (h264 + aac) with real audio | Verified, including under shaders and with Sound Physics Remastered |
| PNG export at higher resolution | Verified |
| Editor UI scale (`editorScale` in the client config) | Verified at 1.5; scales text only, panel sizes stay fixed |
| Simple Voice Chat recording and playback | Implemented; **not verified end to end** |

## Known gaps compared to Flashback

- Snow and overcast weather overrides
- Choosing which entity audio is heard from
- `/hide` command
- Right-click entity popup in the viewport
- Upload link, tips, replay thumbnails, replay sorting
- Keyframe relative copy, scale, and per-track colors
- Reset-RNG setting (the field exists but does nothing)
- Bobby chunk support
- Changing aspect ratio (only a guide overlay)
- Modded login-phase payloads are not replayed

## Differences from what was asked

- **Original code, not copied from Flashback.** Flashback's license says "All rights reserved" and "do not redistribute".
  A private mod doesn't change that, and only its author could give permission, so its source was used as a reference for
  behavior only.
- **FFmpeg is not bundled.** The mod uses FFmpeg already on the system, or the path set in the options.
- **Flashback's GitHub Issues are disabled**, so only ReplayMod's issues could be consulted.
- **Distant Horizons is not part of TFG.** DH 3.3.3 was added to the test instance.

## Compatibility fixes found through TFG

- Sound Physics Remastered redirects the same OpenAL call FlashForge used; hook changed so both apply. This was a crash at startup.
- DH 3.3.3 + Chunky crash when TFC searches for a spawn point in a new world. FlashForge holds DH's Chunky hook until Chunky has started.
- Mod payloads that refer to a player failed when applied before the world in a snapshot; failures are now retried at the end of the snapshot.
- Packets queued at the moment recording began were lost, which is more likely with large registries. These are now applied before the first snapshot.
- Shader exports came out black because the shader clock jumped backward when an export began. The clock is now continuous.

## Not tested

- Multiplayer servers: all recordings came from singleplayer worlds.
- Long recordings: the longest was 1200 ticks, a single segment.
- TFC weather: the test's `weather rain` command showed no rain in a TFC world, and it wasn't checked whether TFC overrides it. Rain itself replays correctly in the dev client.
- Other GPUs and operating systems.
- TFG's own shader pack `TerraFirmaGreg-Shaders-Complementary-3.1.6.zip`.
