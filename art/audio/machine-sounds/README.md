# Generated machine sound set

All 16 machine cues use 27 original clips generated with the local **Stable Audio
3 Small SFX** app through its Gradio API on 2026-10-26. There are no vanilla sound
aliases in this set. Playback timing, pitch, gains, subtitles, and the Blocks
volume slider are unchanged.
Keyboard overflow also reuses the generated fault clip as a UI notification.

Model: `cocktailpeanut/stable-audio-3-small-sfx`, cached revision
`84c4bc8e3281c5bc6d6f09525fec1c5c5fd411ab`. Generation used the app defaults
(8 steps, CFG 1, pingpong sampler), fixed per-variant seeds, and no input audio.
The control workflow came from the Pinokio skill and the app's saved
`api-stable-audio-3-small` skill; no launcher changes were needed.

`set-definition.json` contains prompts, base seeds, source lengths, final clip
lengths, and peak targets. Each file in `sources/` has its original 16-bit WAV
and JSON with the complete resolved API request and WAV SHA-256. `api-info.json`
captures the advertised API contract. `asset-manifest.json` records the decoded
game clips' lengths, channels, levels, and fan seam measurements.

The shipped clips are mono 48 kHz Ogg Vorbis in
`src/main/resources/assets/emulator_i86_crafted/sounds/machine/`. Short cues have
quiet edges so activity can repeat at the existing cooldowns. The four-second
fan uses an overlapping splice rather than a fade to silence at each repeat.
Frequently repeated cues have two variants; fan, startup, shutdown, fault, and
ROM completion have one each.

## Regenerate and review

1. Use the Pinokio skill to find the **Small SFX** app and confirm its ready URL.
2. Run `python tools/generate_machine_sounds.py --base-url <ready-url>` from the
   project root. Existing sources with matching prompts, seeds, and hashes are
   reused. To generate a replacement, move its WAV and JSON aside first.
3. Run `python tools/render_machine_sounds.py --ffmpeg <ffmpeg-path>` with an
   existing Python environment containing `numpy` and `soundfile` (the Stable
   Audio app environment provides both). This performs offline editing only.
4. Run `python -m unittest discover -s tools -p test_render_machine_sounds.py`
   with that same environment, and `node tools/verify_resources.mjs`.

Fixed sources and processing make subsequent renders repeatable. Fresh inference
with the saved seed also depends on the same model, app, and compute environment;
the preserved WAVs are the reference for exact sound reproduction.

The renderer creates `build/sound-demo/index.html`, a labeled 31-second reel of
all variants, a 22-second running-rack mix, individual auditions, a three-repeat
fan test, cue sheets, and validation results. The demo uses decoded game OGGs at
representative game pitches. Individual auditions are normalized for listening;
the rack mix preserves the relative playback gains with a labeled common boost.
Distance attenuation, the game mixer, and the user's volume settings still
affect the in-game result.
