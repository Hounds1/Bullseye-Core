package io.bullseye.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

class MonotonicClockTest {

    @Test
    void measuresElapsedNanosecondsAcrossTickerWrapAndZoneChanges() {
        Clock wall = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - 500_000);
        Clock clock = new MonotonicClock(wall, nanos::get);

        nanos.addAndGet(1_250_000);
        assertEquals(1_001, clock.millis());
        assertEquals(Instant.ofEpochMilli(1_001), clock.instant());

        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Clock zoned = clock.withZone(seoul);
        nanos.addAndGet(750_000);
        assertEquals(seoul, zoned.getZone());
        assertEquals(clock.instant(), zoned.instant());
        assertEquals(1_002, zoned.millis());
    }
}
