package com.koteuka404.fastload.resource;

import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FastResourceIO {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, Boolean>>
            EXISTENCE_CACHE = new ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, Boolean>>();
    private static final ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, byte[]>>
            CONTENT_CACHE = new ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, byte[]>>();
    private static final ConcurrentHashMap<IResourcePack, PersistentModelCache>
            PERSISTENT_CONTENT_CACHE = new ConcurrentHashMap<IResourcePack, PersistentModelCache>();
    private static final java.util.Set<IResourcePack> NO_PERSISTENT_CONTENT_CACHE =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<IResourcePack, Boolean>());

    private static final boolean RESOURCE_CONTENT_CACHE =
            Boolean.parseBoolean(System.getProperty("fastload.resourceContentCache", "true"));
    private static final int MAX_CACHED_RESOURCE_BYTES = 256 * 1024;
    private static final long MAX_CACHED_CONTENT_BYTES = 64L * 1024L * 1024L;

    private static final AtomicLong OPENED_STREAMS = new AtomicLong();
    private static final AtomicLong EXISTENCE_QUERIES = new AtomicLong();
    private static final AtomicLong EXISTENCE_HITS = new AtomicLong();
    private static final AtomicLong PERSISTENT_EXISTENCE_HITS = new AtomicLong();
    private static final AtomicLong INDEXED_RESOURCE_ENTRIES = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();
    private static final AtomicLong CONTENT_CACHE_HITS = new AtomicLong();
    private static final AtomicLong PERSISTENT_CONTENT_CACHE_HITS = new AtomicLong();
    private static final AtomicLong RESEARCH_CLASSPATH_QUERIES = new AtomicLong();
    private static final AtomicLong RESEARCH_CLASSPATH_HITS = new AtomicLong();
    private static final AtomicLong RESEARCH_CLASSPATH_FALLBACKS = new AtomicLong();
    private static final AtomicLong CONTENT_CACHE_BYTES = new AtomicLong();

    private static volatile boolean summaryLogged;

    private FastResourceIO() {
    }

    /*
     * Object signatures are intentional. They are stable across the production
     * reobfuscation boundary and are cast back to the real Minecraft types here.
     */
    public static InputStream open(Object locationObject, Object resourcePackObject) throws IOException {
        ResourceLocation location = (ResourceLocation) locationObject;
        IResourcePack resourcePack = (IResourcePack) resourcePackObject;

        OPENED_STREAMS.incrementAndGet();
        if (!RESOURCE_CONTENT_CACHE || !isCacheableResource(location)) {
            return resourcePack.getInputStream(location);
        }

        ConcurrentHashMap<ResourceLocation, byte[]> packCache = CONTENT_CACHE.get(resourcePack);
        if (packCache != null) {
            byte[] cached = packCache.get(location);
            if (cached != null) {
                CONTENT_CACHE_HITS.incrementAndGet();
                return new ByteArrayInputStream(cached);
            }
        }

        PersistentModelCache persistentCache = persistentCacheFor(resourcePack);
        String cacheKey = location.toString();
        if (persistentCache != null) {
            byte[] cached = persistentCache.get(cacheKey);
            if (cached != null) {
                PERSISTENT_CONTENT_CACHE_HITS.incrementAndGet();
                return new ByteArrayInputStream(cached);
            }
        }

        return readAndCache(resourcePack, location, resourcePack.getInputStream(location), packCache, persistentCache, cacheKey);
    }

    public static boolean resourceExists(Object resourcePackObject, Object locationObject) {
        IResourcePack resourcePack = (IResourcePack) resourcePackObject;
        ResourceLocation location = (ResourceLocation) locationObject;

        EXISTENCE_QUERIES.incrementAndGet();

        ConcurrentHashMap<ResourceLocation, Boolean> packCache = EXISTENCE_CACHE.get(resourcePack);
        if (packCache == null) {
            ConcurrentHashMap<ResourceLocation, Boolean> created =
                    new ConcurrentHashMap<ResourceLocation, Boolean>();
            ConcurrentHashMap<ResourceLocation, Boolean> existing =
                    EXISTENCE_CACHE.putIfAbsent(resourcePack, created);
            packCache = existing == null ? created : existing;
        }

        Boolean cached = packCache.get(location);
        if (cached != null) {
            EXISTENCE_HITS.incrementAndGet();
            return cached.booleanValue();
        }

        PersistentModelCache persistentCache = persistentCacheFor(resourcePack);
        if (persistentCache != null) {
            Boolean indexed = persistentCache.resourceExists(location.toString());
            if (indexed != null) {
                PERSISTENT_EXISTENCE_HITS.incrementAndGet();
                packCache.putIfAbsent(location, indexed);
                return indexed.booleanValue();
            }
        }

        boolean exists = resourcePack.resourceExists(location);
        Boolean previous = packCache.putIfAbsent(location, Boolean.valueOf(exists));
        if (previous != null) {
            EXISTENCE_HITS.incrementAndGet();
            return previous.booleanValue();
        }

        return exists;
    }

    public static InputStream openClasspathResource(Object ownerObject, Object pathObject) {
        RESEARCH_CLASSPATH_QUERIES.incrementAndGet();
        InputStream stream = ClasspathResearchCache.open(ownerObject, pathObject);
        if (stream != null) {
            RESEARCH_CLASSPATH_HITS.incrementAndGet();
        } else {
            RESEARCH_CLASSPATH_FALLBACKS.incrementAndGet();
        }
        return stream;
    }

    public static void invalidateExistenceCache() {
        invalidateExistenceCache(null);
    }

    /**
     * Resource reloads replace the volatile existence/content results, but
     * unchanged Forge mod JARs remain immutable. Keep their already-loaded
     * persistent content cache when the same pack objects participate in the
     * reload. Dynamic/external packs are still fully discarded.
     *
     * The argument is Object on purpose: the injected call must survive the
     * production reobfuscation boundary without embedding Minecraft types in
     * the helper descriptor.
     */
    public static void invalidateExistenceCache(Object resourcePacksObject) {
        flushPersistentContentCaches();
        EXISTENCE_CACHE.clear();
        CONTENT_CACHE.clear();
        CONTENT_CACHE_BYTES.set(0L);

        Set<IResourcePack> activePacks = activeResourcePacks(resourcePacksObject);
        if (activePacks == null) {
            PERSISTENT_CONTENT_CACHE.clear();
            NO_PERSISTENT_CONTENT_CACHE.clear();
        } else {
            for (IResourcePack pack : PERSISTENT_CONTENT_CACHE.keySet()) {
                if (!activePacks.contains(pack)) {
                    PERSISTENT_CONTENT_CACHE.remove(pack);
                }
            }
            for (IResourcePack pack : NO_PERSISTENT_CONTENT_CACHE) {
                if (!activePacks.contains(pack)) {
                    NO_PERSISTENT_CONTENT_CACHE.remove(pack);
                }
            }
        }
        INVALIDATIONS.incrementAndGet();
    }

    public static void logSummary() {
        if (summaryLogged) {
            return;
        }

        summaryLogged = true;

        long cachedEntries = 0L;
        for (Map<ResourceLocation, Boolean> packCache : EXISTENCE_CACHE.values()) {
            cachedEntries += packCache.size();
        }

        long cachedContentEntries = 0L;
        for (Map<ResourceLocation, byte[]> packCache : CONTENT_CACHE.values()) {
            cachedContentEntries += packCache.size();
        }

        LOGGER.info(
                "FastLoad resource I/O summary: bypassedLeakWrappers={}, fastMissingExceptions={}, existenceQueries={}, existenceCacheHits={}, persistentExistenceCacheHits={}, indexedResourceEntries={}, cachedExistenceEntries={}, contentCacheHits={}, persistentContentCacheHits={}, cachedContentEntries={}, cachedContentBytes={}, researchClasspathQueries={}, researchClasspathHits={}, researchClasspathFallbacks={}, researchClasspathIndexEntries={}, invalidations={}",
                OPENED_STREAMS.get(),
                FastFileNotFoundException.getCreatedCount(),
                EXISTENCE_QUERIES.get(),
                EXISTENCE_HITS.get(),
                PERSISTENT_EXISTENCE_HITS.get(),
                INDEXED_RESOURCE_ENTRIES.get(),
                cachedEntries,
                CONTENT_CACHE_HITS.get(),
                PERSISTENT_CONTENT_CACHE_HITS.get(),
                cachedContentEntries,
                CONTENT_CACHE_BYTES.get(),
                RESEARCH_CLASSPATH_QUERIES.get(),
                RESEARCH_CLASSPATH_HITS.get(),
                RESEARCH_CLASSPATH_FALLBACKS.get(),
                ClasspathResearchCache.indexedEntries(),
                INVALIDATIONS.get()
        );

        flushPersistentContentCaches();
        EXISTENCE_CACHE.clear();
        CONTENT_CACHE.clear();
        CONTENT_CACHE_BYTES.set(0L);
        PERSISTENT_CONTENT_CACHE.clear();
        NO_PERSISTENT_CONTENT_CACHE.clear();
    }

    private static boolean isCacheableResource(ResourceLocation location) {
        String path = location.toString();
        int separator = path.indexOf(':');
        if (separator >= 0) {
            path = path.substring(separator + 1);
        }
        if (path.startsWith("textures/")
                || path.startsWith("sounds/")
                || path.startsWith("shaders/")
                || path.startsWith("font/")) {
            return false;
        }

        String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
        return lowerPath.endsWith(".json")
                || lowerPath.endsWith(".json5")
                || lowerPath.endsWith(".mcmeta")
                || lowerPath.endsWith(".lang")
                || lowerPath.endsWith(".properties")
                || lowerPath.endsWith(".cfg")
                || lowerPath.endsWith(".toml")
                || lowerPath.endsWith(".xml")
                || lowerPath.endsWith(".txt")
                || lowerPath.endsWith(".zs")
                || lowerPath.endsWith(".obj")
                || lowerPath.endsWith(".mtl")
                || lowerPath.endsWith(".csv");
    }

    private static InputStream readAndCache(
            IResourcePack resourcePack,
            ResourceLocation location,
            InputStream source,
            ConcurrentHashMap<ResourceLocation, byte[]> packCache,
            PersistentModelCache persistentCache,
            String cacheKey
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        int read;
        while ((read = source.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }

            int currentSize = output.size();
            if (currentSize + read > MAX_CACHED_RESOURCE_BYTES) {
                int prefixLength = MAX_CACHED_RESOURCE_BYTES - currentSize;
                if (prefixLength > 0) {
                    output.write(buffer, 0, prefixLength);
                }

                InputStream prefix = new ByteArrayInputStream(output.toByteArray());
                InputStream remainder = new ByteArrayInputStream(
                        buffer,
                        Math.max(prefixLength, 0),
                        read - Math.max(prefixLength, 0)
                );
                return new SequenceInputStream(prefix, new SequenceInputStream(remainder, source));
            }

            output.write(buffer, 0, read);
        }

        byte[] content = output.toByteArray();
        source.close();

        if (packCache == null) {
            ConcurrentHashMap<ResourceLocation, byte[]> created =
                    new ConcurrentHashMap<ResourceLocation, byte[]>();
            ConcurrentHashMap<ResourceLocation, byte[]> existing = CONTENT_CACHE.putIfAbsent(resourcePack, created);
            packCache = existing == null ? created : existing;
        }

        long total = CONTENT_CACHE_BYTES.addAndGet(content.length);
        if (total <= MAX_CACHED_CONTENT_BYTES) {
            byte[] previous = packCache.putIfAbsent(location, content);
            if (previous != null) {
                CONTENT_CACHE_BYTES.addAndGet(-content.length);
            }
        } else {
            CONTENT_CACHE_BYTES.addAndGet(-content.length);
        }

        if (persistentCache != null) {
            persistentCache.put(cacheKey, content);
        }

        return new ByteArrayInputStream(content);
    }

    private static PersistentModelCache persistentCacheFor(IResourcePack resourcePack) {
        PersistentModelCache cached = PERSISTENT_CONTENT_CACHE.get(resourcePack);
        if (cached != null || NO_PERSISTENT_CONTENT_CACHE.contains(resourcePack)) {
            return cached;
        }

        PersistentModelCache created = PersistentModelCache.forPack(resourcePack);
        if (created == null) {
            NO_PERSISTENT_CONTENT_CACHE.add(resourcePack);
            return null;
        }

        PersistentModelCache existing = PERSISTENT_CONTENT_CACHE.putIfAbsent(resourcePack, created);
        if (existing == null) {
            INDEXED_RESOURCE_ENTRIES.addAndGet(created.indexedResourceCount());
        }
        return existing == null ? created : existing;
    }

    private static void flushPersistentContentCaches() {
        for (PersistentModelCache cache : PERSISTENT_CONTENT_CACHE.values()) {
            cache.flush();
        }
    }

    private static Set<IResourcePack> activeResourcePacks(Object resourcePacksObject) {
        if (!(resourcePacksObject instanceof Iterable<?>)) {
            return null;
        }

        Set<IResourcePack> active = Collections.newSetFromMap(
                new IdentityHashMap<IResourcePack, Boolean>()
        );
        for (Object value : (Iterable<?>) resourcePacksObject) {
            if (value instanceof IResourcePack) {
                active.add((IResourcePack) value);
            }
        }
        return active;
    }
}
