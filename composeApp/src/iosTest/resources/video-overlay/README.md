Synthetic native export regression fixtures; no user media.

`source.mp4` is a two-second, 120×160, 24fps FFmpeg test pattern with a 440Hz sine audio track:

```sh
ffmpeg -f lavfi -i testsrc2=size=120x160:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 -c:v libx264 -pix_fmt yuv420p -c:a aac source.mp4
```

`rotated.mp4` retains the same encoded frames/audio and has a verified 90° display matrix. Generate it with a FFmpeg version supporting `-display_rotation` (the older `-metadata rotate=90` command did not add a matrix on this machine):

```sh
ffmpeg -display_rotation:v:0 90 -i source.mp4 -map 0 -c copy rotated.mp4
ffprobe -v error -select_streams v:0 -show_entries stream_side_data=rotation -of json rotated.mp4
```

The probe must report `rotation: 90`. Do not assume the command created a rotated fixture without inspecting the result.

`overlay.png` is a generated RGBA 120×160 image: its top 24 rows are red (255,0,0,230); the remainder is transparent. Both fixtures were authored for this repository's tests.

On an Apple Silicon Mac with a booted simulator, run:

```sh
./gradlew :composeApp:iosSimulatorArm64Test --device <simulator-UDID> --tests '*IosVideoOverlayExporterTest*'
```

The Gradle task forwards the host resource directory via `SIMCTL_CHILD_SNAPVAULT_TEST_RESOURCES`. The test exercises the real encoder; Linux cross-compilation cannot run it.

Standalone native tests assert successful export, dimensions, audio tracks and retained sources. They do not assert rendered pixels: the standalone simulator executable produced green frames in this environment, unlike the app-hosted UI export. In-app output was checked separately with FFprobe/FFmpeg, including 90° rotation and a red-overlay negative control. Do not treat a standalone test pass as visual proof.

## HDR fixtures (added 2026-11-10)

`hdr-hlg.mp4` and `hdr-pq.mp4` are synthetic 120×160, 24fps, 2 s HEVC Main 10 clips (`yuv420p10le`, BT.2020 primaries/matrix, limited range) with the same 440Hz AAC track. HLG uses transfer `arib-std-b67`; PQ uses `smpte2084`. They carry no mastering-display/MaxCLL metadata. Generate and inspect (zsh users: use `${var}` braces):

```sh
ffmpeg -f lavfi -i testsrc2=size=120x160:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 \
  -vf "format=yuv420p10le,setparams=colorspace=bt2020nc:color_primaries=bt2020:color_trc=<arib-std-b67|smpte2084>:range=tv" \
  -c:v libx265 -tag:v hvc1 -pix_fmt yuv420p10le \
  -x265-params "log-level=error:colorprim=bt2020:transfer=<arib-std-b67|smpte2084>:colormatrix=bt2020nc:range=limited:repeat-headers=1" -c:a aac <name>.mp4
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,profile,pix_fmt,color_space,color_transfer,color_primaries,color_range -of json <name>.mp4
```

SHA-256 (also `docs/audits/evidence/native-2026-11-10/fixture-sha256.txt`):
- `hdr-hlg.mp4` `57e2c0a9bddaa5423c54e8567112a5838e1b038639186c61852e63de73b5a489`
- `hdr-pq.mp4` `9108eb1fbe9fe557b5a46c96cca0eb0058381dd56a55b820ecb6a1378ccb28f9`

 The probe must report `hevc / Main 10 / yuv420p10le / bt2020nc / bt2020` and the intended transfer. Container metadata alone proves nothing about what the exporter writes: judge only the *exported* file, and remember the standalone runner's green-frame limit above.
