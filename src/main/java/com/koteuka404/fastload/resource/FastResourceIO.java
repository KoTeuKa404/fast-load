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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FastResourceIO {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, Boolean>>
            EXISTENCE_CACHE = new ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, Boolean>>();
    private static final ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, byte[]>>
            CONTENT_CACHE = new ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, byte[]>>();

    private static final boolean RESOURCE_CONTENT_CACHE =
            Boolean.parseBoolean(System.getProperty("fastload.resourceContentCache", "true"));
    private static final int MAX_CACHED_RESOURCE_BYTES = 256 * 1024;
    private static final long MAX_CACHED_CONTENT_BYTES = 64L * 1024L * 1024L;

    private static final AtomicLong OPENED_STREAMS = new AtomicLong();
    private static final AtomicLong EXISTENCE_QUERIES = new AtomicLong();
    private static final AtomicLong EXISTENCE_HITS = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();
    private static final AtomicLong CONTENT_CACHE_HITS = new AtomicLong();
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
        if (!RESOURCE_CONTENT_CACHE || !isCacheableModelResource(location)) {
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

        return readAndCache(resourcePack, location, resourcePack.getInputStream(location), packCache);
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

        boolean exists = resourcePack.resourceExists(location);
        Boolean previous = packCache.putIfAbsent(location, Boolean.valueOf(exists));
        if (previous != null) {
            EXISTENCE_HITS.incrementAndGet();
            return previous.booleanValue();
        }

        return exists;
    }

    public static void invalidateExistenceCache() {
        EXISTENCE_CACHE.clear();
        CONTENT_CACHE.clear();
        CONTENT_CACHE_BYTES.set(0L);
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
                "FastLoad resource I/O summary: bypassedLeakWrappers={}, fastMissingExceptions={}, existenceQueries={}, existenceCacheHits={}, cachedExistenceEntries={}, contentCacheHits={}, cachedContentEntries={}, cachedContentBytes={}, invalidations={}",
                OPENED_STREAMS.get(),
                FastFileNotFoundException.getCreatedCount(),
                EXISTENCE_QUERIES.get(),
                EXISTENCE_HITS.get(),
                cachedEntries,
                CONTENT_CACHE_HITS.get(),
                cachedContentEntries,
                CONTENT_CACHE_BYTES.get(),
                INVALIDATIONS.get()
        );

        EXISTENCE_CACHE.clear();
        CONTENT_CACHE.clear();
        CONTENT_CACHE_BYTES.set(0L);
    }

    private static boolean isCacheableModelResource(ResourceLocation location) {
        String path = location.toString();
        int separator = path.indexOf(':');
        if (separator >= 0) {
            path = path.substring(separator + 1);
        }
        return path.endsWith(".json")
                && (path.startsWith("models/") || path.startsWith("blockstates/"));
    }

    private static InputStream readAndCache(
            IResourcePack resourcePack,
            ResourceLocation location,
            InputStream source,
            ConcurrentHashMap<ResourceLocation, byte[]> packCache
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

        return new ByteArrayInputStream(content);
    }
}
