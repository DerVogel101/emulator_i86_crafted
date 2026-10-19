# Shader materials and emissive machine faces

Machine screens and light strips render through Minecraft's standard emissive entity pipeline. They keep their baked model geometry, facing, UVs, and ordinary animated atlas textures. Painted casing and other nonluminous faces stay in the terrain renderer and receive normal world and shader lighting.

This removes the reproduced red/pink contamination with Complementary/Euphoria colored lighting enabled. The powered block light level remains 3. The bitmap sign font and Idrees Hassan's Monocraft attribution/OFL license are unchanged; the earlier Iris text-pipeline error is a separate issue covered by [sign_font.md](sign_font.md).

## Supported material modes

| Mode | Behavior |
| --- | --- |
| Shaders off | Screens and strips remain readable in darkness; casing uses ordinary world lighting. |
| LabPBR | Bundled `_s` material maps describe painted surfaces, exposed metals, and emission. `_n` maps provide a flat, unoccluded baseline without artificial displacement. |
| Integrated PBR+, default `IPBR+ Only` | Standard emissive faces retain their colors. Complementary supplies its normal procedural material effects for the casing. No custom shader patch is required. |
| Integrated PBR+, `labPBR > IPBR+` | Complementary additionally accepts LabPBR emission maps for render paths/materials that consume them. This does not import LabPBR roughness or normal maps into every Integrated PBR+ material. |

The standard emissive pass owns the luminous world faces in every mode. Each face is drawn once; shader emission maps are not added as another overlay. Speed-shard items retain their original full-bright crystal cores in the separate item pipeline.

No shader options are changed automatically. Shader exposure, bloom, tone mapping, and the real colored illumination around a machine can still affect the image. Casing responding to nearby red light is expected; a cyan display turning red is not.

## Implementation and maintenance

- `ClientEmissiveModels` wraps the mod's baked block models through Fabric's final wrapping phase. It separates the dedicated `light_*`, `display_*`, and `signal_cyan` texture faces from terrain geometry and batches their immutable meshes through the standard emissive render type.
- Chunk load/unload events and the client block-dirty notification maintain the set of machine positions. Rendering reads the current block state, so power, active state, and facing are not inferred from transient indicator packets. Only loaded, visible, powered/active machines submit luminous geometry; structural filler has none. There is no additional fixed distance cutoff for the formerly static decorative faces.
- Mesh reuse respects the source model's geometry key and block state. Resource reload creates new wrappers and atlas lookups. Ordinary resource packs can replace the existing texture IDs, including changing resolution and animation metadata. A custom model that replaces the luminous texture IDs with unrelated names needs an explicit material adaptation.
- With Sodium installed, its optional public `SpriteUtil` API marks visible emissive sprites active. This preserves automatic animation after those sprites leave terrain batches. Reflection keeps Sodium optional; an API mismatch produces a warning and must be caught during dependency-upgrade checks.
- No private Iris pipeline hooks, shader-pack filename checks, or rewriting of installed shader archives are used.

`tools/build_art.py` owns the generated artwork and LabPBR data. Emission is restricted to dedicated luminous textures; shared painted/casing regions remain nonemissive. Animated albedo, normal, and specular textures share frame size, order, timing, and interpolation metadata. LabPBR emission uses 0 for none and values through 254 for emission, since 255 is ignored by the standard. Exposed metal cells use their defined metal encoding; painted surfaces use dielectric F0.

Regenerate with `tools/build_art.py`, then run `tools/verify_art_geometry.py` and `node tools/verify_resources.mjs`. The Python tools require Pillow. The client artwork test also checks the rendered screenshots: newly placed machines gain visible screen/strip glow when powered, and the glow remains visible at night.

Native, hand-authored Integrated PBR+ recipes for individual casing materials are a separate shader-maintainer refinement. The tested standard emissive path supplies compatibility without waiting for those recipes. Adding generic colored-lamp or metal mappings for whole machines would misclassify their casing and is deliberately avoided. Euphoria's property IDs must not be copied into stock Complementary.

## Verification baseline

Target versions: Minecraft 26.3, Fabric API 0.163.0+26.3, Iris 1.11.7, Sodium 0.9.2, Complementary Unbound r5.9.3, and Euphoria Patches 1.10.5. Keep this matrix as a gate when updating those components.

Verification uses the native artwork gallery and an isolated copy of the originally failing world. It covers colored lighting at night, default Integrated PBR+, LabPBR, shader disable/re-enable, ordinary animation without manual frame advancement, and a live resource-pack override/reload. The override changes luminous texture resolution to force atlas UV changes. The original world and normal shader configuration are not test fixtures and are left untouched.

Local logs and before/after captures are under `build/shader-compatibility/`. Completed runs and their exact configurations are recorded in that directory's `README.md`.

References: [LabPBR specification](https://shaderlabs.org/wiki/LabPBR_Material_Standard), [Complementary emission handling](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/lib/materials/materialMethods/customEmission.glsl), [Euphoria mod-material contribution workflow](https://github.com/EuphoriaPatches/propertiesFiles/blob/main/README.md).
