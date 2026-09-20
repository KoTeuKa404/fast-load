package com.koteuka404.fastload.profiler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

public final class LoadStats {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CACHE_FAILURES = new AtomicLong();
    private static final AtomicLong SCANNED_CLASSES = new AtomicLong();
    private static final AtomicLong RESTORED_CLASSES = new AtomicLong();
    private static final AtomicLong SCAN_NANOS = new AtomicLong();
    private static final AtomicLong RESTORE_NANOS = new AtomicLong();
    private static final AtomicLong ESTIMATED_SAVED_NANOS = new AtomicLong();

    private LoadStats() {
    }

    public static void cacheHit(long restoredClasses, long restoreNanos, long previousScanNanos) {
        CACHE_HITS.incrementAndGet();
        RESTORED_CLASSES.addAndGet(restoredClasses);
        RESTORE_NANOS.addAndGet(restoreNanos);
        if (previousScanNanos > restoreNanos) {
            ESTIMATED_SAVED_NANOS.addAndGet(previousScanNanos - restoreNanos);
        }
    }

    public static void cacheMiss(long scannedClasses, long scanNanos) {
        CACHE_MISSES.incrementAndGet();
        SCANNED_CLASSES.addAndGet(scannedClasses);
        SCAN_NANOS.addAndGet(scanNanos);
    }

    public static void cacheFailure() {
        CACHE_FAILURES.incrementAndGet();
    }

    public static void logSummary() {
        LOGGER.info(
                "FastLoad summary: hits={}, misses={}, failures={}, restoredClasses={}, scannedClasses={}, restoreTime={} ms, scanTime={} ms, estimatedSaved={} ms",
                CACHE_HITS.get(),
                CACHE_MISSES.get(),
                CACHE_FAILURES.get(),
                RESTORED_CLASSES.get(),
                SCANNED_CLASSES.get(),
                nanosToMillis(RESTORE_NANOS.get()),
                nanosToMillis(SCAN_NANOS.get()),
                nanosToMillis(ESTIMATED_SAVED_NANOS.get())
        );
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
