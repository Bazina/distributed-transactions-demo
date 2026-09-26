package com.demo.booking.util;

/** Measures how long a step took, so the logs can show where the time goes. */
public final class Timer {
    private final long startedAt = System.nanoTime();

    /**
     * @return milliseconds since this timer was created
     */
    public long elapsedMillis() {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
