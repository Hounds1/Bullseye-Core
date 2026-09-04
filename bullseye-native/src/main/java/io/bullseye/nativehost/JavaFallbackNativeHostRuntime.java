package io.bullseye.nativehost;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

public final class JavaFallbackNativeHostRuntime implements NativeHostRuntime {

    private final Clock clock;

    public JavaFallbackNativeHostRuntime(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public HostNativeSnapshot snapshot() {
        return new HostNativeSnapshot(clock.millis(), Map.of());
    }
}
