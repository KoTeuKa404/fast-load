package com.koteuka404.fastload.model;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

public final class FastModelStats {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final AtomicLong NEGATIVE_MODELS = new AtomicLong();
    private static volatile boolean summaryLogged;

    private FastModelStats() {
    }

    public static void recordNegative(Object location) {
        NEGATIVE_MODELS.incrementAndGet();
    }

    public static void logSummary() {
        if (summaryLogged) {
            return;
        }

        summaryLogged = true;
        LOGGER.info(
                "FastLoad model summary: negativeModelsCached={}, stacklessWrapperExceptions={}",
                NEGATIVE_MODELS.get(),
                FastModelException.getCreatedCount()
        );
    }
}
