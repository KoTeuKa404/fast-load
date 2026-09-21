package com.koteuka404.fastload.resource;

import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

public final class FastResourceIO {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final AtomicLong OPENED_STREAMS = new AtomicLong();
    private static volatile boolean summaryLogged;

    private FastResourceIO() {
    }

    public static InputStream open(ResourceLocation location, IResourcePack resourcePack) throws IOException {
        OPENED_STREAMS.incrementAndGet();
        return resourcePack.getInputStream(location);
    }

    public static void logSummary() {
        if (summaryLogged) {
            return;
        }

        summaryLogged = true;
        LOGGER.info(
                "FastLoad resource I/O summary: bypassedLeakWrappers={}, fastMissingExceptions={}",
                OPENED_STREAMS.get(),
                FastFileNotFoundException.getCreatedCount()
        );
    }
}
