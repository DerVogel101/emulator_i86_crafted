# Generated machine sound set

All 16 machine cues use 27 original clips generated with the local **Stable Audio
3 Small SFX** app through its Gradio API on 2026-10-26. There are no vanilla sound
aliases in this set. Playback timing, gains, subtitles, and the Blocks
volume slider are unchanged.
Keyboard overflow also reuses the generated fault clip as a UI notification.

Model: `cocktailpeanut/stable-audio-3-small-sfx`, cached revision
`84c4bc8e3281c5bc6d6f09525fec1c5c5fd411ab`. Generation used the app defaults
(8 steps, CFG 1, pingpong sampler), fixed per-variant seeds, and no input audio.
The control workflow came from the Pinokio skill and the app's saved
`api-stable-audio-3-small` skill; no launcher changes were needed.

`set-definition.json` contains prompts, base seeds, source lengths, final clip
lengths, and peak targets. Individual clips can override their prompt and seed in
`variant_overrides` while the other variants retain their original settings.
Each file in `sources/` has its original 16-bit WAV and JSON with the complete
resolved API request and WAV SHA-256. `api-info.json`
captures the advertised API contract. `asset-manifest.json` records the decoded
game clips' lengths, channels, levels, and fan seam measurements.

The shipped clips are mono 48 kHz Ogg Vorbis in
`src/main/resources/assets/emulator_i86_crafted/sounds/machine/`. Short cues use a 4 ms raised-cosine attack and up to 35 ms release, capped for
short transients, so repeated activity has gentle edges. The four-second fan uses
a centered excerpt to exclude the source startup/shutdown fades and a 400 ms
equal-power crossfade with smoothly eased gains. Decoded 100 ms RMS windows must
stay within a 3 dB total range. It
plays at a lowered pitch (0.775-0.85), with a 250 Hz high-pass and 2 kHz
low-pass filter to soften the airflow texture and remove low rumble.
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

The renderer creates `build/sound-demo/index.html`, a labeled reel of
all variants, a 22-second running-rack mix, individual auditions, a four-repeat
fan test, per-clip players with three back-to-back repetitions, cue sheets, and
validation results. The demo uses decoded game OGGs at
representative game pitches. Individual auditions are normalized for listening;
the rack mix preserves the relative playback gains with a labeled common boost.
Distance attenuation, the game mixer, and the user's volume settings still
affect the in-game result.
