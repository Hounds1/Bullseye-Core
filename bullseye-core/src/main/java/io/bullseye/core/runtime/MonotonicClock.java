package io.bullseye.core.runtime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Measures elapsed time from the wall-clock instant recorded when the clock starts. */
public final class MonotonicClock extends Clock {

    private final ZoneId zone;
    private final LongSupplier nanoTime;
    private final long originMillis;
    private final long originNanos;

    public MonotonicClock(Clock wallClock) {
        this(wallClock, System::nanoTime);
    }

    MonotonicClock(Clock wallClock, LongSupplier nanoTime) {
        this.zone = Objects.requireNonNull(wallClock, "wallClock").getZone();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.originMillis = wallClock.millis();
        this.originNanos = nanoTime.getAsLong();
    }

    private MonotonicClock(
            ZoneId zone, LongSupplier nanoTime, long originMillis, long originNanos) {
        this.zone = zone;
        this.nanoTime = nanoTime;
        this.originMillis = originMillis;
        this.originNanos = originNanos;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return this.zone.equals(zone)
                ? this
                : new MonotonicClock(zone, nanoTime, originMillis, originNanos);
    }

    @Override
    public long millis() {
        // Subtract before converting to milliseconds so a wrapping nanoTime origin is safe.
        long elapsedNanos = nanoTime.getAsLong() - originNanos;
        return Math.addExact(originMillis, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }
}
