"""Generate the saved sound set through the running Stable Audio Gradio API.

Adapted from the app skill's generate_sound_set.py; sources are cached for reruns.
"""
import argparse
import hashlib
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path


def request(url, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "art/audio/machine-sounds")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    sources = args.output / "sources"
    sources.mkdir(parents=True, exist_ok=True)
    specs = json.loads((args.output / "set-definition.json").read_text(encoding="utf-8"))
    info = json.loads(request(base + "/gradio_api/info"))
    (args.output / "api-info.json").write_text(json.dumps(info, indent=2), encoding="utf-8")
    params = info["named_endpoints"]["/generate"]["parameters"]
    common = " Isolated close-miked studio sound effect for the same compact vintage computer. Quiet background, dry, no room reverb, no music, no speech, no hiss, no exaggerated bass. The action begins immediately."
    total = sum(len(s["durations"]) for s in specs.values())
    done = 0
    for asset, spec in specs.items():
        for variant, target in enumerate(spec["durations"], 1):
            name = f"{asset}_{variant:02d}"
            destination = sources / (name + ".wav")
            metadata = sources / (name + ".json")
            variant_spec = spec.get("variant_overrides", {}).get(str(variant), {})
            overrides = {"prompt": variant_spec.get("prompt", spec["prompt"]) + common, "negative_prompt": "",
                         "seconds_total": spec["source_seconds"], "seed": variant_spec.get("seed", spec["seed"] + variant),
                         "file_format": "wav", "file_naming": "verbose", "cut_to_seconds_total": True}
            if destination.exists() and metadata.exists():
                saved = json.loads(metadata.read_text(encoding="utf-8"))
                if saved["overrides"] != overrides or saved["sha256"] != hashlib.sha256(destination.read_bytes()).hexdigest():
                    raise ValueError(f"{name}: cached source does not match saved request; move it aside before regenerating")
                if saved["target_seconds"] != target:
                    saved["target_seconds"] = target
                    metadata.write_text(json.dumps(saved, indent=2), encoding="utf-8")
                done += 1
                print(f"{done}/{total} cached {name}", flush=True)
                continue
            values = [overrides.get(p["parameter_name"], p["parameter_default"]) for p in params]
            # This app version also requires the unadvertised dist_shift_state input.
            values.append([])
            payload = {"data": values}
            start = time.monotonic()
            job = json.loads(request(base + "/gradio_api/call/generate", payload))
            events = request(base + "/gradio_api/call/generate/" + job["event_id"]).decode()
            match = re.search(r"event: complete\r?\ndata: ([^\r\n]+)", events)
            if not match:
                raise RuntimeError(name + ": " + events[-3000:])
            result = json.loads(match.group(1))
            audio_url = urllib.parse.urljoin(base, result[0]["url"])
            audio = request(urllib.parse.quote(audio_url, safe=":/?=&%"))
            if audio[:4] != b"RIFF" or len(audio) < 44:
                raise ValueError(f"{name}: response is not a WAV")
            destination.write_bytes(audio)
            metadata.write_text(json.dumps({"asset": asset, "variant": variant,
                "target_seconds": target, "model": "Stable Audio 3 Small SFX",
                "overrides": overrides, "request": payload, "event_id": job["event_id"],
                "sha256": hashlib.sha256(audio).hexdigest()}, indent=2), encoding="utf-8")
            done += 1
            print(f"{done}/{total} generated {name} ({time.monotonic()-start:.1f}s)", flush=True)


if __name__ == "__main__":
    main()
