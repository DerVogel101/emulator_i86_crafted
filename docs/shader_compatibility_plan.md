**Proposed shader compatibility fix — 2026-10-19**

Implementation follow-through is documented in [shader_compatibility.md](shader_compatibility.md). The terrain emissive-flag experiment did not fix the reproduced night tint; the implementation uses the existing standard emissive pipeline. Native PBR+ casing recipes are not required for that compatibility path.

Planning only. No renderer, texture, shader configuration, or world changes are part of this document.

The target is readable, correctly colored animated screens and light strips with shaders off, LabPBR enabled, and default Integrated PBR+ enabled. Preserve power states, animation, resource-pack overrides, existing indicator behavior, and ordinary world lighting. Shader exposure, bloom, and tone mapping can change the final appearance; acceptance is removal of the reproduced pink/red lighting contamination, not identical screenshot RGB across shaders.

1. **Keep the font fix and isolate the remaining lighting issue.**

   The missing `minecraft:pipeline/text_grayscale_polygon_offset` program and the texture tint are separate failures. Keep the bitmap sign provider, its glyph checks, and the original Monocraft attribution to Idrees Hassan under SIL OFL 1.1. The shader work must not restore a TTF provider for Minecraft signs.

   The copied-world investigation reproduced the tint without corrupting the animation atlas. Removing model `light_emission` overrides restored the colors; disabling both colored-lighting systems also restored them. Removing those overrides alone is incomplete because it can make the surfaces dim in darkness. The evidence is in `build/texture-investigation/README.md` and its captures.

   Keep the powered blocks' real light level of 3 in `ModContent.java`. Surface emissivity and light emitted into the world must be handled separately.

2. **Prove one emissive surface before changing every model.**

   Use the copied failing world and one powered screen plus its rails. First test Fabric's existing `QuadEmitter.emissive(true)` / `MutableQuadView.emissive(true)` support, using the model-loading hooks already supplied by Fabric API. Restrict the change to the mod's intended emissive quads and remove their model lighting override. Preserve baked geometry, UVs, and Minecraft's animated atlas. Audit ambient occlusion and directional shading separately: the emissive flag alone does not disable them.

   This is a candidate, not a proven Iris fix. Full brightness can still be interpreted differently by a shader. The gate is correct color and readable glow in darkness, with the original colored-lighting settings enabled, in both LabPBR and default Integrated PBR+.

   If the terrain path still acquires the tint, reuse the standard emissive render type that already works for `ClientIndicators`, drawing the existing glowing submodel geometry with the animated block atlas. Do not duplicate the geometry in Java or create another animation clock. Render each luminous surface once; remove its corresponding ordinary-model surface from that path. This fallback must obtain powered decoration state from loaded block/chunk state, not transient indicator packets, and must handle reload, disconnect, chunk unloading, and resource replacement.

   Choose the first path that passes. Do not maintain two interchangeable rendering systems or add private Iris pipeline hooks. Verify depth, occlusion, and shadow behavior before rolling it out.

3. **Generate correct LabPBR materials from the existing artwork source.**

   Extend `tools/build_art.py`, which owns the models and textures. Audit every `glow=True` caller, including screens, rails, sign-port attachment lights, physical input segments, and glowing item geometry. Keep the current LED size, colors, activity pulses, and fade behavior.

   Generate RGBA `_s.png` maps using LabPBR 1.3: red is perceptual smoothness, green is dielectric F0 or a defined metal, blue is porosity/subsurface scattering, and alpha is emission. Use alpha 0 for authored nonemissive pixels and values through 254 for emission; 255 is the specification's ignored value, not maximum glow. Keep metalness off painted casing and display glass. Use explicit authored material regions rather than guessing emission from RGB brightness.

   Give luminous faces their own texture regions where shared atlas UVs would otherwise make unlit surfaces glow. Start with restrained surface properties. For `_n.png`, use the LabPBR DirectX normal convention, valid material AO, and a flat/no-displacement baseline wherever there is no intended detail. Do not invent deep POM relief on geometry that already models the shape.

   Animated auxiliary maps must match the albedo's frame dimensions, order, timing, and interpolation: currently 16 frames, frametime 3, interpolation enabled. Generate their `.mcmeta` files from the same animation definition. Check interpolation and mipmaps for emission leaking into dark pixels or neighboring materials. Confirm there is one effective emission contribution, including if the emissive-render-type fallback is selected.

