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
| Weather overrides: clear, rain, thunder, overcast, snow | Overcast and snow verified, including in TFC under shaders |
| Audio source entity | Verified: sounds are heard from the chosen entity. Listening to the result wasn't checked |
| `/hide` and `/show` | Verified by running the commands; hidden entities are outlined in red and left out of exports. Opening chat with the keyboard in a replay wasn't exercised |
| Entity popup | Finding the entity at a point and the popup itself verified; the real right-click wasn't exercised |
| Replay thumbnails | Verified |
| Replay sorting (date, name, world, length, size) | Button shown; changing the sort wasn't exercised |
| Tips | Verified on request; showing automatically on open wasn't exercised |
| Game view sizing: change shape, fill, behind panels | Change shape at 1:1 and behind panels verified; fill and other shapes not exercised |
| Keyframe relative copy | The math is verified against worked examples; the menu and paste weren't exercised |
| Keyframe scaling | The math is verified; the drag handles and menu weren't exercised |
| Drag-select keyframes and per-track colors | Implemented, not exercised |
| Reset randomness on export | Repeat exports match closely, but not exactly (see below) |

Repeat exports of the same range with the same settings are not byte-identical. They differ by a few particle pixels: about
55 dB PSNR in TFG and 38 to 40 dB in the dev client's rainy world.

## Known gaps compared to Flashback

- **Upload to medal.tv and copy a link.** Flashback sends clips to a medal.tv address made for Flashback and identifies itself as Flashback. FlashForge doesn't pretend to be another mod to use it.
- **Bobby chunk support.** Modrinth lists Bobby for 1.20.1 on Fabric only, so there is no Forge version to integrate with or test.
- Modded login-phase payloads are not replayed.

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
- TFC decides rain and snow itself, which overrode FlashForge's weather settings. The overrides now run first, and only while one is set.
- The first frames of an export could zoom slightly, as a FOV value frozen during pause caught up. The viewer's FOV modifier is now fixed.

## Not tested

- Multiplayer servers: all recordings came from singleplayer worlds.
- Long recordings: the longest was 1200 ticks, a single segment.
- Recorded rain in a TFC world: TFC's weather ignores the test's `weather rain` command, so no recorded TFC rain has been replayed. Recorded rain replays correctly in the dev client.
- Other GPUs and operating systems.
- TFG's own shader pack `TerraFirmaGreg-Shaders-Complementary-3.1.6.zip`.
