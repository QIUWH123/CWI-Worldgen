# Contributing To CWI Worldgen

## Build

Use Java 21 and the included Gradle wrapper:

```sh
./gradlew build
```

The distribution is `build/libs/cwi_worldgen-<mod_version>.jar`. Release version
and license metadata come from `gradle.properties`. Generated runtime resources
are committed, so an ordinary build does not require the local import tools.
Build dependencies are downloaded through Gradle; dependency JARs are not stored
in this repository or bundled into the mod.

## Verification

The normal build compiles production and native test sources. It does not launch
Minecraft or execute native GameTests. Native tests require an isolated CWI
content fixture with the required mods, block registrations and external data.
That fixture, its preparation tools and runtime data are maintained locally.
Do not use player saves or a production pack to run automated tests.

For release verification, build a clean checkout or an extracted `git archive`.
Check the resulting JAR against the release's `SHA256SUMS`. Resource bytes are
preserved by `.gitattributes`; do not normalize packaged resources accidentally.

## Changes

Keep changes within their owning subsystem and preserve existing IDs,
dependencies, saved generation profiles and unrelated work. Existing worlds
must retain their serialized terrain behavior unless a migration is explicitly
designed and approved. Test terrain geometry and feature placement separately.

Keep local configuration, dependency copies, tools, evidence, backups and saves
outside the tracked publication set and the distribution JAR. Never commit
credentials or local filesystem paths.

Original CWI contributions use GPL-3.0-only. Preserve upstream notices and
separate licenses, and check [LICENSE_SCOPE.md](LICENSE_SCOPE.md) before importing,
relicensing or publishing third-party material.
