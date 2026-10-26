"""Render the saved Stable Audio sources and a labeled, repeatable listening demo.

Requires numpy, soundfile and ffmpeg; no model inference runs here.
"""
import argparse
import csv
import hashlib
import html
import json
import math
import subprocess
import wave
from pathlib import Path

import numpy as np
import soundfile as sf

RATE = 48000
ROOT = Path(__file__).resolve().parents[1]
PLAYBACK = {
    "fan": (.02, .8), "start": (.12, 1.2), "stop": (.10, .7),
    "fault": (.09, .8), "burn": (.06, 1.2), "relay": (.035, 1.2),
    "disk": (.055, 1.3), "terminal": (.035, 1.4), "sign": (.03, 1.3),
    "key": (.035, 1.5), "card_read": (.055, 1.5), "card_write": (.07, 1.5),
    "card_feed": (.10, 1.1), "paper": (.08, 1.3), "clock": (.018, .9),
    "link": (.025, 1.6),
}


def decode(ffmpeg, path, fan=False):
    raw = subprocess.check_output([
        ffmpeg, "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(RATE),
        "-af", "highpass=f=250,lowpass=f=2000" if fan else "highpass=f=60,lowpass=f=11000",
        "-f", "f32le", "pipe:1",
    ])
    samples = np.frombuffer(raw, "<f4").astype(float)
    if not len(samples) or not np.isfinite(samples).all():
        raise ValueError(f"Invalid audio: {path}")
    return samples


def normalize(samples, peak_db=-3):
    peak = float(np.max(np.abs(samples)))
    if peak < 1e-7:
        raise ValueError("Silent source")
    return samples * (10 ** (peak_db / 20) / peak)


