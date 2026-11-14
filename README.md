# CWI Worldgen

CWI Worldgen generates the wasteland Overworld for Create Wasteland Industry
on Minecraft 1.21.1 and NeoForge 21.1.264. This repository contains the active
Worldgen source, including the classic terrain style, compact mountain ranges,
watershed rivers, local landforms, caves, geology and asynchronous locate commands.
The mod ID is `cwi_worldgen`; worldgen resources retain their canonical `cwi:` IDs.

Version **0.3.21.15** prevents ash-dirt patches from replacing stone. Patches still
generate on normal and ashen depleted dirt. New worlds use saved generation
profile **22**, introduced in 0.3.21.14 with smaller mountain ranges, reduced
mountain relief and corrected dry river-bank fill. Individual mountain source
wavelengths retain their profile-21 settings. Standing ash and burnt trees are
sparser and require depleted soil in profiles 21 and 22.

Existing worlds retain their serialized terrain profiles. The ash-dirt material
fix applies to newly generated chunks in all profiles; updating the mod does not
rewrite existing chunks. Use a fresh world to select profile 22 from an older
profile. Install only one Worldgen JAR and restart Minecraft after replacing it.

This is a companion to the CWI pack. CWI Core, KubeJS, Create and the other
dependencies declared in [loader metadata](src/main/resources/META-INF/neoforge.mods.toml)
are required at runtime. The pack supplies its own registered blocks, assets and
authored structures. These external dependencies and pack files are not bundled
in this source repository. This mod is not a standalone replacement for that
content.

Build with Java 21 and the included Gradle wrapper:

```sh
./gradlew build
```

The output is `build/libs/cwi_worldgen-<mod_version>.jar`; the version comes from
`gradle.properties`. Generated runtime resources are committed. The normal build
compiles the native test sources but does not launch Minecraft or execute
GameTests. Native integration tests require a separately prepared CWI content
fixture; no dependency JARs, runtime configuration or saves are included here.

Operator searches use `/locate landform <type> [range]`,
`/locate biome <biome> [range]` and `/locate structure <structure> [range]`.
Ranges default to 8192 blocks and accept 64–32768 blocks, with no total time
limit. Landform results are confirmed sampled anchors, not an exhaustive proof
of the nearest block. Some marine searches still require expensive terrain
calculations. `/cwiworldgen landform list` and `here` list and identify landforms.

Source and release publication preserve the original third-party notices and
licenses. See [third-party notices](src/main/resources/assets/cwi_worldgen/THIRD_PARTY_NOTICES.txt)
and the [Create code license](src/main/resources/assets/cwi_worldgen/CREATE_CODE_LICENSE.txt).
Original authors retain their rights; repository access does not grant additional
redistribution rights.
