# FastLoad

FastLoad is an experimental Forge 1.12.2 coremod that reduces repeated mod-discovery work between launches.

## What it caches

On the first launch FastLoad scans mod JARs normally and records:

- class entries required by `ModCandidate`;
- Forge ASM annotation data;
- discovered standard `@Mod` descriptors;
- class versions;
- the previous scan duration.

On later launches, unchanged JARs can restore this discovery data without reopening and ASM-parsing every `.class` file.

## Safety and fallback behavior

FastLoad is designed to fail open:

- corrupt or incompatible cache files are ignored and rebuilt;
- the cache uses an explicit bounded binary format, not Java object deserialization;
- writes go through a temporary file and atomic replace when supported;
- cache entries are tied to the Forge version and registered mod-container types;
- if cache restoration fails, the JAR is scanned normally;
- if the ASM transformer cannot patch Forge, original Forge discovery is left untouched.

The normal fast path validates file size and modification time. A SHA-256 digest is stored on creation and rechecked when metadata changes. Use `-Dfastload.strictHashes=true` to hash every cache hit if maximum validation is preferred over launch speed.

## JVM flags

- `-Dfastload.cache=false` — disable the discovery cache.
- `-Dfastload.strictHashes=true` — verify SHA-256 on every cache lookup.
- `-Dfastload.hashOnMetadataChange=false` — rebuild immediately when file metadata changes instead of checking whether content stayed identical.

Cache files are stored under:

```text
<minecraft instance>/fastload-cache/v1/
```

Deleting that directory is always safe; it only forces a rebuild on the next launch.

## Development

The Gradle setup follows the same Forge 1.12.2 style as ThaumicForever:

- Minecraft 1.12.2;
- ForgeGradle 2.3;
- Forge userdev `14.23.5.2820`;
- mappings `snapshot_20171003`;
- Java 8;
- Gradle wrapper 2.14.1.

Build on Windows:

```bat
gradlew.bat build
```

Run the dev client:

```bat
gradlew.bat runClient
```

The built JAR appears in `build/libs/`.

ForgeGradle 2.3 should be run with Java 8. Set `JAVA_HOME` to a Java 8 JDK before building rather than hardcoding a machine-specific JDK path in the repository.

## Current scope

v0.1 targets Forge's mod JAR discovery / ASM scan. It does not yet cache model baking, textures, registries, or slow initialization code inside individual mods. The built-in profiler reports discovery cache hits, misses, scanned/restored classes, and estimated time saved.
