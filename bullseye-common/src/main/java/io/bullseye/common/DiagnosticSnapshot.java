package io.bullseye.common;

import java.util.Objects;

public record DiagnosticSnapshot(
        String host,
        String application,
        Severity severity,
        ResourceType resource,
        ResourceState state,
        long since,
        long version
) {

    public DiagnosticSnapshot {
        requireText(host, "host");
        requireText(application, "application");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(state, "state");
        if (since < 0) {
            throw new IllegalArgumentException("since must not be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public static DiagnosticSnapshot initial(String host, String application, long now) {
        return new DiagnosticSnapshot(
                host,
                application,
                Severity.NORMAL,
                ResourceType.UNKNOWN,
                ResourceState.NORMAL,
                now,
                1
        );
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
