# FastLoad

FastLoad is an experimental Forge 1.12.2 coremod that reduces repeated startup work between launches and profiles expensive Forge loading phases.

## Discovery cache

On the first launch FastLoad scans mod JARs normally and records:

- class entries required by `ModCandidate`;
- Forge ASM annotation data;
- discovered standard `@Mod` descriptors;
- class versions;
- the previous scan duration.

On later launches, unchanged JARs can restore this discovery data without reopening and ASM-parsing every `.class` file.

## v0.3 final search-tree optimization

Profiling a 190-mod pack showed that Forge's final `FMLModIdMappingEvent` spent almost all of its time in `FMLCommonHandler.reloadSearchTrees()`.

Forge 1.12.2 calls `GameData.freezeData()` with an empty remap map because no IDs changed at that point. FastLoad v0.3 therefore optimizes only the first frozen mapping event with zero remapped registries:

- the already-built item search tree is reused;
- the recipe search tree is rebuilt from the final `RecipeBookClient.ALL_RECIPES`, so recipe changes made during init/post-init are reflected;
- all other mapping events keep the original full Forge search-tree rebuild;
- if the existing item tree is missing or the optimized path throws, FastLoad immediately falls back to the vanilla full rebuild.

This avoids repeating expensive tooltip/sub-item indexing for every item on the final no-remap freeze while preserving a fresh recipe search index.

To force vanilla behavior:

```text
-Dfastload.optimizeFinalSearchTrees=false
```

The ModIdMapping profiler remains enabled and reports the time spent in each Forge mapping step.

## Safety and fallback behavior

FastLoad is designed to fail open:

- corrupt or incompatible discovery cache files are ignored and rebuilt;
- the cache uses an explicit bounded binary format, not Java object deserialization;
- writes go through a temporary file and atomic replace when supported;
- cache entries are tied to the Forge version and registered mod-container types;
- if cache restoration fails, the JAR is scanned normally;
- if an ASM transformer cannot patch Forge, original Forge behavior is left untouched;
- the search-tree optimization applies only to the first frozen event with zero remapped registries, and otherwise uses vanilla behavior.

## JVM flags

- `-Dfastload.cache=false` — disable the discovery cache.
- `-Dfastload.strictHashes=true` — verify SHA-256 on every cache lookup.
- `-Dfastload.hashOnMetadataChange=false` — rebuild immediately when file metadata changes instead of checking whether content stayed identical.
- `-Dfastload.optimizeFinalSearchTrees=false` — disable the v0.3 search-tree optimization and use Forge's full rebuild.

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

The built JAR appears in `build/libs/`.

## Current scope

v0.3 caches Forge mod JAR discovery and avoids one redundant full item search-tree rebuild during the initial no-remap registry freeze. It does not yet cache model baking, textures, CraftTweaker execution, or arbitrary initialization code inside individual mods.
