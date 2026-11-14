# License Scope

Original CWI-authored code is licensed under GNU General Public License version
3 only (SPDX: **GPL-3.0-only**). The full license is in [LICENSE](LICENSE).
This grant does not relicense upstream code, adaptations of upstream material,
or separately licensed assets. Third-party material retains its applicable
terms and attribution.

This is a mixed-origin project. The original-code grant covers original CWI
source, tests and build code, subject to the exclusions and unresolved provenance
below. It is not a blanket GPL license for every repository file or JAR entry.

## Third-Party Code

Java paths in this section are relative to
`src/main/java/net/quiwh/cwiworldgen/`.

| Origin | Paths | Existing terms |
| --- | --- | --- |
| Create | `feature/LayeredDepositFeature.java`, `feature/LayeredDepositConfiguration.java` | MIT; preserve the bundled Create notice. |
| Darker Depths 2.1.5 | Files listed below | Upstream 1.20.1 source includes CC BY-NC 3.0; the older JAR metadata still says All Rights Reserved. Preserve attribution, identify changes and comply with the noncommercial restriction. |
| Geode Plus 1.2.5 | `feature/geode/GeodeFeature.java`, `feature/geode/CrystalSpikeFeature.java`, `feature/geode/EchoCrystalSpikeFeature.java`, `feature/geode/config/GeodeCrystalSpikeConfig.java` | CC BY-NC-SA 3.0, including its attribution, noncommercial and share-alike conditions. |
| Supplied terrain engine | `terrain/reference/**` and source-derived integration, including `terrain/ReferenceSpawnFinder.java` and `terrain/ReferenceRiverGasket.java` | No license was included in the supplied archive. TerraForged provenance labels do not establish a license grant. |

Darker Depths adaptations:

```text
carver/BlockCarver.java
carver/BlockCarverConfiguration.java
feature/CatacombsLavaLiningFeature.java
feature/CorrespondentLayersConfig.java
feature/CorrespondentLayersFeature.java
feature/GemstoneFeature.java
feature/HugeGlowshroomFeature.java
feature/LargeMoltenPillarFeature.java
feature/LavaVegetationPatchFeature.java
feature/PetrifiedBranchConfig.java
feature/PetrifiedBranchFeature.java
feature/RandomFloorPlacementConfig.java
feature/RandomFloorPlacementFeature.java
feature/SmallBoulderConfiguration.java
feature/SmallBoulderFeature.java
```

`block/FamilyBuddingBlock.java` follows Geode Plus crystal-growth behavior. Its
implementation provenance remains under review; it is excluded from the
original-code-only grant until that review is resolved. The modern spike helper,
`feature/geode/CrystalSpikePlacement.java`, also needs its derivation reviewed
before being classified as wholly original; it is excluded pending that review.

The geode implementation also follows vanilla Minecraft's geode algorithm.
The adaptation notice records the source used for this port; it does not claim
that Geode Plus originated every part of that algorithm.

## Assets And Data

Imported assets and data retain their upstream terms. Their presence or namespace
change does not transfer copyright or grant additional redistribution rights.

- Darker Depths resources retain their attribution. The upstream 1.20.1 source
  includes CC BY-NC 3.0, although the imported JAR metadata says All Rights
  Reserved; see the verified upstream source below.
- Geode Plus crystal resources retain CC BY-NC-SA 3.0 terms.
- Ad Astra resources, including the imported iron/steel plate and industrial
  lamp families, declare the Terrarium License. The supplied JAR did not contain
  its full terms; applicable permissions remain unverified.
- The original TFMG input declares MIT. Its actual copyright and license notice
  must be preserved before public redistribution of the imported resources.
- Other imported resources remain subject to their recorded provenance and
  applicable upstream rights; they are not relicensed by the root LICENSE.

The existing [third-party inventory](src/main/resources/assets/cwi_worldgen/THIRD_PARTY_NOTICES.txt)
and [Create MIT notice](src/main/resources/assets/cwi_worldgen/CREATE_CODE_LICENSE.txt)
are preserved. Wording inside the copied Create notice describes the upstream
Create code scope, not every file in CWI Worldgen.

## Combined Distribution

The original CWI GPL grant does not resolve the unknown-license material. The
Darker Depths noncommercial and Geode Plus noncommercial/share-alike terms also
cannot be replaced by an ordinary GPL-only grant for the combined program. Additional
permissions, verified compatible terms or independent replacements are needed
before representing the current combined source and JAR as a GPL-cleared public
release. Preserve the original repository privately while resolving these items.

## Verified Upstream Sources

Darker Depths' [1.20.1 source at commit 72fdfbc](https://github.com/futurenp/DarkerDepths/tree/72fdfbc0e5d320281793e91d352acbbb0900ba14)
declares version 2.1.5 in `gradle.properties` and includes
[CC BY-NC 3.0 in LICENSE.txt](https://github.com/futurenp/DarkerDepths/blob/72fdfbc0e5d320281793e91d352acbbb0900ba14/LICENSE.txt).
Its `META-INF/mods.toml` still declares All Rights Reserved. The existing bundled
inventory records that historical metadata; this supplemental finding does not
erase it or relicense upstream code.

Geode Plus' [upstream license notice at commit bd6cdc3](https://github.com/ChorusTeam/geode-plus/blob/bd6cdc37579b2fd432e4d7077944c187596be62d/LICENSE.md)
declares CC BY-NC-SA 3.0 and explicitly welcomes credited modpack use. That grant
does not remove its noncommercial/share-alike conditions. Upstream does not
provide a 1.2.5 Git tag; the imported version's metadata also declares
CC BY-NC-SA 3.0.

Copies of the verified Darker Depths license, Geode Plus notice and CC BY-NC-SA
3.0 legal text are included under `LICENSES/` in the repository and JAR.
