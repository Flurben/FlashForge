# Performance

How 0.2.0 compares with 0.1.1, how that was measured, and what was tried and left out.

## How it was measured

`tools/perf.js` plays the same scripted session each time: a fresh world from a fixed seed, 30 mobs, block changes every second and particles several times a second. The player flies out for a minute, hovers for a minute, flies back and hovers again while recording. The run then opens the replay, plays it, makes ten seeks, and exports 600 frames of video and 60 PNGs at 1920x1080.

| Setup | Details |
| --- | --- |
| Machine | Windows 11, 24 threads, RTX 4070 Ti SUPER |
| Settings | Render distance 12, uncapped frame rate, 1600x900 window |
| Dev client | Forge and this mod only |
| TerraFirmaGreg (TFG) | 0.13.17, 296 mods, Distant Horizons 3.3.3, Complementary Unbound |

Each 0.1.1 figure comes from one run, with 1-minute segments unless the row says otherwise. Frame rates vary by about 5% between identical runs, so smaller differences mean nothing. These are measurements from one machine at one render distance, so the ratios won't carry over exactly.

## File size

0.1.1 made a snapshot every 5 minutes and 0.2.0 makes one every minute, so seeks have five times as many starting points. The sizes below compare each version at its own default.

| Recording | 0.1.1 | 0.2.0 | Change |
| --- | --- | --- | --- |
| TFG, 4-minute flight | 15.9 MB (calculated) | 13.6 MB | 14% smaller |
| Dev client, 4-minute flight | 10.8 MB (calculated) | 7.0 MB | 35% smaller |
| TFG, 10 minutes mostly hovering | About 19 MB (estimated) | 7.3 MB | About 60% smaller |
| TFG, each further idle minute | About 1.3 MB (estimated) | About 0.2 MB | About 85% smaller |

The figures labeled calculated or estimated weren't produced by running 0.1.1 at its default:

- **Calculated** means 0.1.1's own raw data from these runs, with the later snapshots removed and gzipped at the level it used.
- **Estimated** means its measured snapshot size of about 5.75 MB per 5 minutes plus its measured stream rate.

The savings are smallest when flying through new terrain, because new chunks have to be stored anyway. They grow with recording length. Almost all of a replay is chunk data, and in the TFG flight about 40% of those bytes were exact repeats.

## Hitches on the game thread

Worst single stall in TFG.

| When | 0.1.1 | 0.2.0 |
| --- | --- | --- |
| Starting a recording | 362 ms | 8 ms, plus slices of at most 4 ms |
| Each new segment | 79 ms | 2.7 ms |
| Saving a thumbnail | 149 ms | 1.1 ms |
| Stopping a recording | 123 ms | Done in the background |
| Moving to the next segment in playback | About 100 ms each | About 2 ms each |

## Seeking

| Measure | 0.1.1 | 0.2.0 |
| --- | --- | --- |
| Game thread time for ten seeks, TFG | 1933 ms | 1273 ms |
| Game thread time for ten seeks, dev client | 601 ms | 193 ms |
| Mod packets replayed per seek after 4 minutes, TFG | 28,194 | 5,967 |
| Small hop back, until terrain is ready, TFG | 449 ms | 90 ms |
| Long hop back to the same place, until terrain is ready, TFG | 5454 ms and 2896 ms | 2798 ms and 2349 ms |

Time until terrain is ready improved less evenly. Some hops were no better, and one forward hop went from 381 ms to 950 ms. That time depends on how many chunks must be rebuilt, and unchanged chunks can only be kept if they're in the same place. In this test's long moves, about 20% of snapshot chunks were kept.

The mod packets replayed by each seek also stop growing. 0.1.1 added about 5,000 a minute in TFG, with a size limit as the only cap. In 0.2.0, a 10-minute recording reached 6,379.

## Export

| Measure | 0.1.1 | 0.2.0 |
| --- | --- | --- |
| Render thread time reading each frame, TFG | 1.25 ms | 0.12 ms |
| Render thread allocation during video export, TFG | 694 MB/s | 138 MB/s |
| Video export frame rate, TFG | 106 fps | 110 fps |
| Video export frame rate, dev client | 203 fps | 248 fps |
| 60 PNGs at 1080p, TFG | 22.7 s | 6.7 s |
| 60 PNGs at 1080p, dev client | 13.1 s | 2.0 s |