def render(samples, seconds, fan=False, peak_db=-3):
    """Select one event, or overlap steady fan audio into a gapless loop."""
    count = round(seconds * RATE)
    if count < 64:
        raise ValueError("Clip is too short")
    samples = samples - np.mean(samples)
    if fan:
        overlap = min(round(.4 * RATE), count // 4)
        # The loop seam lands inside the unmodified source, away from the splice.
        available = len(samples) - count - overlap
        if available < 0:
            raise ValueError("Fan source must exceed loop length by the crossfade duration")
        start = min(round(.3 * RATE), available)
        segment = samples[start:start + count + overlap]
        phase = (.5 - .5 * np.cos(np.linspace(0, np.pi, overlap))) * (np.pi / 2)
        fade_in, fade_out = np.sin(phase), np.cos(phase)
        samples = np.concatenate((segment[overlap:-overlap],
                                 segment[-overlap:] * fade_out + segment[:overlap] * fade_in))
    else:
        envelope = np.array([np.sqrt(np.mean(samples[i:i + 240] ** 2))
                             for i in range(0, len(samples), 240)])
        active = np.flatnonzero(envelope > max(envelope.max() * .07, 1e-6))
        if not len(active):
            raise ValueError("Silent source")
        if seconds >= .5:
            start = max(0, int(active[0]) * 240 - 240)
        else:
            start = max(0, int(np.argmax(np.abs(samples))) - min(round(.02 * RATE), count // 10))
        samples = np.pad(samples[start:start + count], (0, max(0, start + count - len(samples))))
        onset = min(round(.004 * RATE), count // 8)
        tail = min(round(.035 * RATE), count // 4)
        samples[:onset] *= .5 - .5 * np.cos(np.linspace(0, np.pi, onset))
        samples[-tail:] *= .5 + .5 * np.cos(np.linspace(0, np.pi, tail))
        samples[0] = samples[-1] = 0
    samples = normalize(samples, peak_db)
    assert len(samples) == count and np.isfinite(samples).all()
    assert np.max(np.abs(samples)) < 1
    return samples


def pitched(samples, pitch):
    positions = np.arange(0, len(samples), pitch)
    return np.interp(positions, np.arange(len(samples) + 1), np.append(samples, samples[0]))


def write_wav(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.rint(np.clip(samples, -.9999, .9999) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm.tobytes())


def encode_ogg(ffmpeg, path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        ffmpeg, "-v", "error", "-y", "-f", "f32le", "-ar", str(RATE), "-ac", "1",
        "-i", "pipe:0", "-c:a", "libvorbis", "-q:a", "5", "-map_metadata", "-1", str(path),
    ], input=samples.astype("<f4").tobytes(), check=True)


def make_demo(sounds, specs, output):
    output.mkdir(parents=True, exist_ok=True)
    timeline = []
    reel = []
    cards = []
    position = 0
    for (asset, variant), samples in sounds.items():
        name = f"{asset}_{variant:02d}"
        gain, pitch = PLAYBACK[asset]
        audition = normalize(pitched(samples, pitch), -6)
        write_wav(output / "repeats" / f"{name}.wav", np.tile(audition, 3))
        if asset == "fan":
            audition = np.tile(audition, 2)
        write_wav(output / "clips" / f"{name}.wav", audition)
        timeline.append({"demo": "all-sounds", "time_seconds": round(position / RATE, 3),
                         "label": name, "gain": gain, "pitch": pitch})
        reel.extend((audition, np.zeros(round(.45 * RATE))))
        position += len(audition) + round(.45 * RATE)
        prompt = html.escape(specs[asset].get("variant_overrides", {}).get(str(variant), {}).get("prompt", specs[asset]["prompt"]))
        cards.append(f'<article><h3>{html.escape(name)}</h3><p>{prompt}</p>'
                     f'<p>Game pitch {pitch:g}; gain {gain:g}. Audition peak −6 dBFS.</p>'
                     f'<audio controls preload="none" src="clips/{name}.wav"></audio>'
                     f'<p>Three back-to-back repetitions:</p><audio controls preload="none" src="repeats/{name}.wav"></audio></article>')
    write_wav(output / "machine-sounds-demo.wav", np.concatenate(reel))

    mix = np.zeros(22 * RATE)
    fan = pitched(sounds[("fan", 1)], PLAYBACK["fan"][1])
    loop = np.tile(fan, math.ceil(19 * RATE / len(fan)))[:19 * RATE] * .02
    ramp = round(.25 * RATE)
    loop[:ramp] *= np.linspace(0, 1, ramp)
    loop[-ramp:] *= np.linspace(1, 0, ramp)
    mix[:len(loop)] += loop
    timeline.append({"demo": "rack", "time_seconds": 0, "label": "fan loop, power on through 19s", "gain": .02, "pitch": PLAYBACK["fan"][1]})

    def place(asset, at, variant=1, gain=None, pitch=None):
        default_gain, default_pitch = PLAYBACK[asset]
        gain = default_gain if gain is None else gain
        pitch = default_pitch if pitch is None else pitch
        samples = pitched(sounds[(asset, variant)], pitch) * gain
        start = round(at * RATE)
        mix[start:start + len(samples)] += samples
        timeline.append({"demo": "rack", "time_seconds": round(at, 3),
                         "label": f"{asset}_{variant:02d}", "gain": gain, "pitch": pitch})

    place("relay", .4, gain=.12, pitch=.8)
    place("start", .4)
    for i in range(5):
        place("disk", 1.6 + i * .3, i % 2 + 1, pitch=.8 if i % 2 else 1.3)
        place("key", 3.5 + i * .15, i % 2 + 1)
        place("terminal", 5 + i * .3, i % 2 + 1)
        for asset, at in (("card_read", 7.5), ("card_write", 9.5)):
            place(asset, at + i * .2, i % 2 + 1)
            place("relay", at + i * .2, i % 2 + 1, gain=.025, pitch=1.7)
    place("card_feed", 11.2)
    place("paper", 11.2)
    place("card_feed", 12, 2, pitch=.85)
    place("paper", 12, 2)
    for i in range(2):
        place("sign", 12.8 + i * .6, i + 1)
        place("link", 14 + i * .8, i + 1)
        place("clock", 15.7 + i * 1.5, i + 1, pitch=.9 if i == 0 else 1.2)
    place("burn", 16.4)
    place("fault", 18)
    place("stop", 19.2)
    # One common gain preserves the actual game balance between all layers.
    mix_gain = 10 ** (14 / 20)
    if np.max(np.abs(mix)) * mix_gain > .9:
        mix_gain = .9 / np.max(np.abs(mix))
    write_wav(output / "rack-demo.wav", mix * mix_gain)
    fan_test = normalize(np.tile(fan, 3), -9)
    write_wav(output / "fan-loop-test.wav", fan_test)
    with (output / "cue-sheet.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=timeline[0].keys())
        writer.writeheader()
        writer.writerows(timeline)
    (output / "cue-sheet.json").write_text(json.dumps(timeline, indent=2) + "\n", encoding="utf-8")
    page = '''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Machine sound audition</title>
<style>body{font:16px/1.5 system-ui;max-width:1000px;margin:40px auto;padding:0 20px;background:#151a20;color:#e4edf2}h1,h2,h3{line-height:1.2}audio{width:100%}article{padding:18px;background:#222b35;border-radius:10px}#clips{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}p{color:#bfcbd5}a{color:#8fd1ff}</style>
<h1>Generated machine sounds</h1><p>Stable Audio 3 Small SFX • mono 48 kHz Vorbis game assets • fixed seeds, deterministic editing.</p>
<h2>Every clip</h2><p>All variants at their typical game pitch, raised to the same audition peak. Follow the labels below or the <a href="cue-sheet.csv">cue sheet</a>.</p><audio controls src="machine-sounds-demo.wav"></audio>
<h2>Busy rack · 22 seconds</h2><p>Startup → disk → keyboard → terminal → reader → writer → card feed → sign → link → clock → burn → fault → shutdown. Original game gains and pitches; one common listening boost of BOOST dB. Spatial attenuation and game master volume are omitted.</p><audio controls src="rack-demo.wav"></audio>
<h2>Fan seam test</h2><p>Three consecutive loops at game pitch 0.8, raised to −9 dBFS. The loop contains a smooth equal-power crossfade, with no gap between repetitions.</p><audio controls src="fan-loop-test.wav"></audio>
<h2>Individual auditions</h2><section id="clips">CARDS</section></html>'''
    page = page.replace("BOOST", f"{20 * math.log10(mix_gain):.1f}").replace("CARDS", "\n".join(cards))
    revision = hashlib.sha256(json.dumps(PLAYBACK, sort_keys=True).encode() + b"".join(samples.tobytes() for samples in sounds.values())).hexdigest()[:12]
    page = page.replace('.wav"', f'.wav?v={revision}"')
    (output / "index.html").write_text(page, encoding="utf-8")
    return {"rack_peak_dbfs": 20 * math.log10(float(np.max(np.abs(mix * mix_gain)))),
            "listening_boost_db": 20 * math.log10(mix_gain), "all_sounds_seconds": position / RATE,
            "fan_loop_seconds": len(fan) / RATE, "fan_test_seconds": len(fan_test) / RATE}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--source", type=Path, default=ROOT / "art/audio/machine-sounds")
    parser.add_argument("--demo", type=Path, default=ROOT / "build/sound-demo")
    args = parser.parse_args()
    specs = json.loads((args.source / "set-definition.json").read_text(encoding="utf-8"))
    if set(specs) != set(PLAYBACK):
        raise ValueError("Sound definition must contain all 16 registered events")
    assets = ROOT / "src/main/resources/assets/emulator_i86_crafted/sounds/machine"
    sounds, manifest = {}, []
    for asset, spec in specs.items():
        for variant, seconds in enumerate(spec["durations"], 1):
            name = f"{asset}_{variant:02d}"
            source = args.source / "sources" / f"{name}.wav"
            samples = render(decode(args.ffmpeg, source, asset == "fan"), seconds,
                             asset == "fan", spec.get("peak_db", -3))
            destination = assets / f"{name}.ogg"
            encode_ogg(args.ffmpeg, destination, samples)
            # Audition the decoded deliverable, including any Vorbis seam behavior.
            delivered, delivered_rate = sf.read(destination)
            assert delivered_rate == RATE and delivered.ndim == 1
            assert len(delivered) == len(samples)
            assert np.max(np.abs(delivered)) < 1
            sounds[(asset, variant)] = delivered
            entry = {"file": str(destination.relative_to(ROOT)).replace("\\", "/"),
                     "source": f"sources/{source.name}", "seed": spec.get("variant_overrides", {}).get(str(variant), {}).get("seed", spec["seed"] + variant),
                     "duration_seconds": len(delivered) / RATE, "sample_rate": RATE, "channels": 1,
                     "peak_dbfs": 20 * math.log10(float(np.max(np.abs(delivered)))),
                     "rms_dbfs": 20 * math.log10(float(np.sqrt(np.mean(delivered ** 2))))}
            if asset == "fan":
                entry["loop_boundary_step"] = abs(float(delivered[0] - delivered[-1]))
                entry["max_sample_step"] = float(np.max(np.abs(np.diff(delivered))))
                assert entry["loop_boundary_step"] <= max(.015, entry["max_sample_step"])
            manifest.append(entry)
            print(f"Rendered {name}: {entry['duration_seconds']:.3f}s", flush=True)
    (args.source / "asset-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    checks = make_demo(sounds, specs, args.demo)
    checks.update({"assets": len(manifest), "events": len(specs), "sample_rate": RATE,
                   "mono": True, "codec": "vorbis", "all_decoded_without_clipping": True})
    (args.demo / "validation.json").write_text(json.dumps(checks, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(checks, indent=2))


if __name__ == "__main__":
    main()
