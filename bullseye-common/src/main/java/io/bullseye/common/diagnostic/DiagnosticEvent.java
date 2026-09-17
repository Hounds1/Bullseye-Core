package io.bullseye.common.diagnostic;

import java.util.Objects;

public record DiagnosticEvent(
        String eventId,
        String host,
        String application,
        ResourceType resource,
        ResourceState state,
        Severity severity,
        long detectedAt,
        String reason) {

    public DiagnosticEvent {
        requireText(eventId, "eventId");
        requireText(host, "host");
        requireText(application, "application");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
        if (detectedAt < 0) {
            throw new IllegalArgumentException("detectedAt must not be negative");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
