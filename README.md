# CWI Worldgen

CWI Worldgen generates the wasteland Overworld for **Create Wasteland Industry**
on Minecraft 1.21.1 / NeoForge. It is a companion to the CWI modpack and requires
its registered blocks, assets and authored structures.

## Features

- Wasteland terrain with compact mountain ranges, local landforms, caves and
  mineral deposits.
- Rivers, valleys, lakes and ponds, with water currents that move boats,
  creatures and dropped items.
- Sparse ash and burnt trees rooted in depleted soil. Ash-dirt patches preserve
  stone and replace only normal or ashen depleted dirt.
- Background searches for landforms, biomes and structures through `/locate`.
- Configurable current strength and flowing-water audio.

## Usage

Install one CWI Worldgen JAR alongside the required CWI pack content, then restart
Minecraft. Select **CWI Wasteland** when creating a world. Dedicated servers use
`level-type=cwi_worldgen:wasteland`.

Existing worlds retain their saved terrain settings, and updates do not rewrite
generated chunks. Use a new world for the latest terrain settings.

Operators can use:

```text
/locate landform <type> [range]
/locate biome <id or #tag> [range]
/locate structure <id or #tag> [range]
/cwiworldgen landform list
/cwiworldgen landform here
/cwi_currents help
```

Searches default to **8192 blocks**, accept **64–32768 blocks**, and have no total
time limit. Landform results identify confirmed sampled locations; they do not
prove the nearest matching block. Large searches can take time, and structure
searches may partly generate candidate chunks.

Current settings use the normal NeoForge server and client configuration files.
`/cwi_currents` provides operator controls for inspecting and editing current
metadata in existing water.

## Requirements

- Java **21**, Minecraft **1.21.1**, and NeoForge **21.1.264 or later in 21.1**.
- CWI Core and CWI pack content, KubeJS, Create, Applied Energistics 2,
  Create Connected, Create Deco, Create Diesel Generators, Farmer's Delight,
  Functional Storage, Miner's Delight, Sophisticated Backpacks and TFMG.

See [loader metadata](src/main/resources/META-INF/neoforge.mods.toml) for exact
dependency versions. Dependency JARs and external pack content are not bundled.

## License

Original CWI-authored code is licensed under the **GNU General Public License,
version 3 only** (**SPDX: GPL-3.0-only**). See [LICENSE](LICENSE).

Third-party code, adaptations and assets retain their own terms and attribution.
See [LICENSE_SCOPE.md](LICENSE_SCOPE.md) and the existing
[third-party notices](src/main/resources/assets/cwi_worldgen/THIRD_PARTY_NOTICES.txt).
The combined source and JAR currently have unresolved third-party redistribution
permissions; the GPL grant for original CWI code does not resolve them.

Build and contribution information is in [CONTRIBUTING.md](CONTRIBUTING.md).
