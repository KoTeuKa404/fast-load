# FastLoad

FastLoad is an experimental Forge 1.12.2 coremod that reduces repeated startup work between launches and profiles expensive Forge loading phases.

## What it caches

On the first launch FastLoad scans mod JARs normally and records:

- class entries required by `ModCandidate`;
- Forge ASM annotation data;
- discovered standard `@Mod` descriptors;
- class versions;
- the previous scan duration.

On later launches, unchanged JARs can restore this discovery data without reopening and ASM-parsing every `.class` file.

## v0.2 startup profiling

v0.2 keeps the v0.1 discovery cache and additionally instruments Forge's `ForgeModContainer.mappingChanged()` path.

The original Forge operations still run in the same order:

1. `OreDictionary.rebakeMap()`
2. `StatList.reinit()`
3. `Ingredient.invalidateAll()`
4. `FMLCommonHandler.resetClientRecipeBook()`
5. `FMLCommonHandler.reloadSearchTrees()`
6. `FMLCommonHandler.reloadCreativeSettings()`

FastLoad only measures them and prints one summary line such as:

```text
FastLoad ModIdMapping profile: total=..., oreDictionary=..., statList=..., ingredients=..., recipeBook=..., searchTrees=..., creativeSettings=...
```

This profiling step is intentionally conservative: it does not skip any Forge work. The measurements identify which operation is responsible for slow `ModIdMapping` on large 1.12.2 packs so later optimizations can target the real bottleneck safely.

## Safety and fallback behavior

FastLoad is designed to fail open:

- corrupt or incompatible cache files are ignored and rebuilt;
- the cache uses an explicit bounded binary format, not Java object deserialization;
- writes go through a temporary file and atomic replace when supported;
- cache entries are tied to the Forge version and registered mod-container types;
- if cache restoration fails, the JAR is scanned normally;
- if an ASM transformer cannot patch Forge, original Forge behavior is left untouched.

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

v0.2 caches Forge mod JAR discovery and profiles the expensive Forge ModIdMapping path. It does not yet cache model baking, textures, registries, CraftTweaker execution, or arbitrary initialization code inside individual mods.
