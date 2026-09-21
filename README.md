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

## v0.5 resource I/O fast path

v0.5 targets a vanilla 1.12.2 debug-only cost that becomes significant in large packs. `FallbackResourceManager` normally wraps every resource stream in DEBUG mode and captures a full Java stack trace so leaked streams can later be diagnosed. During model and texture loading this can happen thousands of times.

FastLoad now bypasses that diagnostic wrapper by default and opens the exact same resource stream directly. It also replaces resource-manager `FileNotFoundException` instances with a stackless subclass. Missing-resource behavior is unchanged, but exception creation is much cheaper when mods probe many model/resource paths.

The final startup log includes a resource-I/O summary with the number of bypassed leak wrappers and fast missing-resource exceptions.

Compatibility flags:

```text
-Dfastload.resourceLeakTracking=true
-Dfastload.fastMissingResources=false
```

The first restores vanilla leaked-stream stacktrace tracking. The second restores normal `FileNotFoundException` allocation.

## v0.4 lazy recipe search tree

Profiling a 190-mod pack showed that Forge's final no-remap registry freeze spent a large amount of time rebuilding search trees even though item IDs had not changed.

FastLoad already reuses the existing item search tree for that specific initial freeze. v0.4 additionally makes the final recipe search tree lazy:

- the item search tree is reused;
- a lightweight recipe-search placeholder is registered immediately;
- the expensive recipe tooltip / suffix-array indexing happens only when the vanilla recipe-book search is actually used;
- resource reloads before first use do not force the lazy tree to build;
- once built, later resource reloads use normal SearchTree recalculation;
- real remap events and later frozen mapping events still use Forge's full vanilla rebuild.

This moves recipe-search indexing out of the critical startup path. Packs that primarily use JEI can avoid paying that cost during startup entirely.

To disable only the lazy behavior while keeping the v0.3 item-tree reuse:

```text
-Dfastload.lazyRecipeSearchTree=false
```

To disable the whole final search-tree optimization:

```text
-Dfastload.optimizeFinalSearchTrees=false
```

## Safety and fallback behavior

FastLoad is designed to fail open:

- corrupt or incompatible discovery cache files are ignored and rebuilt;
- the cache uses an explicit bounded binary format, not Java object deserialization;
- writes go through a temporary file and atomic replace when supported;
- cache entries are tied to the Forge version and registered mod-container types;
- if cache restoration fails, the JAR is scanned normally;
- if an ASM transformer cannot patch Forge, original Forge behavior is left untouched;
- the search-tree optimization applies only to the first frozen event with zero remapped registries, and otherwise uses vanilla behavior;
- if the optimized search-tree setup fails, Forge's full rebuild is used immediately.

## JVM flags

- `-Dfastload.cache=false` — disable the discovery cache.
- `-Dfastload.strictHashes=true` — verify SHA-256 on every cache lookup.
- `-Dfastload.hashOnMetadataChange=false` — rebuild immediately when file metadata changes instead of checking whether content stayed identical.
- `-Dfastload.optimizeFinalSearchTrees=false` — disable final search-tree optimization.
- `-Dfastload.lazyRecipeSearchTree=false` — eagerly rebuild the final recipe search tree instead of deferring it.
- `-Dfastload.resourceLeakTracking=true` — restore vanilla DEBUG leaked-resource stream stacktrace tracking.
- `-Dfastload.fastMissingResources=false` — use normal stack-filled `FileNotFoundException` instances for missing resources.

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

v0.5 also removes debug-only per-resource stacktrace capture and uses cheap missing-resource exceptions, reducing model/resource loading overhead while keeping the same resource lookup results. Model baking, textures, CraftTweaker execution, and arbitrary mod lifecycle code are not yet cached.