4. **Support Integrated PBR+ explicitly.**

   Integrated PBR+ is shader-owned material logic, not a second texture-map standard. Default `IPBR+ Only` does not automatically consume LabPBR emission maps. Complementary also has a supported `labPBR > IPBR+` emissive mode (`IPBR_EMISSIVE_MODE=3`), which reads the LabPBR emission channel while retaining Integrated PBR+ for other materials. This mechanism exists in the installed Complementary/Euphoria code and the official Complementary source.

   Require the chosen mod-side emissive path to remain readable and free of the reported tint in default `IPBR+ Only`. Document `labPBR > IPBR+` as the supported option for the authored emission masks. Never silently change a player's shader options. This option imports emission; it does not make Integrated PBR+ use all LabPBR roughness and normal data.

   For native PBR+ material treatment, prepare precise powered/unpowered block-state mappings and any required material handler for the shader maintainers. Test the existing material categories first. Do not classify the whole machine as a colored lamp or metal block if that makes casing glow, changes screens, or misrepresents its non-full geometry. Euphoria's mappings also affect colored-light occlusion, so verify light leaking around the actual shapes.

5. **Make the long-term dependency clear and maintainable.**

   Target the official Euphoria `propertiesFiles` contribution workflow for compatible existing material rules, including its `addedMods.md` documentation. If custom per-surface behavior is needed, it also requires a shader material implementation; adding a block ID alone is insufficient. Stock Complementary needs its own accepted integration: Euphoria's property IDs are explicitly not interchangeable with stock Complementary's IDs.

   Keep the generic emissive fix functional independently of upstream adoption. Native material refinements should be upstream-maintained where possible, with stable mod registry IDs and explicit state predicates. Record the first supported shader release and the versions actually tested. Full native PBR+ support is complete only when the required rule is available and verified, not merely when an upstream proposal exists.

   Do not rewrite installed shader ZIPs, copy an entire shader into the mod, persist numeric internal material IDs in worlds, or identify shader features from pack filenames. Supplemental Patches offers a documented resource-pack extension, but it is a separate dependency; its Minecraft 26.3 compatibility has not been established here. It is not required by this plan.

   Add the focused compatibility scene and asset checks to the verification used when Minecraft, Fabric, Iris, Sodium, or the supported shader version changes. Pin a reproducible baseline and test an upgrade before advertising it. No integration can guarantee compatibility with every future shader rewrite; the durable fallback is the mod's tested standard emissive path.

6. **Verify the complete behavior before release.**

   Extend the existing resource/geometry checks and `ArtworkClientGameTest` rather than creating another test framework. Reuse the copied-world reproduction because the simple gallery alone did not reproduce the tint.

   | Configuration | Required proof |
   | --- | --- |
   | Fabric without Iris/Sodium | Correct dark-scene glow, animation, and state transitions |
   | Sodium, shaders off | Same behavior; no new rendering dependency |
   | Iris + stock Complementary, LabPBR | Correct emission masks and material separation |
   | Iris + stock Complementary, default Integrated PBR+ | Correct color and readable emissive surfaces |
   | Iris + Complementary/Euphoria, default Integrated PBR+ | Correct color with colored lighting enabled |
   | Complementary/Euphoria, LabPBR and `labPBR > IPBR+` | Correct animated emission and intended shader interactions |

   Cover day/night, hostile colored lighting in the reproduced scene, powered/unpowered/active states, all facings and sign mounts, representative animation frames and interpolation, near/far views, and repeated shader toggles. Test resource reload, a texture-pack override, reconnect, chunk reload, and item/hand rendering where applicable. Check for duplicate faces, z-fighting, glow through walls, incorrect shadows, washed-out casing, and missing resources/program errors. Recheck bitmap signs and packaged font attribution.

   Asset checks should catch invalid channel values, mismatched animation metadata, missed glowing model families, and nonemissive atlas regions gaining emission. Capture before/after images from the same copied scene. If a dynamic pass is necessary, compare representative machine-heavy scenes for frame-time and allocation regressions; avoid adding one draw call per machine.

**Implementation order:** one-surface rendering proof → generator/material changes and complete caller coverage → supported PBR+ emission behavior → native upstream material contribution → full compatibility matrix. The core fix can stand on its own; native PBR+ refinements need a separately verified delivery path.

**Research references, checked 2026-10-19:**

- [LabPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard), including emission alpha and normal encoding.
- [Complementary's custom emission implementation](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/lib/materials/materialMethods/customEmission.glsl) and [setting names](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/lang/en_US.lang).
- [Euphoria's official mod compatibility workflow](https://github.com/EuphoriaPatches/propertiesFiles/blob/main/README.md) and its fragmented `blockProperties` library.
- [Supplemental Patches extension documentation](https://supplemental-patches.pages.dev/tutorials/getting_started), considered but not selected as a dependency.
- The installed Fabric renderer source documents `emissive(boolean)` as the preferred emissive interface and warns that shading/AO still apply. The installed Iris/Sodium/Complementary versions remain the runtime authority; upstream default branches can target other Minecraft versions.

Not implemented or runtime-tested in this planning step. Remaining risks are shader interpretation of emissive geometry, resource-pack material mismatches, and the availability/maintenance of native upstream PBR+ rules.
