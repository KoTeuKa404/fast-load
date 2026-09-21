package com.koteuka404.fastload.model;

import java.util.concurrent.atomic.AtomicLong;

public final class FastModelException extends Exception {
    private static final AtomicLong CREATED = new AtomicLong();

    public FastModelException(String message, Throwable cause) {
        super(message, cause);
        CREATED.incrementAndGet();
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    static long getCreatedCount() {
        return CREATED.get();
    }
}
