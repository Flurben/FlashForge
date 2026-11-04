# FlashForge

A private Forge 1.20.1 mod for recording gameplay, replaying it with a free camera and keyframe editor, and exporting
video. Its feature set is modeled on [Flashback](https://github.com/Moulberry/Flashback) (Fabric).

**This is an independent implementation.** Flashback's license is "All rights reserved / do not redistribute", so no
Flashback source code is included here. Its source was used only as a reference for behavior.

## Status

Work in progress. See the feature table in [docs/STATUS.md](docs/STATUS.md).

## Building

Requires JDK 17.

```bash
./gradlew build
```

The mod jar is written to `build/libs/flashforge-<version>.jar`.

## Testing

`tools/selftest.ps1` launches the dev client and runs an end-to-end scenario from inside the game: create a world, record,
open the replay, play, and seek both ways. Results go to `run/flashforge-selftest.json`, with screenshots in
`run/flashforge-selftest/`.

`tools/prism-test.js` runs the same test inside a Prism Launcher instance holding a real modpack. It copies the built jar
and Distant Horizons into the instance's mods folder, so both stay there afterwards.

```bash
node tools/prism-test.js --scenario full --preset tfc:overworld --shader ComplementaryUnbound_r5.9.3.zip
```

## Design notes

- Recordings are directories in `flashforge/replays/`, holding gzip segments that each begin with a full state snapshot.
- `registries.nbt` stores Forge's numeric registry IDs from recording time. They vary per world, and replaying with the
  wrong map turns mobs into other mobs and shifts textures.
- `serverconfig.nbt` stores server-type mod configs, because a replay never performs the login handshake that would
  normally deliver them.
- Playback feeds packets to a real `ClientPacketListener` through a fake connection, so modded client handlers run
  normally. Each packet is isolated, so one failing modded packet is skipped rather than ending the replay.
