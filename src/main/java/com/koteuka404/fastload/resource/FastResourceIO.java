package com.koteuka404.fastload.resource;

import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FastResourceIO {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, Boolean>>
            EXISTENCE_CACHE = new ConcurrentHashMap<IResourcePack, ConcurrentHashMap<ResourceLocation, Boolean>>();

    private static final AtomicLong OPENED_STREAMS = new AtomicLong();
    private static final AtomicLong EXISTENCE_QUERIES = new AtomicLong();
    private static final AtomicLong EXISTENCE_HITS = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();

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
        return resourcePack.getInputStream(location);
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

        LOGGER.info(
                "FastLoad resource I/O summary: bypassedLeakWrappers={}, fastMissingExceptions={}, existenceQueries={}, existenceCacheHits={}, cachedExistenceEntries={}, invalidations={}",
                OPENED_STREAMS.get(),
                FastFileNotFoundException.getCreatedCount(),
                EXISTENCE_QUERIES.get(),
                EXISTENCE_HITS.get(),
                cachedEntries,
                INVALIDATIONS.get()
        );

        EXISTENCE_CACHE.clear();
    }
}
