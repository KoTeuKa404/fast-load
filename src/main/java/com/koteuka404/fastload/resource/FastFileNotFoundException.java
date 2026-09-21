package com.koteuka404.fastload.resource;

import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicLong;

public final class FastFileNotFoundException extends FileNotFoundException {
    private static final AtomicLong CREATED = new AtomicLong();

    public FastFileNotFoundException(String message) {
        super(message);
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