TFG's video export barely gained, because most of its time goes to rendering with shaders. The phase also includes waiting for chunks.

## What didn't change

- **Frame rate while recording or playing:** the differences are within normal variation. Flight recordings showed the mod's code at under 0.5% of render thread CPU during recording even in 0.1.1, so there was little to gain.
- **Opening a replay in TFG:** usually about 2 seconds, or about 7 seconds in some runs. The extra 5 seconds is Distant Horizons waiting for its own thread pool in `setDhWorld` while switching worlds, seen in thread dumps. It isn't caused by this mod.
- **Joining a world in TFG:** up to a 5-second stall while the recipe viewer builds its index, also seen in 0.1.1 runs.

## What changed

- **Blob store:** large packets are stored once in `blobs.bin`, each compressed on its own, and segments refer to them by position.
- **Chunk versions:** a snapshot refers to a chunk's stored copy plus the small packets that changed it, rather than encoding it again. After the first snapshot of the 4-minute TFG run, 0 to 3 chunks per snapshot needed encoding, compared with 609 before.
- **Snapshots across frames:** chunks that do need encoding are spread over frames, with a budget of about 2 ms per frame. Entity state is still taken at one instant, because entity moves are relative.
- **One writer thread:** compression, file creation, registry and config saving, metadata and thumbnails all moved there. Segments now use deflate level 6 instead of level 1, at about 2% of a core at most on the dev client.
- **Mod packet journal:** exact repeats replace earlier copies, and each kind of packet keeps its newest 4,096.
- **Playback preparation:** neighboring segments are unpacked in the background, and a snapshot's chunks are unpacked in parallel.
- **Kept chunks:** seeks leave unchanged chunks in place, so the renderer has nothing to rebuild. Chunks with modded block entities keep their blocks and have those block entities restored as stored.
- **Skipping by packet id:** packets a seek would discard are no longer decoded first.
- **GPU thumbnails:** the graphics card shrinks the frame, and the result is collected after a fence.
- **Export copying:** the writer thread copies directly from mapped graphics memory, using reusable buffers. PNGs are compressed on several threads.
- **Smaller items:** the replay browser reads from disk in the background, and replay combining no longer blocks the game. Several per-frame and per-entity paths stopped allocating needlessly.

## Tried and left out

| Idea | Why not |
| --- | --- |
| zstd (aircompressor) | Ratio 3.3 against gzip's 5.8 on the same data |
| xz | About 15% smaller than deflate level 6, but unpacks about 5 times slower, which seeks would feel |
| Batching chunks before compressing | No gain: 20.25 MB against 20.31 MB. Deflate's 32 KB window is smaller than a chunk |
| A preset compression dictionary | No gain: 17.62 MB against 17.59 MB |
| Decoding packets on a background thread | Decoding was already only about 6% of seek time after the changes above |
| Skipping large mod packets on repeat seeks | Would save about 15 ms a seek, with a risk of leaving mods in the wrong state |
| Skipping unchanged player poses | Gzip already removes nearly all of that repetition, and playback relies on them each tick |

## Checking that the replay is still right

Most of these changes could produce a wrong world without any visible error, so the profiling run now checks for that. While recording, `WorldCheck` notes every block in the 81 chunks around the player, plus each entity's position, at 14 moments. Afterwards it seeks to each moment, backward and then forward, and compares.

| Setup | Blocks compared | Different | Entities compared | Missing | More than 2 blocks away |
| --- | --- | --- | --- | --- | --- |
| Dev client | 222,953,472 | 0 | 3,774 | 0 | 0 |
| TFG | 222,953,472 | 0 | 1,778 | 2 | 0 |

This doesn't check block entity contents or modded client state, so those could differ without it being noticed.

## Running it yourself

Rebuild, then run a session. Add `--jfr` for flight recordings, `--dump <folder>` for raw data, and `--no-check` to leave out the world check, which stalls the game briefly each time it notes the world:

```bash
node tools/perf.js prism out.json --no-check --shader ComplementaryUnbound_r5.9.3.zip
```

To read the results:

```bash
node tools/perf-summary.js out.json
```

```bash
node tools/perf-compare.js before.json after.json
```

`tools/JfrTop.java` summarizes flight recordings, and `tools/CodecBench.java` compares compressors on dumped data.
